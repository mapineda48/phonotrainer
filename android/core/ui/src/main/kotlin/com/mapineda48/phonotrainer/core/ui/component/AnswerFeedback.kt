package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R

/** The feedback after an answer (design 9.14); [saved] reflects whether the example is saved. */
@Immutable
data class AnswerFeedbackState(val result: AnswerResult, val saved: Boolean = false)

/** Right or wrong, the explanation, and where to go next (WP-21; API frozen at M2, work-plan 4.5). Placeholder. */
@Composable
fun AnswerFeedback(
  state: AnswerFeedbackState,
  onLesson: () -> Unit,
  onLearn: () -> Unit,
  onWatchMore: () -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PhonoCard(modifier = modifier) {
    Text(
      text = if (state.result.correct) {
        stringResource(R.string.feedback_right)
      } else {
        stringResource(R.string.feedback_wrong, state.result.answer)
      },
      style = MaterialTheme.typography.titleMedium,
    )
    PhonoButton(stringResource(R.string.feedback_lesson), onLesson, Modifier.testTag(QuestionTags.LESSON))
    PhonoButton(
      stringResource(R.string.feedback_learn),
      onLearn,
      Modifier.testTag(QuestionTags.LEARN),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      stringResource(R.string.feedback_watch_more),
      onWatchMore,
      Modifier.testTag(QuestionTags.WATCH_MORE),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      stringResource(R.string.feedback_save),
      onSave,
      Modifier.testTag(QuestionTags.SAVE),
      kind = PhonoButtonKind.QUIET,
      enabled = !state.saved,
    )
  }
}

@Preview
@Composable
private fun AnswerFeedbackPreview() {
  PhonoTheme {
    AnswerFeedback(
      AnswerFeedbackState(AnswerResult(false, "flapping", PreviewData.occurrence.change, PreviewData.occurrence)),
      onLesson = {},
      onLearn = {},
      onWatchMore = {},
      onSave = {},
    )
  }
}
