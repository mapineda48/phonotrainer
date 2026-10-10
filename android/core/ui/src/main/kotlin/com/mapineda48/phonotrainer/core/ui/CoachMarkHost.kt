package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** One coach mark's words (the catalogue is WP-13's). */
data class CoachMark(val title: String, val body: String)

/**
 * Hosts one coach mark over [content] (design 9.23): an inverse slip with "Got it", never pausing and never on a
 * timer. [mark] null shows none.
 */
@Composable
fun CoachMarkHost(
  mark: CoachMark?,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Box(modifier = modifier) {
    content()
    if (mark != null) {
      Surface(color = MaterialTheme.colorScheme.inverseSurface) {
        Column {
          Text(mark.title, style = MaterialTheme.typography.titleSmall)
          Text(mark.body, style = MaterialTheme.typography.bodyMedium)
          PhonoButton(
            text = stringResource(R.string.ui_coach_got_it),
            onClick = onDismiss,
            kind = PhonoButtonKind.QUIET,
            modifier = Modifier.testTag(UiTags.COACH_GOT_IT),
          )
        }
      }
    }
  }
}

@Preview
@Composable
private fun CoachMarkHostPreview() {
  PhonoTheme {
    CoachMarkHost(mark = CoachMark("Tap a word", "Every marked word opens its lesson."), onDismiss = {}) { Text("Feed") }
  }
}
