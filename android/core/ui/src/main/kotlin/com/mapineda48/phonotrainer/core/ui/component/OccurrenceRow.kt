package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.R

/** An example row (design 9.25): the occurrence and whether it is the one playing. */
@Immutable
data class OccurrenceRowState(val occurrence: Occurrence, val playing: Boolean = false)

/** Test tags of the example row. */
object OccurrenceTags {
  const val PLAY = "ui.occurrence.play"
  const val LESSON = "ui.occurrence.lesson"
}

/** An example from the learner's clips (WP-20; API frozen at M2, work-plan 4.5). Placeholder. */
@Composable
fun OccurrenceRow(
  state: OccurrenceRowState,
  onPlay: () -> Unit,
  onOpenLesson: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val word = state.occurrence.wordText
  Row(modifier = modifier) {
    PhonoButton(stringResource(R.string.occurrence_play, word), onPlay, Modifier.testTag(OccurrenceTags.PLAY))
    MarkedWordSpan(text = word, family = state.occurrence.family)
    Text(state.occurrence.clipTitle)
    PhonoButton(
      stringResource(R.string.occurrence_lesson, word),
      onOpenLesson,
      Modifier.testTag(OccurrenceTags.LESSON),
      kind = PhonoButtonKind.QUIET,
    )
  }
}

@Preview
@Composable
private fun OccurrenceRowPreview() {
  PhonoTheme { OccurrenceRow(OccurrenceRowState(PreviewData.occurrence), onPlay = {}, onOpenLesson = {}) }
}
