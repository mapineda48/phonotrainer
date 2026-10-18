package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.PhraseWord
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.R

/**
 * The words of the change an answer was about (spec F-17 item 3): its [name] ("flapping"), its [description], and the
 * report's practice advice ([advice] and [register]) when the reference has them.
 */
@Immutable
data class ChangeCopy(
  val name: String,
  val description: String? = null,
  val advice: Advice? = null,
  val register: Register? = null,
)

/**
 * The feedback after an answer (design 9.14): the [result], whether its word is [saved], the [change]'s words (null:
 * the change's key made readable and the occurrence's advice) and, for "How many words?", the [phrase] with its
 * changed words. [answerFeedbackState] builds it from the question and the reference.
 */
@Immutable
data class AnswerFeedbackState(
  val result: AnswerResult,
  val saved: Boolean = false,
  val change: ChangeCopy? = null,
  val phrase: List<PhraseWord>? = null,
)

/** The feedback of [result] for [question] (its phrase, for a count), with the change's words from [reference]. */
fun answerFeedbackState(result: AnswerResult, question: Question?, reference: Reference?, saved: Boolean): AnswerFeedbackState {
  val label = result.change
  val change = reference?.let { ref ->
    val practice = ref.practice[label]
    ChangeCopy(
      name = ref.labels[label] ?: readable(label.value),
      description = ref.descriptions[label]?.takeIf(String::isNotBlank),
      advice = practice?.advice ?: result.explanation.advice,
      register = practice?.register,
    )
  }
  val phrase = question?.takeIf { it.kind == QuestionKind.COUNT }?.phrase
  return AnswerFeedbackState(result, saved, change, phrase)
}

/** A change key read as words when the reference has no name for it ("t_deletion" → "t deletion"). */
private fun readable(key: String): String = key.replace('_', ' ')

/**
 * Right or wrong is said by [QuestionCard]'s live line; this is the explanation and where to go next (WP-21; API
 * frozen at M2, work-plan 4.5; design 9.14, spec F-17 item 3). The explanation well (galley): for "How many words?"
 * the phrase with its changed words underlined (web `Explanation`), the change chip with its practice badge, “water”
 * /ˈwɔtɚ/ → [ˈwɔɾɚ], and the change's description. Then "Again at 0.5×" (when [onAgainSlow] is given), "Open the
 * lesson", "Save" (nothing is saved unless it is tapped; "Saved" once it is), "Learn about {change}" and "Watch more
 * like this". The chip opens the change too ([onLearn]).
 */
@Composable
fun AnswerFeedback(
  state: AnswerFeedbackState,
  onLesson: () -> Unit,
  onLearn: () -> Unit,
  onWatchMore: () -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
  onAgainSlow: (() -> Unit)? = null,
) {
  val name = state.change?.name ?: readable(state.result.change.value)
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Explanation(state, name, onLearn)
    Actions(state, name, onLesson, onLearn, onWatchMore, onSave, onAgainSlow)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Explanation(state: AnswerFeedbackState, name: String, onLearn: () -> Unit) {
  val colors = PhonoTheme.colors
  val occurrence = state.result.explanation
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .background(colors.galley)
      .testTag(QuestionTags.EXPLANATION)
      .padding(PhonoSpacing.s16),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    state.phrase?.takeIf { it.isNotEmpty() }?.let { Phrase(it) }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      ChangeChip(label = capitalized(name), family = occurrence.family, onClick = onLearn)
      val advice = state.change?.advice ?: occurrence.advice
      PracticeBadge(advice, register = state.change?.register?.let { registerWords(it) })
    }
    IpaText(
      text = forms(occurrence.wordText, occurrence.dictIpa, occurrence.realizedIpa),
      style = MaterialTheme.typography.bodyLarge,
      color = colors.ink,
      modifier = Modifier.testTag(QuestionTags.FORMS),
    )
    state.change?.description?.let {
      IpaText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2, modifier = Modifier.testTag(QuestionTags.DESCRIPTION))
    }
  }
}

