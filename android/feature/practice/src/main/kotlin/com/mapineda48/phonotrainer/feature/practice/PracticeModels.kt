package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.ui.component.AnswerFeedbackState
import kotlin.math.roundToInt

/** What the Practice tab shows (spec F-17 States): one page at a time. */
internal enum class PracticePage {
  /** The reference, the corpus counts or the stored session are not read yet. */
  LOADING,

  /** No change of the quiz is in the learner's clips ("Nothing to practice yet"). */
  EMPTY,

  /** The start screen: the kinds, the focus chip, the weak-spots switch, "Start practicing". */
  START,

  /** A session is being generated ("Gathering words from your clips…"). */
  BUILDING,

  /** A question of the session. */
  SESSION,

  /** "7 of 10 right". */
  RESULTS,
}

/** What a play button of the question plays: the clip, slowly (0.5×), or with the words around it. */
internal enum class Playback { NORMAL, SLOW, CONTEXT }

/** A change as Practice names it: its key, its name from the reference ("flapping") and its family. */
@Immutable
internal data class ChangeRef(val label: ChangeLabel, val name: String, val family: Family)

/** The focus chip ("Only: flapping"), from `PracticeKey.focus`. */
@Immutable
internal sealed interface FocusUi {
  /** A change of the reference; [playable] is false when the learner's clips have no example of it. */
  data class Known(val change: ChangeRef, val playable: Boolean) : FocusUi

  /** A key the reference does not know: everything is practised (web "Unknown focus"). */
  data class Unknown(val raw: String) : FocusUi
}

/** A session stored but not finished, which "Continue" resumes at question [number] of [of]. */
@Immutable
internal data class ResumeUi(val number: Int, val of: Int)

@Immutable
internal data class StartUi(
  val focus: FocusUi? = null,
  val weakSpots: Boolean = false,
  val resume: ResumeUi? = null,
) {
  /** "Start practicing" is offered unless the focus has no playable example. */
  val canStart: Boolean get() = (focus as? FocusUi.Known)?.playable ?: true
}

/** One row of "Your accuracy so far": "4 of 9 · 44 %". */
@Immutable
internal data class AccuracyRow(val change: ChangeRef, val correct: Int, val seen: Int) {
  val percent: Int get() = if (seen == 0) 0 else (100.0 * correct / seen).roundToInt()
}

/** The progress block of the start screen (spec F-17 item 5): no streaks, no days, no time spent (F-17-AC5). */
@Immutable
internal data class ProgressUi(
  /** Weakest first. */
  val rows: List<AccuracyRow> = emptyList(),
  val saved: Int = 0,
  val met: Int = 0,
  val total: Int = 0,
  /** The reference's changes not met yet, in its order; each opens its page in Learn. */
  val notMet: List<ChangeRef> = emptyList(),
) {
  /** Whether there is anything to show besides the empty lines (the empty Practice shows it only then). */
  val hasAny: Boolean get() = rows.isNotEmpty() || saved > 0 || met > 0
}

/** Where a bead of the session's row stands. */
internal enum class Bead { DONE, CURRENT, NEXT }

/** The question on screen: question [index] (0-based) of [of], answered with [chosen] or not yet. */
@Immutable
internal data class SessionUi(
  val id: Long,
  val index: Int,
  val of: Int,
  val question: Question,
  val chosen: String?,
  /** The explanation once [chosen] is set. */
  val feedback: AnswerFeedbackState?,
  /** The family of each "Which change?" option. */
  val families: Map<String, Family>,
  val right: Int,
  val wrong: Int,
) {
  val number: Int get() = index + 1

  /** The last question: "See your results" instead of "Next question". */
  val last: Boolean get() = index + 1 >= of

  val beads: List<Bead> get() = List(of) { i -> if (i < index) Bead.DONE else if (i == index) Bead.CURRENT else Bead.NEXT }
}

/** One line of the results' list: "3. Which change? “water” — flapping". */
@Immutable
internal data class ResultLine(val number: Int, val kind: QuestionKind, val word: String, val change: String, val correct: Boolean)

@Immutable
internal data class ResultsUi(
  val right: Int,
  val total: Int,
  /** Fewer questions than a session holds: the pool ran out early (spec F-17 Error / Partial). */
  val partial: Boolean,
  /** The changes of the wrong answers, in order ("Worth reviewing"). */
  val missed: List<ChangeRef>,
  val lines: List<ResultLine>,
)

@Immutable
internal data class PracticeUiState(
  val page: PracticePage = PracticePage.LOADING,
  val start: StartUi = StartUi(),
  val progress: ProgressUi = ProgressUi(),
  val session: SessionUi? = null,
  val results: ResultsUi? = null,
)
