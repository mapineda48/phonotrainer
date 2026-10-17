package com.mapineda48.phonotrainer.feature.watch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipLease
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPosition
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.extension.PhraseDetailsEntry
import com.mapineda48.phonotrainer.core.ui.extension.RecordSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The feed (spec F-06 to F-09, architecture 10 and 11): resolves the mode and start of its [WatchKey], builds the pages
 * (shorts, the set's questions, the end card), drives the player pool (one per ViewModel, released in [onCleared],
 * stopped while Watch is not the visible tab), loads each card's clip data, counts seen time, keeps the position and
 * holds a lease on the clip on screen. Mode changes from the picker navigate to a new `WatchKey` (the shell makes it
 * the Watch tab's root), so one ViewModel plays one mode.
 */
@HiltViewModel(assistedFactory = WatchViewModel.Factory::class)
internal class WatchViewModel @AssistedInject constructor(
  @Assisted private val key: WatchKey,
  private val savedState: SavedStateHandle,
  private val player: FeedPlayer,
  private val spanPlayer: SpanPlayer,
  private val shadowing: ShadowingRunner,
  private val shorts: ShortRepository,
  private val content: ClipContentRepository,
  private val occurrences: OccurrenceRepository,
  private val clips: ClipRepository,
  private val sets: SetRepository,
  private val progress: ProgressRepository,
  private val saved: SavedRepository,
  private val settingsRepository: SettingsRepository,
  private val coachMarks: CoachMarkRepository,
  private val reference: ReferenceRepository,
  private val didactic: DidacticContent,
  private val computer: ComputerStatusRepository,
  private val sync: SyncController,
  private val clock: Clock,
  @DefaultDispatcher private val cpu: CoroutineDispatcher,
  phraseDetails: Optional<PhraseDetailsEntry>,
  recordSection: Optional<RecordSection>,
) : ViewModel() {
  @AssistedFactory
  interface Factory {
    fun create(key: WatchKey): WatchViewModel
  }

  private val feed = MutableStateFlow(FeedData())
  private val contents = MutableStateFlow(emptyMap<ShortId, ShortContent>())
  private val failed = MutableStateFlow(emptySet<JobId>())
  private val revealed = MutableStateFlow(emptySet<Int>())
  private val scroll = MutableStateFlow<ScrollRequest?>(null)
  private val awaiting = MutableStateFlow(false)
  private val titles = MutableStateFlow(emptyMap<JobId, String>())
  private val pickerClips = MutableStateFlow(emptyList<PickerClip>())
  private val seeks = MutableStateFlow(0)

  private var talkBackOn = false
  private var visible = true
  private var settledPage = -1
  private var visit: Visit? = null
  private var lease: ClipLease? = null
  private val opened = HashSet<JobId>()
  private val watchedClips = HashSet<JobId>()
  private var shadowJob: Job? = null
  private var scrollIds = 0L
  private var generation = 0

  /** Spotlights a set forces on its shorts (a saved short's saved word, F-07 rule 1). */
  private var spotlights: Map<ShortId, Spotlight?> = emptyMap()
  private val watchedQueueClips = HashSet<JobId>()
  private val goneClips = HashSet<JobId>()

  /** WP-30's "Record yourself" when its module binds it (work-plan 4.4); null in v1-core. */
  val recordSection: RecordSection? = recordSection.orElse(null)
  private val canRecord = this.recordSection != null
  private val canPhraseDetails = phraseDetails.map { it.available() }.orElse(false)

  private val savedShorts: StateFlow<Map<ShortId, Long>> = saved.saved()
    .map { items -> items.filter { it.kind == SavedKind.SHORT }.associate { it.short to it.id } }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  private val offline: Flow<Boolean> = combine(computer.status, computer.paired) { status, paired ->
    paired != null && status !is ComputerStatus.Connected
  }

  private val coach: Flow<WatchCoachMark?> = combine(WatchCoachMark.entries.map { mark -> coachMarks.shouldShow(mark.id) }) { shows ->
    WatchCoachMark.entries.firstOrNull { shows[it.ordinal] }
  }

  private val picker: Flow<PickerState> = combine(
    sets.today(),
    saved.saved(),
    occurrences.changeCounts(),
    reference.reference,
    pickerClips,
  ) { today, savedItems, counts, ref, clipRows ->
    val shortsOfSet = today?.items?.filterIsInstance<StudySetItem.Short>()?.filterNot { it.dropped }
    val labels = ref.labels.keys.toList()
    val order = ref.families.map { it.family } + listOf(Family.LEXICAL, Family.OTHER)
    val families = labels
      .groupBy { ref.familyFor(it) }
      .toList()
      .sortedBy { (family, _) -> order.indexOf(family).let { if (it < 0) order.size else it } }
      .map { (family, members) ->
        val inOrder = ref.families.firstOrNull { it.family == family }?.members
        val sorted = if (inOrder == null) members else members.sortedBy { inOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        PickerFamily(family, sorted.map { PickerChange(it, FeedLogic.changeName(ref, it), counts[it]?.shorts ?: 0) })
      }
    PickerState(
      today = shortsOfSet?.let { list -> list.count { it.done } to list.size },
      savedCount = savedItems.size,
      families = families,
      clips = clipRows,
    )
  }

  private val onTheWay: Flow<Int> = clips.clips(ClipQuery()).map { list ->
    list.count { it.availability is ClipAvailability.Bringing || it.availability == ClipAvailability.GettingReadyOnComputer }
  }

  val state: StateFlow<WatchUiState> = combine(
    combine(feed, contents, failed, revealed, scroll) { f, c, x, r, s -> Base(f, c, x, r, s) },
    combine(player.state, settingsRepository.settings, savedShorts, offline, coach) { p, set, sv, off, cm ->
      Live(p, set, sv.keys, off, cm)
    },
    combine(shadowing.state, awaiting, picker, onTheWay, titles) { sh, aw, pk, otw, t -> Extra(sh, aw, pk, otw, t) },
    seeks,
  ) { base, live, extra, seekTick ->
    val f = base.feed
    WatchUiState(
      loading = f.loading,
      empty = f.empty,
      mode = f.mode?.name?.withTitle(extra.titles),
      pages = f.pages,
      current = f.current,
      generation = f.generation,
      contents = base.contents.mapValues { (id, c) -> if (c.clipTitle == null) c.copy(clipTitle = extra.titles[id.jobId]) else c },
      failed = base.failed,
      saved = live.saved,
      offline = live.offline,
      playback = live.playback,
      seekTick = seekTick,
      settings = live.settings,
      revealed = base.revealed,
      coachMark = live.coach.takeIf { f.pages.getOrNull(f.current) is FeedPage.Short },
      summary = f.summary,
      clipEnd = f.clipEnd?.let { end -> end.copy(title = end.title ?: f.clipEndJob?.let { extra.titles[it] }) },
      shortSet = f.shortSet,
      clipsOnTheWay = extra.onTheWay,
      shadowing = extra.shadowing,
      scroll = base.scroll,
      awaitingSound = extra.awaiting,
      canRecord = canRecord,
      canPhraseDetails = canPhraseDetails,
      picker = extra.picker,
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, WatchUiState())

  init {
    player.setSpeed(settingsRepository.settings.value.defaultSpeed)
    player.setTrack(settingsRepository.settings.value.track)
    viewModelScope.launch { resolve() }
    viewModelScope.launch { watchEnds() }
    viewModelScope.launch { watchSet() }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Resolving the mode and building the pages

  private suspend fun resolve() {
    val start = key.start
    val restored = restoredShort()
    when (start) {
      null, FeedStart.Resume -> {
        val last = progress.lastFeed().first()
        when (val mode = last?.mode) {
          null, is FeedMode.Mix -> loadMix(restored ?: last?.short)
          is FeedMode.InOrder -> loadSequence(mode, restored ?: last.short)
          is FeedMode.Change -> loadSequence(mode, restored ?: last.short)
          FeedMode.Saved -> loadSequence(mode, restored ?: last.short)
        }
      }
      FeedStart.TodaySet -> loadMix(restored)
      is FeedStart.InOrder -> {
        val mode = FeedMode.InOrder(start.jobId)
        loadSequence(mode, restored, inOrder = start)
      }
      is FeedStart.ChangeMode -> loadSequence(FeedMode.Change(start.label, start.onlyClip), restored)
      FeedStart.SavedMode -> loadSequence(FeedMode.Saved, restored)
      is FeedStart.AtWord -> {
        val mode = FeedMode.InOrder(start.jobId)
        loadSequence(mode, restored, inOrder = FeedStart.InOrder(start.jobId, start.segment, fromStart = false))
      }
    }
  }

  /** The lesson [FeedStart.AtWord] asks for, once per entry (a recreation never opens it again). */
  fun pendingLesson(): WordRef? {
    val start = key.start as? FeedStart.AtWord ?: return null
    if (!start.openLesson || savedState.get<Boolean>(KEY_LESSON_OPENED) == true) return null
    savedState[KEY_LESSON_OPENED] = true
    return WordRef(start.jobId, start.segment, start.word)
  }

  private fun restoredShort(): ShortId? {
    val job = savedState.get<String>(KEY_SHORT_JOB) ?: return null
    val segment = savedState.get<Int>(KEY_SHORT_SEGMENT) ?: return null
    return ShortId(JobId(job), segment)
  }

  private suspend fun loadSequence(mode: FeedMode, at: ShortId?, inOrder: FeedStart.InOrder? = null) {
    val sequence = shorts.sequence(mode)
    val items = withMedia(sequence.items.map { it to null })
    val ref = reference.reference.value
    val name = when (mode) {
      is FeedMode.InOrder -> ModeName.InOrder(null)
      is FeedMode.Change -> ModeName.Change(mode.label, FeedLogic.changeName(ref, mode.label), ref.familyFor(mode.label))
      FeedMode.Saved -> ModeName.Saved
      is FeedMode.Mix -> ModeName.Mix(today = true)
    }
    val resolved = ResolvedMode(mode, name)
    if (items.isEmpty()) {
      val empty = when (mode) {
        is FeedMode.Change -> FeedEmpty.NoShortsWithChange(FeedLogic.changeName(ref, mode.label))
        FeedMode.Saved -> FeedEmpty.NothingSaved
        else -> nothingPlayable() ?: FeedEmpty.ClipNotHere
      }
      feed.value = fresh(FeedData(loading = false, mode = resolved, empty = empty))
      if (mode is FeedMode.InOrder) watchTitle(mode.jobId)
      return
    }
    val infos = items.map { it.short }
    val startIndex = when {
      at != null -> infos.indexOfFirst { it.id == at }.takeIf { it >= 0 } ?: FeedLogic.indexContaining(infos, at.firstSegment)
      mode is FeedMode.InOrder -> {
        val resume = if (inOrder?.firstSegment == null && inOrder?.fromStart != true) progress.resume(mode.jobId).first() else null
        FeedLogic.inOrderStart(infos, inOrder?.firstSegment, inOrder?.fromStart == true, resume)
      }
      else -> sequence.startIndex.coerceIn(0, infos.lastIndex)
    }
    val pages = items.mapIndexed { index, item -> FeedPage.Short(index, item) } + FeedPage.End(endKindOf(mode))
    val clipEnd = (mode as? FeedMode.InOrder)?.let { inOrderEnd(it.jobId, items.size) }
    feed.value = fresh(FeedData(
      loading = false,
      mode = resolved,
      pages = pages,
      current = startIndex,
      clipEnd = clipEnd,
      clipEndJob = (mode as? FeedMode.InOrder)?.jobId,
    ))
    player.setQueue(items, startIndex)
    if (mode is FeedMode.InOrder) watchTitle(mode.jobId)
    watchQueue(items)
  }

  private fun endKindOf(mode: FeedMode): EndKind = when (mode) {
    is FeedMode.InOrder -> EndKind.CLIP_END
    is FeedMode.Change -> EndKind.CHANGE_END
    FeedMode.Saved -> EndKind.SAVED_END
    is FeedMode.Mix -> EndKind.SET_SUMMARY
  }

  private suspend fun inOrderEnd(jobId: JobId, count: Int): ClipEnd {
    val kinds = try {
      FeedLogic.kindsOfChange(content.clipData(jobId))
    } catch (_: ClipDataException) {
      0
    }
    val onPhone = clips.clips(ClipQuery(filter = ClipFilter.ON_THIS_PHONE)).first()
    val at = onPhone.indexOfFirst { it.id == jobId }
    val next = if (at >= 0) onPhone.getOrNull(at + 1) else null
    return ClipEnd(onPhone.getOrNull(at)?.title, count, kinds, next?.let { NextClip(it.id, it.title) })
  }

  /** The empty state when nothing at all is playable on this phone, else null. */
  private suspend fun nothingPlayable(): FeedEmpty? {
    if (occurrences.totals().first().shorts > 0) return null
    if (computer.paired.value == null) return FeedEmpty.FirstRun
    return FeedEmpty.ClipsComing((sync.state.value as? SyncState.Active)?.percent)
  }

  private suspend fun loadMix(at: ShortId?, another: StudySet? = null) {
    val set = another ?: sets.ensureToday()
    if (set == null) {
      val empty = nothingPlayable()
      feed.value = if (empty != null) {
        fresh(FeedData(loading = false, mode = ResolvedMode(FeedMode.Mix(0), ModeName.Mix(today = true)), empty = empty))
      } else {
        fresh(FeedData(
          loading = false,
          mode = ResolvedMode(FeedMode.Mix(0), ModeName.Mix(today = true)),
          pages = listOf(FeedPage.End(EndKind.NOTHING_LEFT)),
        ))
      }
      return
    }
    showSet(set, at)
  }

  private suspend fun showSet(set: StudySet, at: ShortId?) {
    val settings = settingsRepository.settings.value
    val shortItems = set.items.withIndex().filter { (_, item) -> item is StudySetItem.Short && !item.dropped }
    val media = withMedia(shortItems.map { (_, item) -> (item as StudySetItem.Short).short to item.spotlight })
    val byId = media.associateBy { it.short.id }
    val pages = ArrayList<FeedPage>()
    var queueIndex = 0
    val queue = ArrayList<FeedItem>()
    val spotlights = HashMap<ShortId, Spotlight?>()
    for ((position, item) in shortItems) {
      item as StudySetItem.Short
      val feedItem = byId[item.short.id] ?: continue
      pages += FeedPage.Short(queueIndex++, feedItem, setPosition = position, savedTag = item.savedTag)
      queue += feedItem
      spotlights[item.short.id] = item.spotlight
    }
    if (settings.questionsAfterSet) {
      val questions = set.items.withIndex().filter { it.value is StudySetItem.QuestionItem }
      questions.forEachIndexed { n, (position, item) ->
        item as StudySetItem.QuestionItem
        pages += FeedPage.QuestionPage(position, n + 1, questions.size, item.question, item.choice)
      }
    }
    pages += FeedPage.End(EndKind.SET_SUMMARY)
    val cursorPage = when {
      at != null -> pages.indexOfFirst { it is FeedPage.Short && it.id == at }.takeIf { it >= 0 }
      set.completedAt != null -> pages.lastIndex
      else -> null
    } ?: pages.indexOfFirst { page ->
      when (page) {
        is FeedPage.Short -> (page.setPosition ?: 0) >= set.cursor
        is FeedPage.QuestionPage -> page.setPosition >= set.cursor
        is FeedPage.End -> true
      }
    }.coerceAtLeast(0)
    this.spotlights = spotlights
    feed.value = fresh(FeedData(
      loading = false,
      mode = ResolvedMode(FeedMode.Mix(set.id), ModeName.Mix(today = set.kind == StudySetKind.TODAY)),
      pages = pages,
      current = cursorPage,
      set = set,
      summary = FeedLogic.setSummary(set, reference.reference.value, settings.questionsAfterSet),
      shortSet = queue.size.takeIf { it < FULL_SET },
    ))
    if (queue.isNotEmpty()) {
      val startQueue = (pages.getOrNull(cursorPage) as? FeedPage.Short)?.queueIndex ?: queue.lastIndex
      player.setQueue(queue, startQueue)
      watchQueue(queue)
    }
  }

  /** [items] paired with their clip's local media; shorts whose clip has no audio on the phone are left out. */
  private suspend fun withMedia(items: List<Pair<ShortInfo, Spotlight?>>): List<FeedItem> {
    val media = HashMap<JobId, ClipMedia?>()
    return items.mapNotNull { (short, _) ->
      val m = media.getOrPut(short.id.jobId) { content.media(short.id.jobId) } ?: return@mapNotNull null
      FeedItem(short, m)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The pager

  /** The screen reports the page the pager settled on (also the first page, once laid out). */
  fun onPageSettled(page: Int) {
    val data = feed.value
    if (page !in data.pages.indices) return
    if (page == settledPage && data.current == page) return
    leaveCurrent()
    settledPage = page
    dropGoneClips()
    val now = feed.value
    val target = now.pages.getOrNull(page) ?: return
    feed.update { it.copy(current = page) }
    when (target) {
      is FeedPage.Short -> enterShort(target, page)
      is FeedPage.QuestionPage -> {
        player.pause()
        stopShadowing()
        awaiting.value = false
        now.set?.let { set -> viewModelScope.launch { sets.advance(set.id, target.setPosition) } }
      }
      is FeedPage.End -> {
        player.pause()
        stopShadowing()
        awaiting.value = false
        now.set?.let { set -> viewModelScope.launch { sets.advance(set.id, set.items.size) } }
      }
    }
  }

  private fun enterShort(page: FeedPage.Short, pageIndex: Int) {
    val autoplay = settingsRepository.settings.value.playEachShort && !talkBackOn && visible
    player.settle(page.queueIndex, autoplay)
    awaiting.value = autoplay
    revealed.value = emptySet()
    val id = page.id
    savedState[KEY_SHORT_JOB] = id.jobId.value
    savedState[KEY_SHORT_SEGMENT] = id.firstSegment
    holdLease(id.jobId)
    if (opened.add(id.jobId)) viewModelScope.launch { progress.markOpened(id.jobId) }
    feed.value.set?.let { set ->
      val position = page.setPosition
      if (position != null && position > set.cursor) viewModelScope.launch { sets.advance(set.id, position) }
    }
    loadAround(pageIndex)
    refreshNeighbours(pageIndex)
    startVisit(page)
    savePosition(page, page.info.playStartMs)
  }

  private fun leaveCurrent() {
    val v = visit ?: return
    visit = null
    v.job?.cancel()
    if (!v.reported && v.tracker.playedMs > 0) {
      viewModelScope.launch { progress.recordPlayed(v.id, v.tracker.playedMs, v.lengthMs) }
    }
    savePosition(v.page, player.positionMs())
  }

  private fun startVisit(page: FeedPage.Short) {
    val length = (page.info.playEndMs - page.info.playStartMs).coerceAtLeast(1)
    val tracker = SeenTracker(length).apply { seeked(page.info.playStartMs) }
    val v = Visit(page, page.id, length, tracker)
    visit = v
    v.job = viewModelScope.launch {
      while (isActive) {
        delay(SeenTracker.PERIOD_MS)
        val state = player.state.value
        val position = player.positionMs()
        tracker.sample(position, state.playing)
        if (state.playing) awaiting.value = false
        if (tracker.seen && !v.reported) {
          v.reported = true
          progress.recordPlayed(page.id, tracker.playedMs, length)
        }
        revealPlayed(page.id, position)
      }
    }
  }

  /** Listen first (F-07 item 5): a phrase's words appear once it has played through. */
  private fun revealPlayed(id: ShortId, positionMs: Long) {
    if (!settingsRepository.settings.value.listenFirst) return
    val phrases = contents.value[id]?.phrases ?: return
    val done = phrases.filter { positionMs >= it.endMs }.map { it.segment }
    if (done.isNotEmpty() && !revealed.value.containsAll(done)) revealed.update { it + done }
  }

  private fun holdLease(id: JobId) {
    if (lease?.id == id) return
    lease?.close()
    lease = content.open(id)
  }

  private fun savePosition(page: FeedPage.Short, positionMs: Long) {
    val mode = feed.value.mode?.mode ?: return
    viewModelScope.launch {
      progress.saveFeedPosition(FeedPosition(mode, page.id, positionMs, clock.now()))
    }
  }

  /** The cards of [page] and its neighbours get their clip data (parsed and cached by the corpus). */
  private fun loadAround(page: Int) {
    val pages = feed.value.pages
    for (i in (page - 1)..(page + 1)) {
      val short = pages.getOrNull(i) as? FeedPage.Short ?: continue
      if (short.id in contents.value || short.id.jobId in failed.value) continue
      viewModelScope.launch { loadContent(short) }
    }
  }

  private suspend fun loadContent(page: FeedPage.Short) {
    val jobId = page.id.jobId
    val clip = try {
      content.clipData(jobId)
    } catch (error: ClipDataException) {
      failed.update { it + jobId }
      return
    }
    val ref = reference.reference.value
    val spotlight = spotlights[page.id] ?: forcedSpotlight(page.info)
    val built = withContext(cpu) {
      FeedLogic.content(page.info, spotlight, clip, ref, clipTitle = null) { label -> didactic.lesson(label)?.listenFor }
    }
    contents.update { it + (page.id to built) }
    watchTitle(jobId)
  }

  /** A change mode forces its change on the spotlight (F-07 rule 2): the sequence already gives it. */
  private fun forcedSpotlight(short: ShortInfo): Spotlight? = short.spotlight

  private fun watchTitle(jobId: JobId) {
    if (!watchedClips.add(jobId)) return
    viewModelScope.launch {
      clips.clip(jobId).map { it?.summary?.title }.distinctUntilChanged().collect { title ->
        if (title != null) titles.update { it + (jobId to title) }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Fresh media (architecture 11.1, F-06-AC13) and clips that leave the phone

  /** For each clip of the queue: re-issue the queue when its artifacts change, and drop it when its shorts go. */
  private fun watchQueue(items: List<FeedItem>) {
    for (jobId in items.map { it.short.id.jobId }.distinct()) {
      if (!watchedQueueClips.add(jobId)) continue
      viewModelScope.launch {
        clips.clip(jobId).map { it?.artifacts }.distinctUntilChanged().drop(1).collect { refreshMedia(jobId) }
      }
      viewModelScope.launch {
        var hadShorts = false
        shorts.shorts(jobId).collect { list ->
          if (list.isNotEmpty()) hadShorts = true else if (hadShorts) goneClips += jobId
        }
      }
    }
  }

  private fun refreshNeighbours(page: Int) {
    val pages = feed.value.pages
    val neighbours = listOf(page - 1, page + 1).mapNotNull { pages.getOrNull(it) as? FeedPage.Short }
    for (jobId in neighbours.map { it.id.jobId }.distinct()) viewModelScope.launch { refreshMedia(jobId) }
  }

  /** Re-reads [jobId]'s local media and, when it changed, gives the pool the same queue with the fresh media. */
  private suspend fun refreshMedia(jobId: JobId) {
    val fresh = content.media(jobId) ?: return
    val data = feed.value
    var changed = false
    val pages = data.pages.map { page ->
      if (page is FeedPage.Short && page.id.jobId == jobId && page.media != fresh) {
        changed = true
        page.copy(item = page.item.copy(media = fresh))
      } else {
        page
      }
    }
    if (!changed) return
    feed.value = data.copy(pages = pages)
    val current = (pages.getOrNull(data.current) as? FeedPage.Short)?.queueIndex ?: 0
    player.setQueue(pages.filterIsInstance<FeedPage.Short>().map { it.item }, current)
  }

  /** Shorts of clips deleted from the phone go at the next swipe (spec F-06 States, F-08 item 3). */
  private fun dropGoneClips() {
    val set = feed.value.set
    val droppedInSet = set?.items?.withIndex()
      ?.filter { (_, item) -> item is StudySetItem.Short && item.dropped }
      ?.map { it.index }?.toSet().orEmpty()
    if (goneClips.isEmpty() && droppedInSet.isEmpty()) return
    val data = feed.value
    val keep = data.pages.filter { page ->
      page !is FeedPage.Short || (page.id.jobId !in goneClips && page.setPosition !in droppedInSet)
    }
    if (keep.size == data.pages.size) return
    var queue = 0
    val renumbered = keep.map { page -> if (page is FeedPage.Short) page.copy(queueIndex = queue++) else page }
    val currentKey = data.pages.getOrNull(settledPage)?.key
    val newIndex = renumbered.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 }
      ?: settledPage.coerceAtMost(renumbered.lastIndex)
    feed.value = data.copy(pages = renumbered, current = newIndex)
    val items = renumbered.filterIsInstance<FeedPage.Short>().map { it.item }
    player.setQueue(items, (renumbered.getOrNull(newIndex) as? FeedPage.Short)?.queueIndex ?: 0)
    settledPage = newIndex
    requestScroll(newIndex)
  }

  /** The set's flow: answers and dropped shorts (a clip deleted during the day) reach the pages. */
  private suspend fun watchSet() {
    sets.today().collect { today ->
      val data = feed.value
      val shown = data.set ?: return@collect
      if (today == null || today.id != shown.id) return@collect
      val questionsOn = settingsRepository.settings.value.questionsAfterSet
      feed.value = data.copy(
        set = today,
        summary = FeedLogic.setSummary(today, reference.reference.value, questionsOn),
        pages = data.pages.map { page ->
          if (page is FeedPage.QuestionPage) {
            val item = today.items.getOrNull(page.setPosition) as? StudySetItem.QuestionItem
            if (item != null && page.choice == null) page.copy(choice = item.choice) else page
          } else {
            page
          }
        },
      )
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The end of a short (F-07 item 10)

  private suspend fun watchEnds() {
    var wasEnded = false
    player.state.collect { state ->
      val ended = state.ended
      if (ended && !wasEnded) onShortEnded()
      wasEnded = ended
      if (state.playing) awaiting.value = false
    }
  }

  private fun onShortEnded() {
    val v = visit ?: return
    if (player.state.value.loop != LoopMode.OFF) return
    // Listen first: the first end shows the words; the replay is the first time they are visible.
    contents.value[v.id]?.let { c -> revealed.update { it + c.phrases.map { p -> p.segment } } }
    when (settingsRepository.settings.value.endOfShort) {
      EndOfShort.REPLAY_ONCE -> if (!v.replayed) {
        v.replayed = true
        v.tracker.seeked(v.page.info.playStartMs)
        player.seekTo(v.page.info.playStartMs)
        seeks.update { it + 1 }
        player.play()
      }
      EndOfShort.STOP -> Unit
      EndOfShort.NEXT -> {
        val next = feed.value.current + 1
        if (next < feed.value.pages.size) requestScroll(next)
      }
    }
  }

  private fun requestScroll(page: Int) {
    scroll.value = ScrollRequest(page, ++scrollIds)
  }

  /** The screen performed [request]. */
  fun scrolled(request: ScrollRequest) {
    if (scroll.value?.id == request.id) scroll.value = null
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Commands of the card

  fun setTalkBack(on: Boolean) {
    talkBackOn = on
  }

  /** Watch became (or stopped being) the visible tab (architecture 11.2): the pool releases its decoders. */
  fun setVisible(visible: Boolean) {
    if (this.visible == visible) return
    this.visible = visible
    if (!visible) {
      (feed.value.pages.getOrNull(settledPage) as? FeedPage.Short)?.let { savePosition(it, player.positionMs()) }
      stopShadowing()
    }
    player.setVisible(visible)
  }

  fun togglePlay() {
    val state = player.state.value
    when {
      state.playing -> player.pause()
      state.ended -> {
        val v = visit ?: return
        v.tracker.seeked(v.page.info.playStartMs)
        player.seekTo(v.page.info.playStartMs)
        seeks.update { it + 1 }
        player.play()
      }
      else -> player.play()
    }
    awaiting.value = false
  }

  fun play() {
    if (!player.state.value.playing) togglePlay()
  }

  fun pause() {
    player.pause()
    awaiting.value = false
    (feed.value.pages.getOrNull(settledPage) as? FeedPage.Short)?.let { savePosition(it, player.positionMs()) }
  }

  fun replayPhrase() {
    visit?.tracker?.seeked(player.positionMs())
    player.replayPhrase()
    seeks.update { it + 1 }
  }

  fun holdSlow(held: Boolean) {
    player.holdSlow(held)
  }

  fun cycleSpeed() {
    val next = FeedLogic.nextSpeed(player.state.value.speed)
    player.setSpeed(next)
    viewModelScope.launch { settingsRepository.update { it.copy(defaultSpeed = next) } }
  }

  /** "Slower" (TalkBack, F-07-AC11): one step down the speed cycle, never below 0.5×. */
  fun slower() {
    val current = player.state.value.speed
    val next = FeedLogic.SPEEDS.firstOrNull { it < current - 0.01f } ?: return
    player.setSpeed(next)
    viewModelScope.launch { settingsRepository.update { it.copy(defaultSpeed = next) } }
  }

  fun cycleLoop() {
    player.setLoop(FeedLogic.nextLoop(player.state.value.loop))
  }

  /** A long press on a caption phrase loops it until tapped again (F-07 gestures). */
  fun loopPhrase(segment: Int) {
    val v = visit ?: return
    val phrase = contents.value[v.id]?.phrases?.firstOrNull { it.segment == segment } ?: return
    if (player.state.value.loop == LoopMode.PHRASE) {
      player.setLoop(LoopMode.OFF)
      return
    }
    v.tracker.seeked(phrase.startMs)
    player.seekTo(phrase.startMs)
    seeks.update { it + 1 }
    player.setLoop(LoopMode.PHRASE)
    player.play()
  }

  /** The progress bar: [fraction] of the play bounds. A drag never counts as watching (F-06-AC11). */
  fun seek(fraction: Float) {
    val v = visit ?: return
    val info = v.page.info
    val target = info.playStartMs + ((info.playEndMs - info.playStartMs) * fraction.coerceIn(0f, 1f)).toLong()
    v.tracker.seeked(target)
    player.seekTo(target)
    seeks.update { it + 1 }
  }

  fun playWord() {
    val slip = currentContent()?.spotlight ?: return
    visit?.tracker?.seeked(slip.span.startMs)
    player.playSpan(slip.span)
  }

  fun playWordSlowly() {
    val slip = currentContent()?.spotlight ?: return
    val track = settingsRepository.settings.value.track
    viewModelScope.launch { spanPlayer.play(slip.span, FeedLogic.SLOW, track) }
  }

  fun toggleSave() {
    val page = feed.value.pages.getOrNull(feed.value.current) as? FeedPage.Short ?: return
    val itemId = savedShorts.value[page.id]
    viewModelScope.launch {
      if (itemId != null) {
        saved.unsave(itemId)
      } else {
        saved.saveShort(page.id, currentContent()?.spotlight?.word ?: page.info.spotlight?.word)
      }
    }
  }

  /** Before the lesson opens on a word: the short pauses where it is (F-07-AC6). */
  fun pauseForLesson() {
    pause()
  }

  fun revealWords() {
    val c = currentContent() ?: return
    revealed.update { it + c.phrases.map { p -> p.segment } }
  }

  fun setTrack(track: Track) {
    player.setTrack(track)
    viewModelScope.launch { settingsRepository.update { it.copy(track = track) } }
  }

  fun setCaptions(mode: CaptionMode) {
    viewModelScope.launch { settingsRepository.update { it.copy(captions = mode) } }
  }

  fun setListenFirst(on: Boolean) {
    viewModelScope.launch { settingsRepository.update { it.copy(listenFirst = on) } }
  }

  /** "Leave this clip out of the Mix" (reversible here and in Settings, F-06 item 3). */
  fun setExcluded(excluded: Boolean) {
    val jobId = (feed.value.pages.getOrNull(feed.value.current) as? FeedPage.Short)?.id?.jobId ?: return
    viewModelScope.launch {
      settingsRepository.update {
        it.copy(excludedFromMix = if (excluded) it.excludedFromMix + jobId else it.excludedFromMix - jobId)
      }
    }
  }

  fun downloadVideo() {
    val jobId = (feed.value.pages.getOrNull(feed.value.current) as? FeedPage.Short)?.id?.jobId ?: return
    viewModelScope.launch { clips.downloadVideo(jobId) }
  }

  /** "Shadow" on the rail (F-12): shadowing of the current phrase. */
  fun startShadowing(slowSpeed: Float) {
    val v = visit ?: return
    val phrase = currentContent()?.phraseAt(player.positionMs()) ?: return
    val span = ClipSpan(v.id.jobId, phrase.startMs, phrase.endMs, null)
    val track = settingsRepository.settings.value.track
    shadowJob?.cancel()
    shadowJob = viewModelScope.launch { shadowing.run(span, slowSpeed, track) }
  }

  fun stopShadowing() {
    if (shadowJob == null && shadowing.state.value is ShadowingState.Idle) return
    shadowing.stop()
    shadowJob?.cancel()
    shadowJob = null
  }

  fun dismissCoachMark(mark: WatchCoachMark) {
    viewModelScope.launch { coachMarks.dismiss(mark.id) }
  }

  /** The picker opened: its clip rows learn where the learner left each clip. */
  fun loadPickerClips() {
    viewModelScope.launch {
      val onPhone = clips.clips(ClipQuery(filter = ClipFilter.ON_THIS_PHONE)).first()
      pickerClips.value = onPhone.map { clip ->
        val resume = progress.resume(clip.id).first()
        val leftAt = resume?.let { r -> shorts.shorts(clip.id).first().firstOrNull { it.id == r }?.ordinal }
        PickerClip(clip.id, clip.title, leftAt)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Set questions (F-08 item 5) and "Another set"

  fun answer(page: FeedPage.QuestionPage, choice: String) {
    val set = feed.value.set ?: return
    if (page.choice != null) return
    viewModelScope.launch {
      val result = try {
        sets.answer(set.id, page.setPosition, choice)
      } catch (error: IllegalStateException) {
        return@launch
      }
      setAnswer(page.setPosition, choice, result)
    }
  }

  private fun setAnswer(position: Int, choice: String, result: AnswerResult) {
    feed.update { data ->
      data.copy(
        pages = data.pages.map { page ->
          if (page is FeedPage.QuestionPage && page.setPosition == position) page.copy(choice = choice, result = result) else page
        },
      )
    }
  }

  fun playQuestion(question: Question, slow: Boolean, context: Boolean) {
    val span = if (context) question.context ?: question.clip else question.clip
    val track = settingsRepository.settings.value.track
    viewModelScope.launch { spanPlayer.play(span, if (slow) FeedLogic.SLOW else 1f, track) }
  }

  /** "Save" in a question's explanation: nothing is saved unless tapped (F-08-AC6). */
  fun saveAnswerWord(result: AnswerResult) {
    viewModelScope.launch { saved.saveWord(result.explanation.word) }
  }

  /** "Another set" (F-08 item 7), and "Replay the mix" when nothing new is left. */
  fun anotherSet() {
    viewModelScope.launch {
      val set = sets.anotherSet()
      leaveCurrent()
      settledPage = -1
      if (set == null) {
        feed.value = fresh(FeedData(
          loading = false,
          mode = ResolvedMode(FeedMode.Mix(0), ModeName.Mix(today = false)),
          pages = listOf(FeedPage.End(EndKind.NOTHING_LEFT)),
        ))
      } else {
        showSet(set, at = null)
      }
      requestScroll(feed.value.current)
    }
  }

  /** "Watch it again" on the end of a clip. */
  fun watchAgain() {
    requestScroll(0)
  }

  /** [data] as a newly built feed: the screen sets its pager on its current page. */
  private fun fresh(data: FeedData): FeedData = data.copy(generation = ++generation)

  /** The pool, for the composable surface of the current short (architecture 11.2). */
  val feedPlayer: FeedPlayer get() = player

  /** The playhead, read every frame by the captions (architecture 11.3). */
  fun positionMs(): Long = player.positionMs()

  /** A "What's this?" entry (spec F-14). */
  fun glossary(key: String): GlossaryEntry? = didactic.glossary(key)

  /** What "Record yourself" records: the current phrase, with the spotlight word when there is one (F-13). */
  fun recordTarget(): RecordTarget? {
    val v = visit ?: return null
    val c = contents.value[v.id] ?: return null
    val phrase = c.phraseAt(player.positionMs()) ?: return null
    return RecordTarget(c.spotlight?.word, ClipSpan(v.id.jobId, phrase.startMs, phrase.endMs, null), phrase = true)
  }

  private fun currentContent(): ShortContent? {
    val page = feed.value.pages.getOrNull(feed.value.current) as? FeedPage.Short ?: return null
    return contents.value[page.id]
  }

  override fun onCleared() {
    leaveCurrent()
    lease?.close()
    lease = null
    shadowJob?.cancel()
    player.release()
  }

  /** One visit of a short: its seen time, whether it was reported, and whether "Replay it once" happened. */
  private class Visit(val page: FeedPage.Short, val id: ShortId, val lengthMs: Long, val tracker: SeenTracker) {
    var job: Job? = null
    var reported = false
    var replayed = false
  }

  /** The feed as resolved: the mode, its pages and where it stands. */
  internal data class FeedData(
    val loading: Boolean = true,
    val mode: ResolvedMode? = null,
    val empty: FeedEmpty? = null,
    val pages: List<FeedPage> = emptyList(),
    val current: Int = 0,
    val set: StudySet? = null,
    val summary: SetSummary? = null,
    val clipEnd: ClipEnd? = null,
    val clipEndJob: JobId? = null,
    val shortSet: Int? = null,
    /** Bumped whenever the pages are built anew, so the screen puts its pager on [current]. */
    val generation: Int = 0,
  )

  private data class Base(
    val feed: FeedData,
    val contents: Map<ShortId, ShortContent>,
    val failed: Set<JobId>,
    val revealed: Set<Int>,
    val scroll: ScrollRequest?,
  )

  private data class Live(
    val playback: FeedPlaybackState,
    val settings: UserSettings,
    val saved: Set<ShortId>,
    val offline: Boolean,
    val coach: WatchCoachMark?,
  )

  private data class Extra(
    val shadowing: ShadowingState,
    val awaiting: Boolean,
    val picker: PickerState,
    val onTheWay: Int,
    val titles: Map<JobId, String>,
  )

  private fun ModeName.withTitle(titles: Map<JobId, String>): ModeName =
    if (this is ModeName.InOrder && title == null) {
      val job = (feed.value.mode?.mode as? FeedMode.InOrder)?.jobId
      ModeName.InOrder(job?.let { titles[it] })
    } else {
      this
    }

  companion object {
    const val KEY_SHORT_JOB = "watch.short.job"
    const val KEY_SHORT_SEGMENT = "watch.short.segment"
    const val KEY_LESSON_OPENED = "watch.lesson.opened"

    /** A set holds 8 shorts (F-08 item 1); fewer says so. */
    const val FULL_SET = 8
  }
}