/**
 * "The phrase has 6 words. Underlined words changed in fast speech — the ones a learner usually misses:" and the
 * numbered words (web `Explanation`, whose dotted underline becomes a bold underline); TalkBack hears "(changed)".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Phrase(words: List<PhraseWord>) {
  val colors = PhonoTheme.colors
  Column(Modifier.testTag(QuestionTags.PHRASE), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    val count = pluralStringResource(R.plurals.feedback_phrase_words, words.size, words.size)
    val lead = stringResource(R.string.feedback_phrase_lead, count)
    PhonoText(
      text = lead,
      style = MaterialTheme.typography.bodyLarge,
      color = colors.ink,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
      val changed = stringResource(R.string.feedback_phrase_changed)
      words.forEachIndexed { index, word ->
        val number = (index + 1).toString()
        val spoken = if (word.changed) "$number. ${word.text} $changed" else "$number. ${word.text}"
        val text = buildAnnotatedString {
          withStyle(SpanStyle(color = colors.ink2, fontWeight = FontWeight.Normal)) { append(number) }
          append(" ")
          if (word.changed) {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)) { append(word.text) }
          } else {
            append(word.text)
          }
        }
        androidx.compose.material3.Text(
          text = text,
          style = MaterialTheme.typography.bodyLarge,
          color = colors.ink,
          modifier = Modifier
            .clip(RoundedCornerShape(PhonoRadius.small))
            .background(colors.sheet)
            .semantics { contentDescription = spoken }
            .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s4),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
  state: AnswerFeedbackState,
  name: String,
  onLesson: () -> Unit,
  onLearn: () -> Unit,
  onWatchMore: () -> Unit,
  onSave: () -> Unit,
  onAgainSlow: (() -> Unit)?,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    onAgainSlow?.let {
      PhonoButton(
        text = stringResource(R.string.feedback_again_slow),
        onClick = it,
        modifier = Modifier.testTag(QuestionTags.AGAIN_SLOW),
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Slow,
      )
    }
    PhonoButton(
      text = stringResource(R.string.feedback_lesson),
      onClick = onLesson,
      modifier = Modifier.testTag(QuestionTags.LESSON),
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Legend,
    )
    PhonoButton(
      text = stringResource(if (state.saved) R.string.feedback_saved else R.string.feedback_save),
      onClick = onSave,
      modifier = Modifier.testTag(QuestionTags.SAVE),
      kind = PhonoButtonKind.SECONDARY,
      enabled = !state.saved,
      leadingIcon = if (state.saved) PhonoIcons.Saved else PhonoIcons.Save,
    )
    PhonoButton(
      text = stringResource(R.string.feedback_learn, name),
      onClick = onLearn,
      modifier = Modifier.testTag(QuestionTags.LEARN),
      kind = PhonoButtonKind.QUIET,
      trailingIcon = PhonoIcons.ArrowRight,
    )
    PhonoButton(
      text = stringResource(R.string.feedback_watch_more),
      onClick = onWatchMore,
      modifier = Modifier.testTag(QuestionTags.WATCH_MORE),
      kind = PhonoButtonKind.QUIET,
      trailingIcon = PhonoIcons.ArrowRight,
    )
  }
}

/** “water” /ˈwɔtɚ/ → [ˈwɔɾɚ] (web `Explanation`); [∅] when nothing was heard; no /…/ without a dictionary form. */
@Composable
private fun forms(word: String, dictionary: String?, said: String?): String {
  val shown = displayWord(word)
  val heard = said?.takeIf(String::isNotBlank) ?: NOTHING_HEARD
  val dict = dictionary?.takeIf(String::isNotBlank)
  return if (dict == null) {
    stringResource(R.string.feedback_forms_said, shown, heard)
  } else {
    stringResource(R.string.feedback_forms, shown, dict, heard)
  }
}

@Composable
private fun registerWords(register: Register): String = stringResource(
  when (register) {
    Register.UNIVERSAL -> R.string.feedback_register_universal
    Register.CASUAL -> R.string.feedback_register_casual
    Register.MARKED -> R.string.feedback_register_marked
  },
)

/** The web's `displayWord`: the word without the punctuation around it. */
internal fun displayWord(word: String): String = word.replace(EDGE_PUNCTUATION, "").ifEmpty { word }

private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$")
private const val NOTHING_HEARD = "∅"
