package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R

/** A quiz card's state (design 9.14): the question, the chosen option id once answered, its place in the session. */
@Immutable
data class QuestionCardState(val question: Question, val chosen: String? = null, val number: Int = 1, val of: Int = 1)

/** Test tags of the quiz card and its feedback. */
object QuestionTags {
  const val PLAY = "ui.question.play"
  const val SLOW = "ui.question.slow"
  const val CONTEXT = "ui.question.context"
  const val LESSON = "ui.feedback.lesson"
  const val LEARN = "ui.feedback.learn"
  const val WATCH_MORE = "ui.feedback.watchMore"
  const val SAVE = "ui.feedback.save"

  /** The button of option [id]. */
  fun option(id: String): String = "ui.question.option.$id"
}

/** A quiz card (WP-21; API frozen at M2, work-plan 4.5): Practice and the set questions in the feed. Placeholder. */
@Composable
fun QuestionCard(
  state: QuestionCardState,
  onAnswer: (String) -> Unit,
  onPlay: () -> Unit,
  onSlow: () -> Unit,
  onContext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PhonoCard(modifier = modifier) {
    Text(stringResource(R.string.question_progress, state.number, state.of), style = MaterialTheme.typography.labelLarge)
    Text(state.question.prompt, style = MaterialTheme.typography.titleMedium)
    PhonoButton(stringResource(R.string.question_play), onPlay, Modifier.testTag(QuestionTags.PLAY))
    PhonoButton(
      stringResource(R.string.question_slow),
      onSlow,
      Modifier.testTag(QuestionTags.SLOW),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      stringResource(R.string.question_context),
      onContext,
      Modifier.testTag(QuestionTags.CONTEXT),
      kind = PhonoButtonKind.QUIET,
    )
    state.question.options.forEach { option ->
      PhonoButton(
        text = option.label,
        onClick = { onAnswer(option.id) },
        modifier = Modifier.testTag(QuestionTags.option(option.id)),
        kind = PhonoButtonKind.SECONDARY,
        enabled = state.chosen == null,
      )
    }
  }
}
