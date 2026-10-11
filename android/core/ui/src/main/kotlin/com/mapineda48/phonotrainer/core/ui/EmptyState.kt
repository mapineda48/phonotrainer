package com.mapineda48.phonotrainer.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** One action of an empty state: its label, its emphasis and what it does. */
data class EmptyStateAction(
  val label: String,
  val onClick: () -> Unit,
  val kind: PhonoButtonKind = PhonoButtonKind.PRIMARY,
)

/**
 * The empty-state scaffold of design 9.21: the illustration full width at its aspect (decorative: the words carry the
 * meaning, and `drawable-night` gives the dark twin), the title in Newsreader (displaySmall, a heading), one sentence
 * (bodyLarge) and its actions, the primary first, each at least 48 dp. Search with no result passes no illustration.
 */
@Composable
fun EmptyState(
  @DrawableRes illustration: Int?,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  actions: List<EmptyStateAction> = emptyList(),
) {
  val colors = PhonoTheme.colors
  Column(
    modifier = modifier.fillMaxWidth().padding(PhonoSpacing.gutter),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    illustration?.let { Illustration(it, Modifier.padding(bottom = PhonoSpacing.s8)) }
    PhonoText(
      title,
      style = MaterialTheme.typography.displaySmall,
      color = colors.ink,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    PhonoText(body, style = MaterialTheme.typography.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center)
    actions.forEachIndexed { index, action ->
      PhonoButton(
        text = action.label,
        onClick = action.onClick,
        kind = action.kind,
        fillWidth = index == 0 && action.kind == PhonoButtonKind.PRIMARY,
        modifier = Modifier.testTag("${UiTags.EMPTY_ACTION}.$index"),
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun EmptyStatePreview() {
  PhonoPreviewSurface {
    EmptyState(
      illustration = PhonoIllustrations.SavedEmpty,
      title = "Nothing saved yet",
      body = "Save a short to find it here.",
      actions = listOf(EmptyStateAction("Go to Watch", {}), EmptyStateAction("Learn the marks", {}, PhonoButtonKind.QUIET)),
    )
  }
}
