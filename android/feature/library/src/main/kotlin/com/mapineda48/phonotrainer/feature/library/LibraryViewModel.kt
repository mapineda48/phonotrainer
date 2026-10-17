package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The catalogue as the Library knows it: still loading, unreadable, or the clips. */
internal sealed interface Catalogue {
  data object Loading : Catalogue

  data object Failed : Catalogue

  /** Every clip of the catalogue, each with its local poster when the phone has one. */
  data class Ready(val cards: List<ClipCardState>) : Catalogue
}

/** What the Library shows (spec F-04, design 10.2 P-01). [clips] are the rows after search, filter and sort. */
@Immutable
internal data class LibraryUiState(
  val catalogue: Catalogue = Catalogue.Loading,
  val search: String = "",
  val filter: ClipFilter = ClipFilter.ALL,
  val sort: ClipSort = ClipSort.NEWEST,
  val clips: List<ClipCardState> = emptyList(),
  val total: Int = 0,
  val inProgress: List<Request> = emptyList(),
  val startedOnComputer: Int = 0,
  val paired: Boolean = false,
  /** Not connected while paired: the list works from the local catalogue (the offline banner). */
  val offline: Boolean = false,
  val lastSync: Instant? = null,
  val now: Instant = Instant.EPOCH,
  val zone: ZoneId = ZoneId.of("UTC"),
) {
  val loading: Boolean get() = catalogue == Catalogue.Loading
  val failed: Boolean get() = catalogue == Catalogue.Failed

  /** No clip at all, so the first-run or empty state shows instead of the list's controls. */
  val empty: Boolean get() = catalogue is Catalogue.Ready && total == 0
}

/**
 * The Library (spec F-04). It reads the whole catalogue once (`ClipRepository.clips(ClipQuery())`, called once per
 * ViewModel) and applies search, filter and sort itself ([LibraryRules.arrange]), which gives the live count "8 of
 * 10 clips" and an order that never depends on how a list arrived. Search, filter and sort live in the
 * [SavedStateHandle], so Back from the feed, a tab switch and a process death keep them (F-04-AC5); the scroll
 * position is the screen's saveable list state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class LibraryViewModel @Inject constructor(
  private val clips: ClipRepository,
  private val requests: RequestRepository,
  private val content: ClipContentRepository,
  status: ComputerStatusRepository,
  private val clock: Clock,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  private val attempts = MutableStateFlow(0)
  private val posterCache = mutableMapOf<String, File?>()
  private val busy = mutableSetOf<String>()

  private val catalogue: Flow<Catalogue> = attempts.flatMapLatest {
    clips.clips(ClipQuery())
      .map<List<ClipSummary>, Catalogue> { Catalogue.Ready(withPosters(it)) }
      .onStart { emit(Catalogue.Loading) }
      .catch { if (it is CancellationException) throw it else emit(Catalogue.Failed) }
  }

  private val controls: Flow<Triple<String, ClipFilter, ClipSort>> = combine(
    savedState.getStateFlow(SEARCH, ""),
    savedState.getStateFlow(FILTER, ClipFilter.ALL.name),
    savedState.getStateFlow(SORT, ClipSort.NEWEST.name),
  ) { search, filter, sort ->
    Triple(search, ClipFilter.entries.firstOrNull { it.name == filter } ?: ClipFilter.ALL,
      ClipSort.entries.firstOrNull { it.name == sort } ?: ClipSort.NEWEST)
  }

  private val queue: Flow<Pair<List<Request>, Int>> = combine(
    requests.requests().catch { emit(emptyList()) },
    clips.browserJobs().catch { emit(emptyList()) },
  ) { own, browser -> LibraryRules.inProgress(own) to LibraryRules.startedOnComputer(browser) }

  private val connection: Flow<Triple<Boolean, Boolean, Instant?>> = combine(status.status, status.paired) { now, paired ->
    Triple(paired != null, paired != null && now !is ComputerStatus.Connected, paired?.lastSync)
  }

  val state: StateFlow<LibraryUiState> = combine(catalogue, controls, queue, connection) { catalogue, controls, queue, link ->
    val (search, filter, sort) = controls
    val all = (catalogue as? Catalogue.Ready)?.cards.orEmpty()
    val shown = LibraryRules.arrange(all.map { it.clip }, search, filter, sort)
    val byId = all.associateBy { it.clip.id }
    LibraryUiState(
      catalogue = catalogue,
      search = search,
      filter = filter,
      sort = sort,
      clips = shown.map { byId.getValue(it.id) },
      total = all.size,
      inProgress = queue.first,
      startedOnComputer = queue.second,
      paired = link.first,
      offline = link.second,
      lastSync = link.third,
      now = clock.now(),
      zone = clock.zone(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

  fun setSearch(text: String) {
    savedState[SEARCH] = text
  }

  /** Selects [filter], or every clip when it is already selected (the chips toggle). */
  fun toggleFilter(filter: ClipFilter) {
    val current = savedState.get<String>(FILTER)
    savedState[FILTER] = if (current == filter.name) ClipFilter.ALL.name else filter.name
  }

  fun showAll() {
    savedState[FILTER] = ClipFilter.ALL.name
  }

  fun setSort(sort: ClipSort) {
    savedState[SORT] = sort.name
  }

  fun retry() {
    attempts.value++
  }

  fun setKept(id: JobId, kept: Boolean) = once("keep:${id.value}") { clips.setKept(id, kept) }

  /** "Download to this phone": marks it wanted; it downloads now or the next time the computer is reachable. */
  fun download(id: JobId) = once("download:${id.value}") { clips.downloadToPhone(id) }

  /** "Remove from this phone" after its confirmation: local only, nothing is sent to the computer (F-05-AC11). */
  fun removeFromPhone(id: JobId) = once("remove:${id.value}") { clips.removeFromPhone(id) }

  fun removeVideo(id: JobId) = once("video:${id.value}") { clips.removeVideo(id) }

  /** An "In progress" row's inline action that [LibraryRules.route] says is done here. */
  fun perform(id: RequestId, action: RequestAction) =
    once("request:${id.value}:${action.name}") { requests.perform(id, action) }

  /** Runs [block] unless the same action is still running (a double tap writes once, architecture 3.1). */
  private fun once(key: String, block: suspend () -> Unit) {
    if (!busy.add(key)) return
    viewModelScope.launch {
      try {
        block()
      } finally {
        busy.remove(key)
      }
    }
  }

  /** The local poster of each clip (`ClipMedia.poster`), looked up once per clip and availability. */
  private suspend fun withPosters(all: List<ClipSummary>): List<ClipCardState> = all.map { clip ->
    val key = "${clip.id.value}|${clip.availability}|${clip.video}"
    val poster = if (posterCache.containsKey(key)) {
      posterCache[key]
    } else {
      val found = try {
        content.media(clip.id)?.poster
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (unreadable: Exception) {
        null
      }
      posterCache[key] = found
      found
    }
    ClipCardState(clip, poster = poster)
  }

  private companion object {
    const val SEARCH = "search"
    const val FILTER = "filter"
    const val SORT = "sort"
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
