package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Clip page knows of its clip over time. */
internal sealed interface ClipPresence {
  data object Loading : ClipPresence

  /** The catalogue has no such clip (an old link): "That clip is no longer on this phone." */
  data object Gone : ClipPresence

  /** The clip, or its last known state with [deleted] when it was deleted on the computer while the page is open. */
  data class Known(val detail: ClipDetail, val deleted: Boolean = false) : ClipPresence
}

/** The Shorts tab: still reading, not on this phone ("The transcript comes with the clip."), or its rows. */
internal sealed interface ShortsState {
  data object Loading : ShortsState

  data object NotHere : ShortsState

  data class Ready(val rows: List<ShortRowState>) : ShortsState
}

/** What the Clip page shows (spec F-05 v1-core, design 10.2 P-02, render `16`). */
@Immutable
internal data class ClipUiState(
  val presence: ClipPresence = ClipPresence.Loading,
  val poster: File? = null,
  val shorts: ShortsState = ShortsState.Loading,
  val changes: List<ChangeRowState> = emptyList(),
  /** The short "Watch" resumes at ("Watch · short 4"); null starts at the first one. */
  val resume: ShortInfo? = null,
  /** This phone's request for the clip while the computer still analyzes it. */
  val request: Request? = null,
  val connected: Boolean = false,
  val wanted: Boolean = false,
  /** The speech model of "About this clip" (`meta.models.asr`), once the clip data is read. */
  val speechModel: String? = null,
  val tab: ClipTab = ClipTab.SHORTS,
  val now: Instant = Instant.EPOCH,
  val zone: ZoneId = ZoneId.of("UTC"),
) {
  val detail: ClipDetail? get() = (presence as? ClipPresence.Known)?.detail
}

