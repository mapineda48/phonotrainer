package com.mapineda48.phonotrainer.core.domain.model

import java.time.Instant
import java.time.LocalDate

// Work-plan Appendix A.5: study (WP-14, WP-17, WP-21).

data class FeedPosition(val mode: FeedMode, val short: ShortId, val positionMs: Long, val updatedAt: Instant)

data class KeepWatching(val clip: ClipSummary, val short: ShortId, val positionMs: Long)

data class StudySet(
  val id: Long,
  val kind: StudySetKind,
  val date: LocalDate,
  val items: List<StudySetItem>,
  val cursor: Int,
  val answered: Int,
  val right: Int,
  val completedAt: Instant?,
)

enum class StudySetKind { TODAY, EXTRA }

sealed interface StudySetItem {
  data class Short(
    val short: ShortInfo,
    val spotlight: Spotlight?,
    val savedTag: Boolean,
    val dropped: Boolean,
    /** The learner has moved past it in this set (Home's stations, D-45). */
    val done: Boolean = false,
  ) : StudySetItem

  data class QuestionItem(val question: Question, val choice: String?, val correct: Boolean?) : StudySetItem
}

/** `QuizItem` of web/src/features/practice/quiz.ts. */
data class Question(
  val kind: QuestionKind,
  val key: String,
  val prompt: String,
  val clip: ClipSpan,
  val context: ClipSpan?,
  val options: List<QuestionOption>,
  val answer: String,
  val change: ChangeLabel,
  val occurrence: Occurrence,
  val phrase: List<PhraseWord>?,
)

/** The web's ItemKind values "change", "form", "count". */
enum class QuestionKind { CHANGE, FORM, COUNT }

data class QuestionOption(val id: String, val label: String, val ipa: String?, val change: ChangeLabel?)

data class PhraseWord(val text: String, val changed: Boolean)

data class AnswerResult(val correct: Boolean, val answer: String, val change: ChangeLabel, val explanation: Occurrence)

data class PracticeSession(
  val id: Long,
  val focus: ChangeLabel?,
  val weakSpots: Boolean,
  val questions: List<Question>,
  val index: Int,
  val answers: List<String?>,
  val createdAt: Instant,
  val finishedAt: Instant?,
)

data class ChangeAccuracy(val change: ChangeLabel, val seen: Int, val correct: Int)

data class SavedItem(
  val id: Long,
  val kind: SavedKind,
  val word: WordRef?,
  val short: ShortId,
  val change: ChangeLabel?,
  val savedAt: Instant,
  val lastWatchedAt: Instant?,
  val clipTitle: String,
  val available: Boolean,
)

enum class SavedKind { SHORT, WORD }
