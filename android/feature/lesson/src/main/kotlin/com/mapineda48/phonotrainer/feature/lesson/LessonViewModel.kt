package com.mapineda48.phonotrainer.feature.lesson

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.ClipLease
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.extension.RecordSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The word lesson (spec F-10, F-12, F-14 in step 2): one ViewModel per [LessonKey], over the clip's parsed analysis
 * ([ClipContentRepository], which it holds a lease on while it lives, so a deletion waits until the lesson closes:
 * F-10 Error) and the local audio ([SpanPlayer], [ShadowingRunner]).
 *
 * The word studied, the open steps and the step last opened, the selected sound, speed, loop, track and the practice
 * choices live in the [SavedStateHandle] (architecture 3.1, 16.2), so the lesson comes back on the same word at the
 * same step after a process death (F-10-AC6). Moving to another word resets what belongs to a word: the open steps go
 * back to the guidance default, the selection, the play count and the held sound are cleared (spec F-10 item 3).
 */
@HiltViewModel(assistedFactory = LessonViewModel.Factory::class)
internal class LessonViewModel @AssistedInject constructor(
  @Assisted private val key: LessonKey,
  private val savedState: SavedStateHandle,
  private val content: ClipContentRepository,
  clips: ClipRepository,
  private val spanPlayer: SpanPlayer,
  private val shadowing: ShadowingRunner,
  private val saved: SavedRepository,
  private val settings: SettingsRepository,
  private val reference: ReferenceRepository,
  private val didactic: DidacticContent,
  mouthRenderer: Optional<MouthRenderer>,
  recordSection: Optional<RecordSection>,
) : ViewModel() {
  @AssistedFactory
  interface Factory {
    fun create(key: LessonKey): LessonViewModel
  }

  /** WP-29's drawing of the mouth when its module binds it (work-plan 4.4); null in v1-core: step 4's fallback. */
  val mouth: MouthRenderer? = mouthRenderer.orElse(null)

  /** WP-30's "Record yourself" when its module binds it; null in v1-core: step 5 offers shadowing only. */
  val record: RecordSection? = recordSection.orElse(null)

  /** What the span player is doing, for step 4's drawing to follow the playhead. */
  val playback: StateFlow<SpanPlaybackState> = spanPlayer.state

  private val session = MutableStateFlow(restore())
  private val loaded = MutableStateFlow<Loaded>(Loaded.Loading)
  private val rows = MutableStateFlow<Map<WordRef, List<CompareRow>>>(emptyMap())
  private val playing = MutableStateFlow<ListenTarget?>(null)
  private val ownShadowing = MutableStateFlow(false)
  private var lease: ClipLease? = content.open(key.jobId)
  private var playJob: Job? = null
  private var shadowJob: Job? = null

  /** The current span run and shadowing run (identity tokens), and whether each still owes a stop. */
  private var playRun: Any? = null
  private var playOwed = false
  private var shadowRun: Any? = null
  private var shadowOwed = false
  private var moveJob: Job? = null
  private var saving = false

  /** The saved words of the learner and their item ids (to unsave). */
  private val savedWords: StateFlow<Map<WordRef, Long>> = saved.saved()
    .map { items -> items.filter { it.kind == SavedKind.WORD }.mapNotNull { item -> item.word?.let { it to item.id } }.toMap() }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  private val pausedTitle: Flow<String?> =
    if (key.presentation == LessonPresentation.SHEET && key.origin == LessonOrigin.FEED) {
      clips.clip(key.jobId).map { it?.summary?.title?.takeIf(String::isNotBlank) }
    } else {
      flowOf(null)
    }

  private val shown: Flow<ShadowingState> = combine(shadowing.state, ownShadowing) { state, own ->
    if (own) state else ShadowingState.Idle
  }

  val state: StateFlow<LessonUiState> = combine(
    combine(session, loaded, rows) { s, l, r -> Triple(s, l, r) },
    combine(reference.reference, savedWords, settings.settings) { ref, words, set -> Triple(ref, words, set) },
    combine(playing, shown, pausedTitle) { p, sh, title -> Triple(p, sh, title) },
  ) { (s, l, r), (ref, words, set), (p, sh, title) ->
    when (l) {
      Loaded.Loading -> LessonUiState.Loading
      is Loaded.Failed -> LessonUiState.Unavailable(l.reason)
      is Loaded.Ready -> build(s, l, r, ref, words, set, p, sh, title)
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LessonUiState.Loading)

  init {
    viewModelScope.launch { load() }
  }

  private suspend fun load() {
    val clip = try {
      content.clipData(key.jobId)
    } catch (unreadable: ClipDataException) {
      val reason = if (unreadable.error == AppError.Content.Corrupt) Unavailable.CLIP_DAMAGED else Unavailable.CLIP_GONE
      loaded.value = Loaded.Failed(reason)
      return
    }
    val media = content.media(key.jobId)
    if (session.value.open == null) {
      settings.awaitLoaded()
      val guidance = settings.settings.value.lessonGuidance
      update { it.copy(open = defaultSteps(guidance)) }
    }
    val ref = session.value.ref
    if (clip.word(ref) != null) {
      val first = content.compareRows(ref)
      rows.update { it + (ref to first) }
    }
    loaded.value = Loaded.Ready(clip, media)
  }

  private fun build(
    s: Session,
    l: Loaded.Ready,
    rows: Map<WordRef, List<CompareRow>>,
    reference: Reference,
    savedWords: Map<WordRef, Long>,
    settings: UserSettings,
    playing: ListenTarget?,
    shadowingState: ShadowingState,
    title: String?,
  ): LessonUiState {
    val clip = l.clip
    val ref = s.ref
    val word = clip.word(ref) ?: return LessonUiState.Unavailable(Unavailable.WORD_GONE)
    val crossing = LessonLogic.crossingNext(clip, ref)
    val media = l.media
    val hasAudio = media != null
    val dialogue = hasDialogue(media)
    val track = trackOf(s, settings, media)
    val changes = word.changes.map { LessonLogic.change(reference, word, it) }
    val advice = changes.firstNotNullOfOrNull { it.advice }
    val header = HeaderUi(
      ref = ref,
      text = LessonLogic.displayText(word.text),
      nextText = crossing?.let { LessonLogic.displayText(it.text) },
      position = LessonLogic.position(clip, ref),
      total = clip.wordCount,
      phrase = ref.segment + 1,
      startS = word.startS,
      endS = word.endS,
      changes = changes,
      advice = advice,
      reliability = LessonLogic.reliability(word),
      canPrevious = LessonLogic.previous(clip, ref) != null,
      canNext = LessonLogic.next(clip, ref) != null,
      canNextChange = LessonLogic.nextChange(clip, ref) != null,
      saved = ref in savedWords,
    )
    val listen = ListenUi(
      hasAudio = hasAudio,
      nextText = header.nextText,
      played = s.played,
      speed = s.speed ?: defaultSpeed(settings),
      loop = s.loop,
      track = track,
      dialogue = dialogue,
      playing = playing,
    )
    val target = s.target ?: LessonLogic.defaultTarget(clip, ref)
    val practiceNext = LessonLogic.practiceNext(clip, ref)
    val span = practiceSpan(clip, ref, target)
    val practice = PracticeUi(
      target = target,
      wordAndNext = practiceNext != null,
      slowSpeed = s.slowSpeed,
      hasAudio = hasAudio,
      recognizeOnly = advice?.advice == Advice.UNDERSTAND,
      shadowing = shadowingState,
      span = span,
      recordTarget = RecordTarget(ref, span, phrase = target == ShadowingTarget.PHRASE),
    )
    return LessonUiState.Ready(
      presentation = key.presentation,
      header = header,
      open = s.open.orEmpty(),
      at = s.at,
      reveal = s.reveal,
      listen = listen,
      compare = LessonLogic.compare(clip, ref, rows[ref].orEmpty(), reference, didactic, s.selected),
      why = LessonLogic.why(clip, ref, reference),
      see = LessonLogic.see(clip, ref, s.stream, s.held, hasAudio, didactic),
      practice = practice,
      paused = title?.let(::PausedUi),
    )
  }

  private fun hasDialogue(media: ClipMedia?): Boolean = media?.audioDialogue != null && media.audioDialogue != media.audioMix

  /** The lesson's track: the learner's choice, else the Settings track; the mix when no dialogue track is here. */
  private fun trackOf(s: Session, settings: UserSettings, media: ClipMedia?): Track {
    val track = s.track ?: settings.track
    return if (track == Track.DIALOGUE && !hasDialogue(media)) Track.MIX else track
  }

  private fun currentTrack(): Track =
    trackOf(session.value, settings.settings.value, (loaded.value as? Loaded.Ready)?.media)

  /** The glossary entry of [key], or null when the content has none (then no "?" is shown, spec F-14). */
  fun glossary(key: String): GlossaryEntry? = didactic.glossary(key)

  // --- steps -----------------------------------------------------------------------------------------------------

  /** Opens or closes [step]; an opened step becomes the one the lesson comes back to (F-10-AC6). */
  fun toggleStep(step: LessonStep) = update { s ->
    val open = s.open.orEmpty()
    if (step in open) s.copy(open = open - step) else s.copy(open = open + step, at = step)
  }

  // --- moving between words ----------------------------------------------------------------------------------------

  fun previous() = move { clip, ref -> LessonLogic.previous(clip, ref) }

  fun next() = move { clip, ref -> LessonLogic.next(clip, ref) }

  /** "Next change" (F-10-AC5). */
  fun nextChange() = move { clip, ref -> LessonLogic.nextChange(clip, ref) }

  private fun move(to: (ClipData, WordRef) -> WordRef?) {
    val clip = (loaded.value as? Loaded.Ready)?.clip ?: return
    val target = to(clip, session.value.ref) ?: return
    if (moveJob?.isActive == true) return
    stopSound()
    moveJob = viewModelScope.launch {
      if (target !in rows.value) {
        val loadedRows = content.compareRows(target)
        rows.update { cache -> (cache + (target to loadedRows)).entries.toList().takeLast(ROWS_KEPT).associate { it.toPair() } }
      }
      val guidance = settings.settings.value.lessonGuidance
      update {
        it.copy(
          segment = target.segment, word = target.word, open = defaultSteps(guidance), at = null, selected = null,
          played = 0, held = null, stream = MouthStream.SAID, target = null,
        )
      }
    }
  }

  // --- step 1 ------------------------------------------------------------------------------------------------------

  /** "Play the word", "Play with “you”", "Play the phrase". */
  fun play(what: ListenTarget) {
    val ready = loaded.value as? Loaded.Ready ?: return
    if (ready.media == null) return
    val clip = ready.clip
    val ref = session.value.ref
    val word = clip.word(ref) ?: return
    val span = when (what) {
      ListenTarget.WORD -> LessonLogic.wordSpan(clip, ref, word)
      ListenTarget.WITH_NEXT -> LessonLogic.crossingNext(clip, ref)?.let { LessonLogic.spanThrough(clip, ref, word, it) } ?: return
      ListenTarget.PHRASE -> LessonLogic.phraseSpan(clip, ref)
    }
    update { it.copy(played = it.played + 1) }
    playSpan(span, what, loop = session.value.loop)
  }

  fun setSpeed(speed: Float) = update { it.copy(speed = speed) }

  fun setLoop(loop: Boolean) = update { it.copy(loop = loop) }

  fun setTrack(track: Track) = update { it.copy(track = track) }

  // --- step 2 ------------------------------------------------------------------------------------------------------

  /** A sort tapped: it is selected and its sound plays (web `pick`). */
  fun pickSort(index: Int) {
    update { it.copy(selected = index) }
    playSelectedSound()
  }

  /** "Play this sound". */
  fun playSelectedSound() {
    val sort = currentSort() ?: return
    val ready = loaded.value as? Loaded.Ready ?: return
    if (ready.media == null || sort.endMs <= sort.startMs) return
    playSpan(ClipSpan(key.jobId, sort.startMs, sort.endMs, session.value.ref), null, loop = false)
  }

  private fun currentSort(): SortUi? {
    val index = session.value.selected ?: return null
    return LessonLogic.sorts(rows.value[session.value.ref].orEmpty()).getOrNull(index)
  }

  /** "See it in the mouth": step 4 opens holding the selected sound, in its row (web `onSeeMouth`). */
  fun seeSelectedInMouth() {
    val sort = currentSort() ?: return
    val clip = (loaded.value as? Loaded.Ready)?.clip ?: return
    val ref = session.value.ref
    val (stream, index) = LessonLogic.mouthTarget(sort) { LessonLogic.see(clip, ref, it, null, true, didactic) }
    update {
      it.copy(
        stream = stream, held = index, open = it.open.orEmpty() + LessonStep.SEE, at = LessonStep.SEE,
        reveal = it.reveal + 1,
      )
    }
  }

  // --- step 4 ------------------------------------------------------------------------------------------------------

  /** "Show the mouth for: What was said | Dictionary form". */
  fun setStream(stream: MouthStream) = update { it.copy(stream = stream, held = null) }

  /** A sound button: the mouth holds it and it plays. */
  fun holdSound(index: Int) {
    val ready = loaded.value as? Loaded.Ready ?: return
    val s = session.value
    val sound = LessonLogic.see(ready.clip, s.ref, s.stream, null, true, didactic).sounds.getOrNull(index) ?: return
    update { it.copy(held = index) }
    if (ready.media != null && sound.endMs > sound.startMs) {
      playSpan(ClipSpan(key.jobId, sound.startMs, sound.endMs, session.value.ref), null, loop = false)
    }
  }

  /** "Follow the playback again". */
  fun follow() = update { it.copy(held = null) }

  // --- step 5 ------------------------------------------------------------------------------------------------------

  fun setPractice(target: ShadowingTarget, slowSpeed: Float) {
    if (ownShadowing.value) return
    update { it.copy(target = target, slowSpeed = slowSpeed) }
  }

  /** "Start shadowing" (F-12): the chosen span at the chosen slow speed, on the lesson's track. */
  fun startShadowing() {
    val ready = loaded.value as? Loaded.Ready ?: return
    if (ready.media == null) return
    val s = session.value
    if (ready.clip.word(s.ref) == null) return
    stopSound()
    val span = practiceSpan(ready.clip, s.ref, s.target ?: LessonLogic.defaultTarget(ready.clip, s.ref))
    val slow = s.slowSpeed
    val track = currentTrack()
    val run = Any()
    shadowRun = run
    shadowOwed = true
    ownShadowing.value = true
    shadowJob = viewModelScope.launch {
      try {
        shadowing.run(span, slow, track)
        // It ended on its own (the plan finished, or the runner stopped it): nothing is owed.
        if (shadowRun === run) shadowOwed = false
      } finally {
        if (shadowRun === run) ownShadowing.value = false
      }
    }
  }

  /** "Stop": the runner restores the previous speed. */
  fun stopShadowing() {
    if (shadowOwed) shadowing.stop()
    shadowOwed = false
    shadowRun = null
    shadowJob?.cancel()
    shadowJob = null
    ownShadowing.value = false
  }

  // --- saving ------------------------------------------------------------------------------------------------------

  /** "Save this word" / unsave it (spec F-09 item 1); one write at a time. */
  fun toggleSaved() {
    if (saving) return
    val ref = session.value.ref
    saving = true
    viewModelScope.launch {
      try {
        val id = savedWords.value[ref]
        if (id != null) saved.unsave(id) else saved.saveWord(ref)
      } finally {
        saving = false
      }
    }
  }

  // --- playback ----------------------------------------------------------------------------------------------------

  private fun playSpan(span: ClipSpan, what: ListenTarget?, loop: Boolean) {
    stopShadowing()
    playJob?.cancel()
    val speed = session.value.speed ?: defaultSpeed(settings.settings.value)
    val track = currentTrack()
    val play = Any()
    playRun = play
    playOwed = true
    playing.value = what
    playJob = viewModelScope.launch {
      try {
        spanPlayer.play(span, speed, track, loop)
        if (playRun === play) playOwed = false
      } finally {
        if (playRun === play) playing.value = null
      }
    }
  }

  /**
   * Stops what this lesson plays (a span or shadowing), never someone else's sound. A run owes a stop until it ends
   * on its own, so closing the lesson stops it even after its coroutine was cancelled with the ViewModel's scope.
   */
  private fun stopSound() {
    if (playOwed) spanPlayer.stop()
    playOwed = false
    playRun = null
    playJob?.cancel()
    playJob = null
    playing.value = null
    stopShadowing()
  }

  override fun onCleared() {
    // Closing the lesson stops its sound and restores the speed (F-12 States), and lets a deferred removal go ahead.
    stopSound()
    lease?.close()
    lease = null
  }

  // --- state -------------------------------------------------------------------------------------------------------

  private fun practiceSpan(clip: ClipData, ref: WordRef, target: ShadowingTarget): ClipSpan {
    val word = checkNotNull(clip.word(ref))
    return when (target) {
      ShadowingTarget.PHRASE -> LessonLogic.phraseSpan(clip, ref)
      ShadowingTarget.WORD ->
        LessonLogic.practiceNext(clip, ref)?.let { LessonLogic.spanThrough(clip, ref, word, it) } ?: LessonLogic.wordSpan(clip, ref, word)
    }
  }

  private fun update(change: (Session) -> Session) {
    val next = change(session.value)
    session.value = next
    persist(next)
  }

  private fun restore(): Session {
    val h = savedState
    return Session(
      segment = h[KEY_SEGMENT] ?: key.segment,
      word = h[KEY_WORD] ?: key.word,
      open = h.get<IntArray>(KEY_OPEN)?.toList()?.mapNotNull(LessonStep::of)?.toSet(),
      at = h.get<Int>(KEY_AT)?.let(LessonStep::of),
      reveal = h[KEY_REVEAL] ?: 0,
      selected = h.get<Int>(KEY_SELECTED)?.takeIf { it >= 0 },
      played = h[KEY_PLAYED] ?: 0,
      speed = h.get<Float>(KEY_SPEED)?.takeIf { it > 0f },
      loop = h[KEY_LOOP] ?: false,
      track = h.get<String>(KEY_TRACK)?.let { name -> Track.entries.firstOrNull { it.name == name } },
      stream = h.get<String>(KEY_STREAM)?.let { name -> MouthStream.entries.firstOrNull { it.name == name } } ?: MouthStream.SAID,
      held = h.get<Int>(KEY_HELD)?.takeIf { it >= 0 },
      target = h.get<String>(KEY_TARGET)?.let { name -> ShadowingTarget.entries.firstOrNull { it.name == name } },
      slowSpeed = h[KEY_SLOW] ?: DEFAULT_SLOW,
    )
  }

  private fun persist(s: Session) {
    val h = savedState
    h[KEY_SEGMENT] = s.segment
    h[KEY_WORD] = s.word
    h[KEY_OPEN] = s.open?.map { it.number }?.sorted()?.toIntArray()
    h[KEY_AT] = s.at?.number
    h[KEY_REVEAL] = s.reveal
    h[KEY_SELECTED] = s.selected ?: -1
    h[KEY_PLAYED] = s.played
    h[KEY_SPEED] = s.speed ?: 0f
    h[KEY_LOOP] = s.loop
    h[KEY_TRACK] = s.track?.name
    h[KEY_STREAM] = s.stream.name
    h[KEY_HELD] = s.held ?: -1
    h[KEY_TARGET] = s.target?.name
    h[KEY_SLOW] = s.slowSpeed
  }

  /** What the learner chose in this lesson; all of it survives a process death. */
  private data class Session(
    val segment: Int,
    val word: Int,
    /** null until the lesson first opens: then the guidance default. */
    val open: Set<LessonStep>?,
    val at: LessonStep?,
    /** Bumped when a step should be brought into view ("See it in the mouth"). */
    val reveal: Int,
    val selected: Int?,
    val played: Int,
    /** null: the learner's default speed. */
    val speed: Float?,
    val loop: Boolean,
    /** null: the learner's track setting. */
    val track: Track?,
    val stream: MouthStream,
    val held: Int?,
    /** null: the word's default (the phrase when it is 6 s or shorter). */
    val target: ShadowingTarget?,
    val slowSpeed: Float,
  )

  private val Session.ref: WordRef get() = WordRef(key.jobId, segment, word)

  private sealed interface Loaded {
    data object Loading : Loaded

    data class Failed(val reason: Unavailable) : Loaded

    class Ready(val clip: ClipData, val media: ClipMedia?) : Loaded
  }

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
    private const val ROWS_KEPT = 8
    private const val DEFAULT_SLOW = 0.75f
    private val LISTEN_SPEEDS = listOf(0.5f, 0.75f, 1f)

    internal const val KEY_SEGMENT = "lesson.segment"
    internal const val KEY_WORD = "lesson.word"
    internal const val KEY_OPEN = "lesson.open"
    internal const val KEY_AT = "lesson.at"
    internal const val KEY_REVEAL = "lesson.reveal"
    internal const val KEY_SELECTED = "lesson.selected"
    internal const val KEY_PLAYED = "lesson.played"
    internal const val KEY_SPEED = "lesson.speed"
    internal const val KEY_LOOP = "lesson.loop"
    internal const val KEY_TRACK = "lesson.track"
    internal const val KEY_STREAM = "lesson.stream"
    internal const val KEY_HELD = "lesson.held"
    internal const val KEY_TARGET = "lesson.target"
    internal const val KEY_SLOW = "lesson.slow"

    /** Guidance "Full": steps 1 to 4 open, 5 closed; "Compact": titles only (spec F-10 item 3, F-10-AC3). */
    fun defaultSteps(guidance: LessonGuidance): Set<LessonStep> = when (guidance) {
      LessonGuidance.FULL -> setOf(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.WHY, LessonStep.SEE)
      LessonGuidance.COMPACT -> emptySet()
    }

    /** The learner's default speed when step 1 offers it (0.5×, 0.75×, 1×), else 1×. */
    fun defaultSpeed(settings: UserSettings): Float = settings.defaultSpeed.takeIf { it in LISTEN_SPEEDS } ?: 1f
  }
}
