package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.PhraseWord
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.ui.component.AnswerFeedbackState
import com.mapineda48.phonotrainer.core.ui.component.ChangeCopy
import java.time.LocalDate

/** Sample states of Practice and Saved for previews and screen tests (a made-up clip, no personal data). */
internal object PracticePreviewData {
  val job = JobId("20261004-133519-a77c")
  private const val CLIP = "A walk in the park"

  val flapping = ChangeRef(ChangeLabel("flapping"), "flapping", Family.TD)
  val tdDeletion = ChangeRef(ChangeLabel("t_deletion"), "t/d deletion", Family.TD)
  val placeAssimilation = ChangeRef(ChangeLabel("place_assimilation"), "place assimilation", Family.ASSIMILATION)
  val linking = ChangeRef(ChangeLabel("linking"), "linking", Family.BOUNDARY)
  val vowelReduction = ChangeRef(ChangeLabel("vowel_reduction"), "vowel reduction", Family.REDUCTION)
  val hDropping = ChangeRef(ChangeLabel("h_dropping"), "h-dropping", Family.OTHER)

  val water = WordRef(job, segment = 2, word = 4)

  val occurrence = Occurrence(
    word = water,
    shortFirstSegment = 2,
    wordText = "water",
    dictIpa = "ˈwɔtɚ",
    realizedIpa = "ˈwɔɾɚ",
    startMs = 12_400,
    endMs = 12_800,
    change = flapping.label,
    diffCost = 0.2,
    advice = Advice.PRODUCE,
    family = Family.TD,
    crossesBoundary = false,
    nextWordText = null,
    clipTitle = CLIP,
  )

  val formQuestion = Question(
    kind = QuestionKind.FORM,
    key = "form:water",
    prompt = "Listen to “water”. Which pronunciation did you hear?",
    clip = ClipSpan(job, 12_400, 12_800, water),
    context = ClipSpan(job, 11_900, 13_400, null),
    options = listOf(
      QuestionOption("dictionary", "[ˈwɔtɚ]", "ˈwɔtɚ", null),
      QuestionOption("said", "[ˈwɔɾɚ]", "ˈwɔɾɚ", null),
      QuestionOption("variant:2", "[ˈwɔʔɚ]", "ˈwɔʔɚ", null),
      QuestionOption("variant:3", "[ˈwɑɾə]", "ˈwɑɾə", null),
    ),
    answer = "said",
    change = flapping.label,
    occurrence = occurrence,
    phrase = null,
  )

  val changeQuestion = formQuestion.copy(
    kind = QuestionKind.CHANGE,
    key = "change:water",
    prompt = "Listen to “water”. Which change did you hear?",
    context = null,
    options = listOf(linking, flapping, vowelReduction, placeAssimilation).map {
      QuestionOption(it.label.value, it.name, null, it.label)
    },
    answer = flapping.label.value,
  )

  val countQuestion = formQuestion.copy(
    kind = QuestionKind.COUNT,
    key = "count:2",
    prompt = "Listen to the phrase. How many words did you hear?",
    context = null,
    options = listOf("4", "5", "6").map { QuestionOption(it, "$it words", null, null) },
    answer = "5",
    phrase = listOf("Did" to false, "you" to true, "get" to false, "the" to true, "water" to false).map { PhraseWord(it.first, it.second) },
  )

  private val flappingCopy = ChangeCopy(
    name = "flapping",
    description = "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ",
    advice = Advice.PRODUCE,
    register = Register.UNIVERSAL,
  )

  private val families = listOf(linking, flapping, vowelReduction, placeAssimilation).associate { it.label.value to it.family }

  fun feedback(question: Question, choice: String, saved: Boolean = false): AnswerFeedbackState = AnswerFeedbackState(
    result = AnswerResult(choice == question.answer, question.answer, question.change, question.occurrence),
    saved = saved,
    change = flappingCopy,
    phrase = question.phrase.takeIf { question.kind == QuestionKind.COUNT },
  )

  val progress = ProgressUi(
    rows = listOf(
      AccuracyRow(tdDeletion, correct = 4, seen = 9),
      AccuracyRow(placeAssimilation, correct = 3, seen = 5),
      AccuracyRow(flapping, correct = 7, seen = 10),
    ),
    saved = 12,
    met = 11,
    total = 16,
    notMet = listOf(hDropping, linking),
  )

  val start = StartUi(focus = null, weakSpots = false)

  val startState = PracticeUiState(page = PracticePage.START, start = start, progress = progress)

  val focusState = startState.copy(start = StartUi(focus = FocusUi.Known(flapping, playable = true), weakSpots = true))

  val firstRunState = PracticeUiState(page = PracticePage.EMPTY, progress = ProgressUi(total = 16))

  fun session(question: Question = formQuestion, chosen: String? = null, index: Int = 2, of: Int = 10): SessionUi = SessionUi(
    id = 7,
    index = index,
    of = of,
    question = question,
    chosen = chosen,
    feedback = chosen?.let { feedback(question, it) },
    families = if (question.kind == QuestionKind.CHANGE) families else emptyMap(),
    right = 1,
    wrong = 1,
  )

  fun sessionState(session: SessionUi = session()): PracticeUiState =
    PracticeUiState(page = PracticePage.SESSION, session = session, progress = progress)

  val results = ResultsUi(
    right = 7,
    total = 10,
    partial = false,
    missed = listOf(tdDeletion, placeAssimilation),
    lines = listOf(
      ResultLine(1, QuestionKind.FORM, "water", "flapping", correct = true),
      ResultLine(2, QuestionKind.CHANGE, "just", "t/d deletion", correct = false),
      ResultLine(3, QuestionKind.COUNT, "ten", "place assimilation", correct = false),
    ),
  )

  val resultsState = PracticeUiState(page = PracticePage.RESULTS, results = results, progress = progress)

  val partialResultsState = resultsState.copy(
    results = ResultsUi(right = 3, total = 4, partial = true, missed = listOf(flapping), lines = results.lines.take(1)),
  )

  fun savedRow(
    id: Long,
    kind: SavedKind = SavedKind.WORD,
    available: Boolean = true,
    lastWatched: LastWatched = LastWatched.DaysAgo(3),
    playing: Boolean = false,
  ): SavedRowUi = SavedRowUi(
    id = id,
    kind = kind,
    short = ShortId(job, 2),
    word = water,
    wordText = "water",
    occurrence = occurrence.takeIf { available },
    span = ClipSpan(job, 12_400, 12_800, water),
    change = flapping,
    clipTitle = CLIP,
    lastWatched = lastWatched,
    available = available,
    playing = playing,
  )

  val savedState = SavedUiState(
    loading = false,
    rows = listOf(
      savedRow(1, kind = SavedKind.SHORT, lastWatched = LastWatched.Today, playing = true),
      savedRow(2, kind = SavedKind.WORD, lastWatched = LastWatched.On(LocalDate.of(2026, 9, 4), sameYear = true)),
      savedRow(3, kind = SavedKind.SHORT, available = false, lastWatched = LastWatched.Never),
    ),
  )
}
