package com.mapineda48.phonotrainer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.KeepWatching
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import com.mapineda48.phonotrainer.core.ui.component.ConnectionCardExtras
import com.mapineda48.phonotrainer.core.ui.component.FamilyCount
import com.mapineda48.phonotrainer.core.ui.extension.InsightsAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home (spec F-03): what is true about the computer and this phone's requests, what to study today, and the way into
 * every clip and feed. One state for the whole dashboard, built from the domain repositories (architecture 3.1) and
 * kept while the shell keeps the entry (16.2), so Back from the feed finds Home as it was (F-03-AC8).
 *
 * Today's set is made once per local day by [SetRepository.ensureToday] (F-08 item 3). Home asks for it while it is
 * shown, whenever clips are on this phone and the repository shows no set of the local day: the first time, after local
 * midnight (the repository's `today()` has no set of a new day until it is asked, F-08-AC4), and when another clip
 * arrives while no set of the day exists. "Start" never waits for it (the feed asks too).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class HomeViewModel @Inject constructor(
  private val computer: ComputerStatusRepository,
  private val sync: SyncController,
  private val requests: RequestRepository,
  private val clips: ClipRepository,
  private val content: ClipContentRepository,
  private val shorts: ShortRepository,
  private val occurrences: OccurrenceRepository,
  private val reference: ReferenceRepository,
  private val sets: SetRepository,
  private val progress: ProgressRepository,
  private val practice: PracticeRepository,
  private val saved: SavedRepository,
  private val storage: StorageRepository,
  insights: Optional<InsightsAvailability>,
  private val clock: Clock,
) : ViewModel() {
  private val attempts = MutableStateFlow(0)
  private val asking = MutableStateFlow(Asking())
  private val refreshing = MutableStateFlow(false)
  private val notice = MutableStateFlow<HomeNotice?>(null)
  private val posterCache = mutableMapOf<String, File?>()
  private val busy = mutableSetOf<String>()
  private var noticeJob: Job? = null
  private var askJob: Job? = null

  /** The last reason Home saw to ask for today's set (null: none), kept while Home is away so a return asks no more. */
  private var lastAsk: TodayAsk? = null

  /**
   * The whole catalogue (one subscription). [Read.Loading] is only the first state and that of "Try again": when Home
   * comes back after a longer absence the last list stays until the new one arrives, so the page keeps its height and
   * its scroll position (F-03-AC8).
   */
  private val catalogue: StateFlow<Read> = attempts.flatMapLatest { attempt ->
    val read = clips.clips(ClipQuery())
      .map<List<ClipSummary>, Read> { Read.Clips(it) }
      .catch { if (it is CancellationException) throw it else emit(Read.Failed) }
    if (attempt == 0) read else read.onStart { emit(Read.Loading) }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Read.Loading)

  private val catalogueState: Flow<HomeCatalogue> = catalogue.map { read ->
    when (read) {
      Read.Loading -> HomeCatalogue.Loading
      Read.Failed -> HomeCatalogue.Failed
      is Read.Clips -> HomeCatalogue.Ready(read.clips.size, HomeRules.onPhone(read.clips))
    }
  }

  private val connection = combine(
    computer.status,
    computer.paired,
    computer.backgroundRestricted,
    computer.diagnostics.map { it.lastProbeAt }.distinctUntilChanged(),
    sync.state,
  ) { status, paired, restricted, checkedAt, syncState -> Connection(status, paired != null, paired?.capabilities, restricted, checkedAt, syncState) }

  private val queue = combine(
    requests.counts().catch { emit(RequestCounts(0, 0, 0, 0)) },
    requests.requests().catch { emit(emptyList()) },
  ) { counts, all -> Queue(counts, HomeRules.attention(all), HomeRules.analyzing(all)?.progress) }

  private val storageFull: Flow<Boolean> = storage.usage
    .map { it.budgetReached || it.belowFloor }
    .catch { emit(false) }
    .onStart { emit(false) }
    .distinctUntilChanged()

  /** The local day, read again every [TICK_MS] while Home is shown, so a local midnight passed on screen counts. */
  private val day: Flow<LocalDate> = ticks().map { it.atZone(clock.zone()).toLocalDate() }.distinctUntilChanged()

  /**
   * Today's set card. Each new reason of [HomeRules.todayAsk] while Home is shown asks the repository once (the first
   * clip on this phone, a new local day, another clip while no set of the day exists). Until that answer, and the set
   * it made, show, the card says "Preparing", never "You've seen every short" (F-08-AC4). The last card stays while
   * Home is away, so a return does not flash another one before the repository answers (F-03-AC8).
   */
  private val today: StateFlow<TodayCard> = combine(
    sets.today().catch { emit(null) },
    catalogueState,
    day,
    asking,
    reference.reference,
  ) { set: StudySet?, catalogue, date, making, ref ->
    val reason = HomeRules.todayAsk(set, catalogue, date)
    val askNow = reason?.takeIf { it != lastAsk }
    lastAsk = reason
    askNow?.let(::ask)
    val preparing = askNow != null || making.inFlight > 0 || (set == null && reason != null && reason == making.made)
    HomeRules.todayCard(set, catalogue, preparing, ref)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayCard.Preparing)

  private val keepWatching: Flow<KeepWatchingCard?> = progress.keepWatching()
    .catch { emit(null) }
    .onStart { emit(null) }
    .distinctUntilChanged()
    .flatMapLatest { keep -> if (keep == null) flowOf(null) else keepCard(keep) }

  private val newOnPhone: Flow<List<NewClip>> = clips.newOnThisPhone()
    .catch { emit(emptyList()) }
    .onStart { emit(emptyList()) }
    .map { list -> list.take(HomeRules.NEW_CARDS).map { NewClip(it, it.shortsTotal) } }

  private val shelf: Flow<List<ClipCardState>> = catalogue
    .map { read -> (read as? Read.Clips)?.clips?.let(HomeRules::shelfCandidates).orEmpty() }
    .distinctUntilChanged()
    .flatMapLatest { candidates -> shelfCards(candidates) }

  private val changes: Flow<List<ChangeGroup>> = combine(
    occurrences.changeCounts().catch { emit(emptyMap()) }.onStart { emit(emptyMap()) },
    reference.reference,
  ) { counts, ref -> HomeRules.changeGroups(ref, counts) }

  private val practiceCard: Flow<PracticeCard> = combine(
    practice.accuracy().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
    saved.saved().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
    reference.reference,
  ) { accuracy, items, ref -> PracticeCard(HomeRules.weakSpot(accuracy, ref), HomeRules.savedShorts(items)) }

  private val teaser: Flow<String?> = insights.map { it.teaser.catch { emit(null) }.onStart { emit(null) } }.orElse(flowOf(null))

  private val study = combine(today, keepWatching, newOnPhone, shelf) { t, k, n, s -> Study(t, k, n, s) }
  private val learn = combine(changes, practiceCard, teaser) { c, p, i -> Learning(c, p, i) }
  private val transient = combine(refreshing, notice, ticks()) { r, n, now -> Triple(r, n, now) }

  val state: StateFlow<HomeUiState> = combine(
    combine(connection, queue, storageFull) { c, q, s -> Triple(c, q, s) },
    catalogue.map { (it as? Read.Clips)?.clips },
    catalogueState,
    combine(study, learn) { s, l -> s to l },
    transient,
  ) { (link, queue, full), list, catalogueState, (study, learn), (refreshing, notice, now) ->
    HomeUiState(
      status = link.status,
      paired = link.paired,
      capabilities = link.capabilities,
      sync = link.sync,
      counts = queue.counts,
      attention = queue.attention,
      extras = ConnectionCardExtras(
        now = now,
        checkedAt = link.checkedAt,
        analyzing = queue.analyzing,
        clipsOnPhone = list?.let(HomeRules::onPhone),
        clipsTotal = list?.let(HomeRules::wanted),
        storageNearlyFull = full,
        backgroundRestricted = link.restricted,
      ),
      catalogue = catalogueState,
      today = study.today,
      keepWatching = study.keep,
      newOnPhone = study.new,
      shelf = study.shelf,
      changes = learn.changes,
      practice = learn.practice,
      insights = learn.insights,
      refreshing = refreshing,
      notice = notice,
      now = now,
      zone = clock.zone(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

  /** The clock, read every [TICK_MS] while Home is shown: "Checked 10 s ago", "last seen" and the day stay true. */
  private fun ticks(): Flow<Instant> = flow {
    while (true) {
      emit(clock.now())
      delay(TICK_MS)
    }
  }

  /**
   * Asks the repository for today's set for [reason], one ask after the other. A set it made is remembered with its
   * reason, so the card keeps saying "Preparing" until the repository shows that set.
   */
  private fun ask(reason: TodayAsk) {
    asking.update { it.copy(inFlight = it.inFlight + 1) }
    val previous = askJob
    askJob = viewModelScope.launch {
      var made = false
      try {
        previous?.join()
        made = sets.ensureToday() != null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failed: Exception) {
        // The card then says what is true without a set; the feed tries again when it opens.
      } finally {
        asking.update { Asking(inFlight = it.inFlight - 1, made = reason.takeIf { made }) }
      }
    }
  }

  /** "Sync now" on the status card. */
  fun syncNow() = sync.syncNow(SyncReason.SYNC_NOW)

  /**
   * Pull to refresh (F-03): "Sync now" when connected; otherwise "Your computer isn't reachable right now." and
   * nothing else changes. The indicator stays while the pass runs (at most [REFRESH_MAX_MS]).
   */
  fun refresh() {
    if (computer.status.value !is ComputerStatus.Connected) {
      showNotice(HomeNotice.NOT_REACHABLE)
      return
    }
    if (refreshing.value) return
    refreshing.value = true
    sync.syncNow(SyncReason.PULL_TO_REFRESH)
    viewModelScope.launch {
      try {
        delay(REFRESH_MIN_MS)
        withTimeoutOrNull(REFRESH_MAX_MS) { sync.state.first { it !is SyncState.Active } }
      } finally {
        refreshing.value = false
      }
    }
  }

  fun dismissNotice() {
    noticeJob?.cancel()
    notice.value = null
  }

  private fun showNotice(kind: HomeNotice) {
    noticeJob?.cancel()
    notice.value = kind
    noticeJob = viewModelScope.launch {
      delay(NOTICE_MS)
      notice.value = null
    }
  }

  /** "Try again" after the catalogue could not be read. */
  fun retry() {
    attempts.value++
  }

  /** The status card's inline action, when it is carried out here (HomeRules.route). */
  fun perform(id: RequestId, action: RequestAction) = once("request:${id.value}:${action.name}") { requests.perform(id, action) }

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

  /** The Keep watching card: "Short 4 of 15" and the spotlight's change and words from the clip's shorts. */
  private fun keepCard(keep: KeepWatching): Flow<KeepWatchingCard?> = combine(
    shorts.shorts(keep.clip.id).catch { emit(emptyList()) }.onStart { emit(emptyList()) },
    reference.reference,
  ) { list, ref -> list to ref }.mapLatest { (list, ref) ->
    val short = list.firstOrNull { it.id == keep.short }
    val spotlight = short?.spotlight
    KeepWatchingCard(
      clip = ClipCardState(keep.clip, poster = poster(keep.clip)),
      short = keep.short,
      ordinal = short?.ordinal,
      total = short?.ofTotal ?: keep.clip.shortsTotal.takeIf { it > 0 },
      changeName = spotlight?.let { HomeRules.name(ref, it.change) },
      family = spotlight?.let { ref.familyFor(it.change) },
      words = spotlight?.let { spot -> spotlightWords(keep.clip.id, spot.word, spot.crossesBoundary) },
    )
  }

  private suspend fun spotlightWords(id: JobId, word: WordRef, crosses: Boolean): String? = try {
    val data = content.clipData(id)
    val text = data.word(word)?.text ?: return null
    val next = if (crosses) data.nextWord(word)?.let { data.word(it)?.text } else null
    listOfNotNull(text, next).joinToString(" ")
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unreadable: Exception) {
    null
  }

  /** The shelf: the candidates' details give the last time each was watched and their changes per family. */
  private fun shelfCards(candidates: List<ClipSummary>): Flow<List<ClipCardState>> {
    if (candidates.isEmpty()) return flowOf(emptyList())
    val details = candidates.map { clip ->
      clips.clip(clip.id).catch { emit(null) }.onStart { emit(null) }.map { detail -> clip to detail }
    }
    return combine(combine(details) { it.toList() }, reference.reference) { pairs, ref -> pairs to ref }
      .mapLatest { (pairs, ref) ->
        val ranked = pairs.map { (clip, detail) ->
          val summary = detail?.summary ?: clip
          (summary to detail) to HomeRules.lastActivity(summary, detail?.lastWatched)
        }
        HomeRules.shelf(ranked).map { (summary, detail) ->
          val families = detail?.changeCounts.orEmpty().entries
            .groupBy({ ref.familyFor(it.key) }, { it.value })
            .map { (family, counts) -> FamilyCount(family, counts.sum()) }
            .sortedBy { it.family.ordinal }
          ClipCardState(summary, poster = poster(summary), families = families)
        }
      }
      .distinctUntilChanged()
  }

  /** The local poster of [clip] (`ClipMedia.poster`), looked up once per clip and availability. */
  private suspend fun poster(clip: ClipSummary): File? {
    val key = "${clip.id.value}|${clip.availability}|${clip.video}"
    if (posterCache.containsKey(key)) return posterCache[key]
    val found = try {
      content.media(clip.id)?.poster
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unreadable: Exception) {
      null
    }
    posterCache[key] = found
    return found
  }

  /** What reading the catalogue gave. */
  private sealed interface Read {
    data object Loading : Read

    data object Failed : Read

    data class Clips(val clips: List<ClipSummary>) : Read
  }

  private data class Connection(
    val status: ComputerStatus,
    val paired: Boolean,
    val capabilities: Capabilities?,
    val restricted: Boolean,
    val checkedAt: Instant?,
    val sync: SyncState,
  )

  private data class Queue(
    val counts: RequestCounts,
    val attention: Request?,
    val analyzing: RequestProgress?,
  )

  /** Asks for today's set in flight, and the reason of the last ask when it made a set. */
  private data class Asking(val inFlight: Int = 0, val made: TodayAsk? = null)

  private data class Study(val today: TodayCard, val keep: KeepWatchingCard?, val new: List<NewClip>, val shelf: List<ClipCardState>)

  private data class Learning(val changes: List<ChangeGroup>, val practice: PracticeCard, val insights: String?)

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
    const val REFRESH_MIN_MS = 600L
    const val REFRESH_MAX_MS = 10_000L
    const val NOTICE_MS = 4_000L
    const val TICK_MS = 30_000L
  }
}
