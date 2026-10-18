package com.mapineda48.phonotrainer.feature.practice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.ui.component.answerFeedbackState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Practice tab (spec F-17): the start screen with the focus of [PracticeKey], a session of questions from the
 * learner's clips ([PracticeRepository], which builds and stores the whole session, WP-14), the results, and the
 * progress block with its reset.
 *
 * What the screen shows lives in the [SavedStateHandle]: the session on screen ([ACTIVE]), the question shown ([AT],
 * which stays on an answered question until "Next question"), the session whose results show ([RESULTS]), the
 * weak-spots switch and whether the focus was set aside. The session itself is stored by the repository, so after a
 * process death Practice comes back on the same question, answered or not (spec F-17 States). A session left
 * unfinished in another visit is offered as "Continue" on the start screen.
 */
@HiltViewModel(assistedFactory = PracticeViewModel.Factory::class)
internal class PracticeViewModel @AssistedInject constructor(
  @Assisted private val key: PracticeKey,
  private val savedState: SavedStateHandle,
  private val practice: PracticeRepository,
  private val saved: SavedRepository,
  occurrences: OccurrenceRepository,
  private val references: ReferenceRepository,
  private val spanPlayer: SpanPlayer,
  private val settings: SettingsRepository,
) : ViewModel() {
  @AssistedFactory
  interface Factory {
    fun create(key: PracticeKey): PracticeViewModel
  }

  private val building = MutableStateFlow(false)
  private var answering = false

  /** The stored session (the latest one), or null before the first. */
  private val session: StateFlow<Loaded<PracticeSession?>> =
    practice.currentSession().map { Loaded(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Loaded.Pending)

  private val counts: StateFlow<Loaded<Map<ChangeLabel, ChangeCount>>> =
    occurrences.changeCounts().map { Loaded(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Loaded.Pending)

  private val accuracy: StateFlow<List<ChangeAccuracy>> =
    practice.accuracy().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val met: StateFlow<Set<ChangeLabel>> = practice.changesMet().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  /** The saved words (for the feedback's "Save" / "Saved") and the Saved count. */
  private val savedItems: StateFlow<Pair<Set<WordRef>, Int>> = saved.saved()
    .map { items -> items.filter { it.kind == SavedKind.WORD }.mapNotNullTo(HashSet()) { it.word } to items.size }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet<WordRef>() to 0)

  private val screen = combine(
    savedState.getStateFlow<Long?>(ACTIVE, null),
    savedState.getStateFlow<Int?>(AT, null),
    savedState.getStateFlow<Long?>(RESULTS, null),
    savedState.getStateFlow(WEAK_SPOTS, false),
    savedState.getStateFlow(FOCUS_CLEARED, false),
  ) { active, at, results, weakSpots, cleared -> Screen(active, at, results, weakSpots, cleared) }

  val state: StateFlow<PracticeUiState> = combine(
    combine(session, counts, building) { s, c, b -> Triple(s, c, b) },
    combine(accuracy, met, savedItems) { a, m, sv -> Triple(a, m, sv) },
    references.reference,
    screen,
  ) { (s, c, b), (a, m, sv), reference, screen ->
    build(s, c, b, a, m, sv, reference, screen)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PracticeUiState())

  /**
   * The state as it stands now, read from the inputs' current values and the [SavedStateHandle] directly, so an
   * action decides on what it just changed even while nobody collects [state].
   */
  private fun now(): PracticeUiState = build(
    session.value,
    counts.value,
    building.value,
    accuracy.value,
    met.value,
    savedItems.value,
    references.reference.value,
    Screen(savedState[ACTIVE], savedState[AT], savedState[RESULTS], savedState[WEAK_SPOTS] ?: false, savedState[FOCUS_CLEARED] ?: false),
  )

  private fun build(
    loadedSession: Loaded<PracticeSession?>,
    loadedCounts: Loaded<Map<ChangeLabel, ChangeCount>>,
    building: Boolean,
    accuracy: List<ChangeAccuracy>,
    met: Set<ChangeLabel>,
    savedItems: Pair<Set<WordRef>, Int>,
    reference: Reference,
    screen: Screen,
  ): PracticeUiState {
    if (loadedSession !is Loaded.Value || loadedCounts !is Loaded.Value) return PracticeUiState(page = PracticePage.LOADING)
    val stored = loadedSession.value
    val counts = loadedCounts.value
    val focus = PracticeLogic.focus(key.focus, screen.focusCleared, reference, counts)
    val resume = stored?.takeIf { PracticeLogic.resumable(it) && it.id != screen.active }?.let { ResumeUi(it.index + 1, it.questions.size) }
    val start = StartUi(focus = focus, weakSpots = screen.weakSpots, resume = resume)
    val progress = PracticeLogic.progress(reference, accuracy, met, savedItems.second)
    val base = PracticeUiState(start = start, progress = progress)
    return when {
      building -> base.copy(page = PracticePage.BUILDING)
      stored != null && screen.results == stored.id ->
        base.copy(page = PracticePage.RESULTS, results = PracticeLogic.results(stored, reference))
      stored != null && screen.active == stored.id && stored.questions.isNotEmpty() ->
        base.copy(page = PracticePage.SESSION, session = sessionUi(stored, screen.at, reference, savedItems.first))
      PracticeLogic.nothingToPractice(counts) -> base.copy(page = PracticePage.EMPTY)
      else -> base.copy(page = PracticePage.START)
    }
  }

  private fun sessionUi(session: PracticeSession, at: Int?, reference: Reference, savedWords: Set<WordRef>): SessionUi {
    val index = (at ?: session.index).coerceIn(0, session.questions.lastIndex)
    val question = session.questions[index]
    val chosen = session.answers.getOrNull(index)
    val feedback = chosen?.let {
      answerFeedbackState(PracticeLogic.result(question, it), question, reference, saved = question.occurrence.word in savedWords)
    }
    val (right, wrong) = PracticeLogic.tally(session)
    return SessionUi(
      id = session.id,
      index = index,
      of = session.questions.size,
      question = question,
      chosen = chosen,
      feedback = feedback,
      families = PracticeLogic.families(question, reference),
      right = right,
      wrong = wrong,
    )
  }

  // --- the start screen ------------------------------------------------------------------------------------------

  /** "Practice everything instead": the focus of [PracticeKey] is set aside for this screen. */
  fun clearFocus() {
    savedState[FOCUS_CLEARED] = true
  }

  fun setWeakSpots(on: Boolean) {
    savedState[WEAK_SPOTS] = on
  }

  /** "Start practicing" (and "Practice again"): a new session with the focus and the weak-spots switch. */
  fun start() {
    if (building.value) return
    val focus = PracticeLogic.sessionFocus(now().start.focus)
    val weakSpots = savedState[WEAK_SPOTS] ?: false
    spanPlayer.stop()
    building.value = true
    viewModelScope.launch {
      try {
        val session = practice.newSession(focus, weakSpots)
        savedState[ACTIVE] = session.id
        savedState[AT] = 0
        // No question at all: the results say the clips were not enough this time (spec F-17 Error / Partial).
        savedState[RESULTS] = if (session.questions.isEmpty()) session.id else null
      } finally {
        building.value = false
      }
    }
  }

  /** "Practice my weak spots" on the results: the switch on, then a new session. */
  fun startWeakSpots() {
    savedState[WEAK_SPOTS] = true
    start()
  }

  /** "Continue": the stored session, at its first unanswered question. */
  fun resume() {
    val stored = (session.value as? Loaded.Value)?.value ?: return
    if (!PracticeLogic.resumable(stored)) return
    savedState[ACTIVE] = stored.id
    savedState[AT] = stored.index
    savedState[RESULTS] = null
  }

  /** "Back to the start" and "Leave the session": the start screen; an unfinished session stays resumable. */
  fun backToStart() {
    spanPlayer.stop()
    savedState[ACTIVE] = null
    savedState[AT] = null
    savedState[RESULTS] = null
  }

  // --- a session -------------------------------------------------------------------------------------------------

  /** The learner picks [choice] on the question on screen; a second tap, or one on an answered question, is ignored. */
  fun answer(choice: String) {
    val shown = now().session ?: return
    if (shown.chosen != null || answering) return
    answering = true
    viewModelScope.launch {
      try {
        practice.answer(shown.id, shown.index, choice)
      } catch (_: IllegalStateException) {
        // Answered already (a tap that raced the stored answer) or the session is gone: the stored state stands.
      } finally {
        answering = false
      }
    }
  }

  /** "Next question", or "See your results" after the last one. */
  fun next() {
    val shown = now().session ?: return
    if (shown.chosen == null) return
    spanPlayer.stop()
    if (shown.last) savedState[RESULTS] = shown.id else savedState[AT] = shown.index + 1
  }

  /** "Play", "Slowly" (0.5×), "With context" and "Again at 0.5×" of the question on screen. */
  fun play(how: Playback) {
    val question = now().session?.question ?: return
    val span = if (how == Playback.CONTEXT) question.context ?: question.clip else question.clip
    val speed = if (how == Playback.SLOW) SLOW else 1f
    val track = settings.settings.value.track
    viewModelScope.launch { spanPlayer.play(span, speed, track) }
  }

  /** "Save" in the explanation: the question's word; nothing is saved unless it is tapped (spec F-17 item 3). */
  fun saveWord() {
    val question: Question = now().session?.takeIf { it.chosen != null }?.question ?: return
    viewModelScope.launch { saved.saveWord(question.occurrence.word) }
  }

  /** "Forget it" in the reset confirmation: the accuracy per change is cleared (phone-only in v1-core). */
  fun reset() {
    viewModelScope.launch { practice.reset() }
  }

  /** The screen left: nothing keeps playing. */
  fun stopPlayback() {
    spanPlayer.stop()
  }

  override fun onCleared() {
    spanPlayer.stop()
  }

  /** A flow's first value, or none yet. */
  private sealed interface Loaded<out T> {
    data object Pending : Loaded<Nothing>

    data class Value<T>(val value: T) : Loaded<T>

    companion object {
      operator fun <T> invoke(value: T): Loaded<T> = Value(value)
    }
  }

  private data class Screen(
    val active: Long? = null,
    val at: Int? = null,
    val results: Long? = null,
    val weakSpots: Boolean = false,
    val focusCleared: Boolean = false,
  )

  companion object {
    const val ACTIVE = "practice.active"
    const val AT = "practice.at"
    const val RESULTS = "practice.results"
    const val WEAK_SPOTS = "practice.weakSpots"
    const val FOCUS_CLEARED = "practice.focusCleared"

    /** "Slowly" and "Again at 0.5×" (spec F-17 item 3). */
    const val SLOW = 0.5f
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}
