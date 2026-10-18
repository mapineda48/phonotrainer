package com.mapineda48.phonotrainer.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/**
 * A quiz card's state (design 9.14): the question, the chosen option id once answered, and its place in the session
 * ([number] of [of], read by TalkBack with the kind; the screen draws the visible position). [lede] is the one-line
 * context under the question; null reads "From “{clip}”." when the clip has a title. [families] gives the family of
 * a "Which change?" option's change, so it wears its family icon; an option without one shows its name alone.
 */
@Immutable
data class QuestionCardState(
  val question: Question,
  val chosen: String? = null,
  val number: Int = 1,
  val of: Int = 1,
  val lede: String? = null,
  val families: Map<String, Family> = emptyMap(),
) {
  /** Whether the question is answered. */
  val answered: Boolean get() = chosen != null

  /** Whether the chosen option is the answer. */
  val correct: Boolean get() = chosen != null && chosen == question.answer

  /** The option that is the answer (the web's `answerOption`). */
  val answerOption: QuestionOption? get() = question.options.firstOrNull { it.id == question.answer }
}

/** Test tags of the quiz card and its feedback. */
object QuestionTags {
  const val CARD = "ui.question.card"
  const val KIND = "ui.question.kind"
  const val PROMPT = "ui.question.prompt"
  const val LEDE = "ui.question.lede"
  const val PLAY = "ui.question.play"
  const val SLOW = "ui.question.slow"
  const val CONTEXT = "ui.question.context"
  const val OPTIONS = "ui.question.options"
  const val LIVE = "ui.question.live"
  const val EXPLANATION = "ui.feedback.explanation"
  const val PHRASE = "ui.feedback.phrase"
  const val FORMS = "ui.feedback.forms"
  const val DESCRIPTION = "ui.feedback.description"
  const val AGAIN_SLOW = "ui.feedback.againSlow"
  const val LESSON = "ui.feedback.lesson"
  const val LEARN = "ui.feedback.learn"
  const val WATCH_MORE = "ui.feedback.watchMore"
  const val SAVE = "ui.feedback.save"

  /** The button of option [id]. */
  fun option(id: String): String = "ui.question.option.$id"

  /** The "Correct answer" / "Your answer" mark inside option [id], once answered. */
  fun optionMark(id: String): String = "ui.question.option.$id.mark"
}

/** The words and icon of each kind of question (web `KIND_TEXT`), shared by the card and the Practice start screen. */
object QuestionKindCopy {
  /** "Which pronunciation?" audio-lines, "Which change?" shapes, "How many words?" list-ordered (design 9.14). */
  @DrawableRes
  fun icon(kind: QuestionKind): Int = when (kind) {
    QuestionKind.FORM -> PhonoIcons.Track
    QuestionKind.CHANGE -> PhonoIcons.QuizWhichChange
    QuestionKind.COUNT -> PhonoIcons.ModeInOrder
  }

  @Composable
  fun title(kind: QuestionKind): String = stringResource(
    when (kind) {
      QuestionKind.FORM -> R.string.question_kind_form
      QuestionKind.CHANGE -> R.string.question_kind_change
      QuestionKind.COUNT -> R.string.question_kind_count
    },
  )

  /** The one-line explanation of the start screen (web copy). */
  @Composable
  fun explanation(kind: QuestionKind): String = stringResource(
    when (kind) {
      QuestionKind.FORM -> R.string.question_kind_form_text
      QuestionKind.CHANGE -> R.string.question_kind_change_text
      QuestionKind.COUNT -> R.string.question_kind_count_text
    },
  )
}

/**
 * A quiz card (WP-21; API frozen at M2, work-plan 4.5; design 9.14, render `07`): Practice and the set questions in
 * the feed. A stacked card: the kicker with the kind's icon, the question in headlineLarge, a one-line lede, "Play"
 * (primary), "Slowly" and "With context" (only when the question has a context); then the options in a two-column
 * radio group, each at least 56 dp tall (spec F-17 item 2), IPA in Charis.
 *
 * Once answered (web `QuestionView`): the answer gets a 3 dp ink ring, bold words and a check with "Correct answer";
 * a wrong pick a 2 dp critical ring and a cross with "Your answer"; the others dim to 70 %; and a polite live line
 * says "Correct!" or "Not quite — the answer was [ɾ]." (icon + words + outline, never colour alone). The explanation
 * and the actions are [AnswerFeedback]'s.
 */
@Composable
fun QuestionCard(
  state: QuestionCardState,
  onAnswer: (String) -> Unit,
  onPlay: () -> Unit,
  onSlow: () -> Unit,
  onContext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val question = state.question
  val kindTitle = QuestionKindCopy.title(question.kind)
  PhonoCard(modifier = modifier.testTag(QuestionTags.CARD), variant = CardVariant.STACK) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      Kicker(
        text = kindTitle,
        icon = QuestionKindCopy.icon(question.kind),
        modifier = Modifier.testTag(QuestionTags.KIND),
      )
      PhonoText(
        text = question.prompt,
        style = MaterialTheme.typography.headlineLarge,
        color = colors.ink,
        modifier = Modifier
          .testTag(QuestionTags.PROMPT)
          .semantics { heading() },
      )
      val lede = state.lede ?: question.occurrence.clipTitle.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.question_from_clip, it)
      }
      lede?.let {
        PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2, modifier = Modifier.testTag(QuestionTags.LEDE))
      }
      PlayButtons(hasContext = question.context != null, onPlay = onPlay, onSlow = onSlow, onContext = onContext)
      Options(state, onAnswer)
      if (state.answered) LiveLine(state)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayButtons(hasContext: Boolean, onPlay: () -> Unit, onSlow: () -> Unit, onContext: () -> Unit) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoButton(
      text = stringResource(R.string.question_play),
      onClick = onPlay,
      modifier = Modifier.testTag(QuestionTags.PLAY),
      leadingIcon = PhonoIcons.Play,
    )
    PhonoButton(
      text = stringResource(R.string.question_slow),
      onClick = onSlow,
      modifier = Modifier.testTag(QuestionTags.SLOW),
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Slow,
    )
    if (hasContext) {
      PhonoButton(
        text = stringResource(R.string.question_context),
        onClick = onContext,
        modifier = Modifier.testTag(QuestionTags.CONTEXT),
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Track,
      )
    }
  }
}

