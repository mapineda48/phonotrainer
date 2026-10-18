package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PracticeSession
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.familyFor

/** The pure rules of the Practice screens (spec F-17), kept apart from the ViewModel so they are tested on the JVM. */
internal object PracticeLogic {
  /** A session holds this many questions (web `SESSION_LENGTH`); fewer means the pool ran out early. */
  const val SESSION_LENGTH = 10

  /** The one label the quiz never asks about (web `quizPhenomena`: an elided word leaves nothing to hear). */
  private val NOT_QUIZZED = ChangeLabel("word_elision")

  fun change(reference: Reference, label: ChangeLabel): ChangeRef =
    ChangeRef(label, reference.labels[label] ?: label.value.replace('_', ' '), reference.familyFor(label))

  /** Nothing to practice: no change the quiz asks about has an example in the learner's playable clips. */
  fun nothingToPractice(counts: Map<ChangeLabel, ChangeCount>): Boolean =
    counts.none { (label, count) -> label != NOT_QUIZZED && count.occurrences > 0 }

  /** The focus chip of [raw] (`PracticeKey.focus`), unless the learner chose "Practice everything instead". */
  fun focus(raw: String?, cleared: Boolean, reference: Reference, counts: Map<ChangeLabel, ChangeCount>): FocusUi? {
    if (raw.isNullOrBlank() || cleared) return null
    val label = ChangeLabel(raw)
    if (label !in reference.labels) return FocusUi.Unknown(raw)
    return FocusUi.Known(change(reference, label), playable = (counts[label]?.occurrences ?: 0) > 0)
  }

  /** The change a new session focuses on: a known focus only (an unknown one practises everything, as on the web). */
  fun sessionFocus(focus: FocusUi?): ChangeLabel? = (focus as? FocusUi.Known)?.change?.label

  /**
   * "Your accuracy so far" (web `StatsCard`): every change answered at least once, weakest first (ties by name), with
   * the Saved count and the changes met out of the reference's changes.
   */
  fun progress(
    reference: Reference,
    accuracy: List<ChangeAccuracy>,
    met: Set<ChangeLabel>,
    saved: Int,
  ): ProgressUi {
    val rows = accuracy.filter { it.seen > 0 }
      .map { AccuracyRow(change(reference, it.change), correct = it.correct, seen = it.seen) }
      .sortedWith(compareBy<AccuracyRow>({ it.correct.toDouble() / it.seen }, { it.change.name }))
    val labels = reference.labels.keys
    return ProgressUi(
      rows = rows,
      saved = saved,
      met = labels.count { it in met },
      total = labels.size,
      notMet = labels.filter { it !in met }.map { change(reference, it) },
    )
  }

  /** A session that "Continue" can resume: not finished, with a question left. */
  fun resumable(session: PracticeSession?): Boolean =
    session != null && session.finishedAt == null && session.questions.isNotEmpty() && session.index < session.questions.size

  /** The result of question [index] as `PracticeRepository.answer` gave it, rebuilt from the stored choice. */
  fun result(question: Question, choice: String): AnswerResult =
    AnswerResult(choice == question.answer, question.answer, question.change, question.occurrence)

  /** The family of each "Which change?" option, for its icon. */
  fun families(question: Question, reference: Reference): Map<String, Family> =
    question.options.mapNotNull { option -> option.change?.let { option.id to reference.familyFor(it) } }.toMap()

  /** "7 of 10 right", "Worth reviewing" and the list of the session's questions (web `SessionSummary`). */
  fun results(session: PracticeSession, reference: Reference): ResultsUi {
    val answered = session.questions.mapIndexedNotNull { i, question -> session.answers.getOrNull(i)?.let { question to it } }
    val missed = answered.filter { (question, choice) -> choice != question.answer }
      .map { it.first.change }
      .distinct()
      .map { change(reference, it) }
    val lines = answered.mapIndexed { i, (question, choice) ->
      ResultLine(
        number = i + 1,
        kind = question.kind,
        word = displayWord(question.occurrence.wordText),
        change = change(reference, question.change).name,
        correct = choice == question.answer,
      )
    }
    return ResultsUi(
      right = lines.count { it.correct },
      total = session.questions.size,
      partial = session.questions.size < SESSION_LENGTH,
      missed = missed,
      lines = lines,
    )
  }

  /** The right and wrong answers given so far. */
  fun tally(session: PracticeSession): Pair<Int, Int> {
    var right = 0
    var wrong = 0
    session.questions.forEachIndexed { i, question ->
      val choice = session.answers.getOrNull(i) ?: return@forEachIndexed
      if (choice == question.answer) right++ else wrong++
    }
    return right to wrong
  }

  /** The web's `displayWord`: the word without the punctuation around it. */
  fun displayWord(word: String): String = word.replace(EDGE_PUNCTUATION, "").ifEmpty { word }

  private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$")
}
