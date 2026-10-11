package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice

/**
 * The frame of every component preview (design 12.5: `@PreviewLightDark` previews of every state): the theme of the
 * preview's night mode, the page colour behind, the screen gutter and a section gap between the states shown.
 */
@Composable
fun PhonoPreviewSurface(
  modifier: Modifier = Modifier,
  colors: ColorChoice = ColorChoice.STANDARD,
  patterns: PatternChoice = PatternChoice.FOLLOW_COLORS,
  content: @Composable ColumnScope.() -> Unit,
) {
  PhonoTheme(colors = colors, patterns = patterns) {
    Column(
      modifier = modifier.background(PhonoTheme.colors.page).padding(PhonoSpacing.gutter),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      content = content,
    )
  }
}
