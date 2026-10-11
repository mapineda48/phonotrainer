package com.mapineda48.phonotrainer.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.CoachMarkSlip
import com.mapineda48.phonotrainer.core.designsystem.component.CoachPointer
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing

/** One coach mark's words (the catalogue is WP-13's) and, optionally, its icon. */
data class CoachMark(val title: String, val body: String, @DrawableRes val icon: Int? = null)

/**
 * Hosts one coach mark over [content] (design 9.23): an inverse slip with the tip, one sentence and "Got it", never
 * pausing and never on a timer; one at a time. [mark] null shows none. [alignment] places the slip over the content
 * (the bottom by default, pointing up at what it explains when [pointer] says so).
 */
@Composable
fun CoachMarkHost(
  mark: CoachMark?,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  alignment: Alignment = Alignment.BottomCenter,
  pointer: CoachPointer = CoachPointer.NONE,
  content: @Composable () -> Unit,
) {
  Box(modifier = modifier) {
    content()
    if (mark != null) {
      CoachMarkSlip(
        title = mark.title,
        body = mark.body,
        gotItLabel = stringResource(R.string.ui_coach_got_it),
        onGotIt = onDismiss,
        icon = mark.icon ?: PhonoIcons.Tip,
        pointer = pointer,
        gotItModifier = Modifier.testTag(UiTags.COACH_GOT_IT),
        modifier = Modifier.align(alignment).padding(PhonoSpacing.gutter),
      )
    }
  }
}

@PreviewLightDark
@Composable
internal fun CoachMarkHostPreview() {
  PhonoPreviewSurface {
    CoachMarkHost(
      mark = CoachMark("Tap any word for its lesson.", "Every marked word opens its lesson.", PhonoIcons.Hold),
      onDismiss = {},
      pointer = CoachPointer.TOP,
    ) { PhonoText("Captions of the short") }
  }
}