/** The answers in a two-column radio group (design 9.14); rows share the height of their taller cell. */
@Composable
private fun Options(state: QuestionCardState, onAnswer: (String) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().selectableGroup().testTag(QuestionTags.OPTIONS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap),
  ) {
    state.question.options.chunked(COLUMNS).forEach { pair ->
      Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap),
      ) {
        pair.forEach { option ->
          OptionCell(state, option, onAnswer, Modifier.weight(1f).fillMaxHeight())
        }
        repeat(COLUMNS - pair.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

/** How an option looks once the question is answered (web `QuestionView`; design 9.14). */
private enum class OptionLook { OPEN, ANSWER, WRONG_PICK, OTHER }

@Composable
private fun OptionCell(state: QuestionCardState, option: QuestionOption, onAnswer: (String) -> Unit, modifier: Modifier) {
  val colors = PhonoTheme.colors
  val look = when {
    !state.answered -> OptionLook.OPEN
    option.id == state.question.answer -> OptionLook.ANSWER
    option.id == state.chosen -> OptionLook.WRONG_PICK
    else -> OptionLook.OTHER
  }
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val (ring, ringWidth) = when (look) {
    OptionLook.ANSWER -> colors.ink to ANSWER_RING
    OptionLook.WRONG_PICK -> colors.critical to PICK_RING
    else -> colors.lineStrong to PhonoSizes.ring
  }
  Column(
    modifier = modifier
      .heightIn(min = PhonoSizes.quizAnswerMin)
      .focusRing(shape)
      .alpha(if (look == OptionLook.OTHER) DIMMED else 1f)
      .clip(shape)
      .background(colors.sheet)
      .border(ringWidth, ring, shape)
      .selectable(
        selected = option.id == state.chosen,
        enabled = !state.answered,
        role = Role.RadioButton,
        onClick = { onAnswer(option.id) },
      )
      .testTag(QuestionTags.option(option.id))
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    OptionWords(option, state.families[option.change?.value], bold = look == OptionLook.ANSWER)
    when (look) {
      OptionLook.ANSWER -> OptionMark(option.id, PhonoIcons.StepDone, colors.good, stringResource(R.string.question_correct_answer))
      OptionLook.WRONG_PICK -> OptionMark(option.id, PhonoIcons.Close, colors.critical, stringResource(R.string.question_your_answer))
      else -> Unit
    }
  }
}

/** An option's words: IPA in Charis, a change with its family icon, or a count. */
@Composable
private fun OptionWords(option: QuestionOption, family: Family?, bold: Boolean) {
  val colors = PhonoTheme.colors
  val weight = if (bold) FontWeight.Bold else null
  when {
    option.ipa != null -> IpaText(
      text = option.label,
      style = PhonoTheme.type.ipaForm.copy(fontWeight = weight, textAlign = TextAlign.Center),
      color = colors.ink,
      transcription = true,
    )
    option.change != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
      family?.let {
        Icon(painterResource(MarkLines.iconOf(it)), null, tint = colors.family.of(it), modifier = Modifier.size(PhonoSizes.iconButton))
      }
      PhonoText(
        text = capitalized(option.label),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = weight ?: FontWeight.Medium),
        color = colors.ink,
        textAlign = TextAlign.Center,
      )
    }
    else -> PhonoText(
      text = option.label,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = weight ?: FontWeight.Medium),
      color = colors.ink,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun OptionMark(id: String, @DrawableRes icon: Int, tint: androidx.compose.ui.graphics.Color, words: String) {
  Row(
    modifier = Modifier.testTag(QuestionTags.optionMark(id)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(words, style = MaterialTheme.typography.labelLarge, color = PhonoTheme.colors.ink)
  }
}

/** "Correct!" or "Not quite — the answer was [ɾ].", announced politely (design 9.14). */
@Composable
private fun LiveLine(state: QuestionCardState) {
  val colors = PhonoTheme.colors
  val answer = state.answerOption?.label ?: state.question.answer
  val words = if (state.correct) stringResource(R.string.question_correct) else stringResource(R.string.question_wrong, answer)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(QuestionTags.LIVE)
      .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(
      painterResource(if (state.correct) PhonoIcons.StatusReady else PhonoIcons.StatusAttention),
      contentDescription = null,
      tint = if (state.correct) colors.good else colors.critical,
      modifier = Modifier.size(PhonoSizes.iconBar),
    )
    IpaText(
      text = words,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = colors.ink,
      modifier = Modifier.weight(1f),
    )
  }
}

/** The first letter in upper case ("flapping" → "Flapping"), as the web's `capitalize`. */
internal fun capitalized(text: String): String = text.replaceFirstChar { it.uppercaseChar() }

private const val COLUMNS = 2
private const val DIMMED = 0.7f
private val ANSWER_RING: Dp = 3.dp
private val PICK_RING: Dp = 2.dp
