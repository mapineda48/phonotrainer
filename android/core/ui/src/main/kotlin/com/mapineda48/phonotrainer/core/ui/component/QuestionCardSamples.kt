package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PhraseWord
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.domain.model.Register

/** Sample questions of the three kinds for the previews and tests of the quiz card (made-up clip, no personal data). */
internal object QuestionCardSamples {
  private val occurrence = PreviewData.occurrence
  private val span = ClipSpan(occurrence.word.jobId, occurrence.startMs, occurrence.endMs, occurrence.word)
  private val context = ClipSpan(occurrence.word.jobId, 11_900, 13_400, null)

  /** "Which pronunciation?" on “water”: what was said, the dictionary form and two other attested forms. */
  val form = Question(
    kind = QuestionKind.FORM,
    key = "form:water",
    prompt = "Listen to “water”. Which pronunciation did you hear?",
    clip = span,
    context = context,
    options = listOf(
      QuestionOption("dictionary", "[ˈwɔtɚ]", "ˈwɔtɚ", null),
      QuestionOption("said", "[ˈwɔɾɚ]", "ˈwɔɾɚ", null),
      QuestionOption("variant:2", "[ˈwɔʔɚ]", "ˈwɔʔɚ", null),
      QuestionOption("variant:3", "[ˈwɑɾə]", "ˈwɑɾə", null),
    ),
    answer = "said",
    change = occurrence.change,
    occurrence = occurrence,
    phrase = null,
  )

  /** "Which change?" on “water”: flapping and three changes of other families. */
  val change = form.copy(
    kind = QuestionKind.CHANGE,
    key = "change:water",
    prompt = "Listen to “water”. Which change did you hear?",
    context = null,
    options = listOf(
      QuestionOption("linking", "linking", null, ChangeLabel("linking")),
      QuestionOption("flapping", "flapping", null, ChangeLabel("flapping")),
      QuestionOption("vowel_reduction", "vowel reduction", null, ChangeLabel("vowel_reduction")),
      QuestionOption("place_assimilation", "place assimilation", null, ChangeLabel("place_assimilation")),
    ),
    answer = "flapping",
  )

  /** "How many words?" on a five-word phrase with two changed words. */
  val count = form.copy(
    kind = QuestionKind.COUNT,
    key = "count:2",
    prompt = "Listen to the phrase. How many words did you hear?",
    context = null,
    options = listOf(
      QuestionOption("4", "4 words", null, null),
      QuestionOption("5", "5 words", null, null),
      QuestionOption("6", "6 words", null, null),
    ),
    answer = "5",
    phrase = listOf(
      PhraseWord("Did", false),
      PhraseWord("you", true),
      PhraseWord("get", false),
      PhraseWord("the", true),
      PhraseWord("water", false),
    ),
  )

  /** The families of the change options, as a screen gives them from the reference. */
  val families: Map<String, Family> = mapOf(
    "linking" to Family.BOUNDARY,
    "flapping" to Family.TD,
    "vowel_reduction" to Family.REDUCTION,
    "place_assimilation" to Family.ASSIMILATION,
  )

  val flapping = ChangeCopy(
    name = "flapping",
    description = "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ",
    advice = Advice.PRODUCE,
    register = Register.UNIVERSAL,
  )

  fun result(question: Question, choice: String): AnswerResult =
    AnswerResult(choice == question.answer, question.answer, question.change, question.occurrence)
}

@PreviewLightDark
@Composable
private fun QuestionCardOpenPreview() {
  PhonoPreviewSurface {
    QuestionCard(QuestionCardState(QuestionCardSamples.form, number = 2, of = 10), onAnswer = {}, onPlay = {}, onSlow = {}, onContext = {})
  }
}

@PreviewLightDark
@Composable
private fun QuestionCardWrongPreview() {
  PhonoPreviewSurface {
    QuestionCard(
      QuestionCardState(QuestionCardSamples.form, chosen = "dictionary", number = 2, of = 10),
      onAnswer = {},
      onPlay = {},
      onSlow = {},
      onContext = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun QuestionCardChangeRightPreview() {
  PhonoPreviewSurface {
    QuestionCard(
      QuestionCardState(QuestionCardSamples.change, chosen = "flapping", families = QuestionCardSamples.families),
      onAnswer = {},
      onPlay = {},
      onSlow = {},
      onContext = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun QuestionCardCountPreview() {
  PhonoPreviewSurface {
    QuestionCard(QuestionCardState(QuestionCardSamples.count), onAnswer = {}, onPlay = {}, onSlow = {}, onContext = {})
  }
}

@PreviewLightDark
@Composable
private fun AnswerFeedbackWrongPreview() {
  val question = QuestionCardSamples.form
  PhonoPreviewSurface {
    AnswerFeedback(
      AnswerFeedbackState(QuestionCardSamples.result(question, "dictionary"), change = QuestionCardSamples.flapping),
      onLesson = {},
      onLearn = {},
      onWatchMore = {},
      onSave = {},
      onAgainSlow = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun AnswerFeedbackCountSavedPreview() {
  val question = QuestionCardSamples.count
  PhonoPreviewSurface {
    AnswerFeedback(
      AnswerFeedbackState(
        QuestionCardSamples.result(question, "5"),
        saved = true,
        change = QuestionCardSamples.flapping,
        phrase = question.phrase,
      ),
      onLesson = {},
      onLearn = {},
      onWatchMore = {},
      onSave = {},
    )
  }
}