/**
 * The Clip page (spec F-05, v1-core). While it is shown it holds a `ClipLease`, so a deletion on the computer waits
 * until the learner leaves (architecture 7.7, QT-10), and it marks the clip opened (study protection, architecture
 * 7.5). The selected tab and "Download when connected" live in the [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ClipViewModel.Factory::class)
internal class ClipViewModel @AssistedInject constructor(
  @Assisted key: ClipKey,
  private val clips: ClipRepository,
  shorts: ShortRepository,
  private val content: ClipContentRepository,
  private val progress: ProgressRepository,
  private val requests: RequestRepository,
  reference: ReferenceRepository,
  status: ComputerStatusRepository,
  private val clock: Clock,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  val jobId: JobId = key.jobId
  private val lease = content.open(jobId)
  private val busy = mutableSetOf<String>()

  init {
    if (savedState.get<String>(TAB) == null) savedState[TAB] = key.tab.name
    viewModelScope.launch { progress.markOpened(jobId) }
  }

  private val presence: Flow<ClipPresence> = clips.clip(jobId)
    .catch { if (it is CancellationException) throw it else emit(null) }
    .runningFold<ClipDetail?, ClipPresence>(ClipPresence.Loading) { before, detail ->
      when {
        detail != null -> ClipPresence.Known(detail)
        before is ClipPresence.Known -> before.copy(deleted = true)
        else -> ClipPresence.Gone
      }
    }
    .distinctUntilChanged()

  private val availability: Flow<ClipAvailability?> =
    presence.map { (it as? ClipPresence.Known)?.detail?.summary?.availability }.distinctUntilChanged()

  /** The parsed clip data, read again when the clip arrives or changes on this phone; null when unreadable. */
  private val clipData: Flow<ClipData?> = availability.mapLatest { here ->
    if (here != ClipAvailability.OnThisPhone && here !is ClipAvailability.Bringing) return@mapLatest null
    try {
      content.clipData(jobId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unreadable: Exception) {
      null
    }
  }.onStart { emit(null) }

  private val poster: Flow<File?> = availability.mapLatest {
    try {
      content.media(jobId)?.poster
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unreadable: Exception) {
      null
    }
  }.onStart { emit(null) }

  private val shortsOf: Flow<List<ShortInfo>?> = shorts.shorts(jobId)
    .map<List<ShortInfo>, List<ShortInfo>?> { it }
    .catch { if (it is CancellationException) throw it else emit(emptyList()) }
    .onStart { emit(null) }

  private val resumeId: Flow<ShortId?> = progress.resume(jobId).catch { emit(null) }.onStart { emit(null) }

  private val study = combine(shortsOf, clipData, progress.seen().catch { emit(emptySet()) }.onStart { emit(emptySet()) },
    resumeId, reference.reference) { list, data, seen, resume, ref -> StudyParts(list, data, seen, resume, ref) }

  private val context = combine(
    requests.requests().map { all -> all.firstOrNull { it.jobId == jobId } }.catch { emit(null) }.onStart { emit(null) },
    status.status,
    savedState.getStateFlow(TAB, key.tab.name),
    savedState.getStateFlow(WANTED, false),
    poster,
  ) { request, now, tab, wanted, picture -> ContextParts(request, now is ComputerStatus.Connected, tab, wanted, picture) }

  val state: StateFlow<ClipUiState> = combine(presence, study, context) { presence, study, context ->
    val detail = (presence as? ClipPresence.Known)?.detail
    val resume = study.resume ?: detail?.resume
    val shortsState = when {
      study.shorts == null -> ShortsState.Loading
      study.shorts.isEmpty() && detail?.summary?.availability != ClipAvailability.OnThisPhone -> ShortsState.NotHere
      else -> ShortsState.Ready(ClipRules.shortRows(study.shorts, study.data, study.reference, study.seen, resume))
    }
    ClipUiState(
      presence = presence,
      poster = context.poster,
      shorts = shortsState,
      changes = detail?.let { ClipRules.changeRows(it.changeCounts, study.reference) }.orEmpty(),
      resume = study.shorts?.firstOrNull { it.id == resume },
      request = context.request,
      connected = context.connected,
      wanted = context.wanted,
      speechModel = study.data?.meta?.models?.asr,
      tab = ClipTab.entries.firstOrNull { it.name == context.tab } ?: ClipTab.SHORTS,
      now = clock.now(),
      zone = clock.zone(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ClipUiState(tab = key.tab))

  fun selectTab(tab: ClipTab) {
    savedState[TAB] = tab.name
  }

  fun setKept(kept: Boolean) = once("keep") { clips.setKept(jobId, kept) }

  /** "Download to this phone" / "Download when connected": marks the clip wanted (F-05-AC4). */
  fun download() = once("download") {
    savedState[WANTED] = true
    clips.downloadToPhone(jobId)
  }

  fun downloadVideo() = once("video") { clips.downloadVideo(jobId) }

  fun removeVideo() = once("video") { clips.removeVideo(jobId) }

  /** "Remove from this phone" after its confirmation: local only, nothing reaches the computer (F-05-AC11). */
  fun removeFromPhone() = once("remove") {
    savedState[WANTED] = false
    clips.removeFromPhone(jobId)
  }

  /** The processing request's inline action that [LibraryRules.route] says is done here. */
  fun perform(id: RequestId, action: RequestAction) = once("request:${id.value}:${action.name}") { requests.perform(id, action) }

  /** Runs [block] unless the same action is still running (architecture 3.1's double-tap guard). */
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

  override fun onCleared() {
    lease.close()
  }

  private data class StudyParts(
    val shorts: List<ShortInfo>?,
    val data: ClipData?,
    val seen: Set<ShortId>,
    val resume: ShortId?,
    val reference: Reference,
  )

  private data class ContextParts(
    val request: Request?,
    val connected: Boolean,
    val tab: String,
    val wanted: Boolean,
    val poster: File?,
  )

  @AssistedFactory
  interface Factory {
    fun create(key: ClipKey): ClipViewModel
  }

  private companion object {
    const val TAB = "tab"
    const val WANTED = "wanted"
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
