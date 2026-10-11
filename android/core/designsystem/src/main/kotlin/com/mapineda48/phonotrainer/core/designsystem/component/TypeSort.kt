package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.SortShape

/** How a sort of Compare is drawn (design 9.10). */
enum class SortState { SAME, CHANGED, DROPPED }

/**
 * A type sort (design 1.3, 9.10): a metal sort of Compare holding one sound in Charis (ipaSort), 10 dp corners and a
 * 2 dp nick under its base; at least 80 × 140 dp as a target (A-03). Selected: ink fill. [state] CHANGED draws a 2 dp
 * ink ring, DROPPED a dashed inkMuted outline and the sound at 45 %. [label] is its accessible name ("t, voiced
 * alveolar tap, changed").
 */
@Composable
fun TypeSort(
  symbol: String,
  label: String,
  modifier: Modifier = Modifier,
  state: SortState = SortState.SAME,
  selected: Boolean = false,
  caption: String? = null,
  onClick: (() -> Unit)? = null,
) {
  val colors = PhonoTheme.colors
  val fill = if (selected) colors.ink else colors.sheet
  val content = if (selected) colors.onInk else colors.ink
  Column(
    modifier = modifier
      .then(if (onClick != null) Modifier.focusRing(SortShape) else Modifier)
      .sizeIn(minWidth = 80.dp, minHeight = 140.dp)
      .clip(SortShape)
      .background(fill)
      .then(
        when (state) {
          SortState.CHANGED -> Modifier.border(2.dp, colors.ink, SortShape)
          SortState.SAME -> Modifier.border(PhonoSizes.ring, colors.lineStrong, SortShape)
          SortState.DROPPED -> Modifier
        },
      )
      .drawBehind {
        val y = size.height - 10.dp.toPx()
        // The nick under the base; for a dropped sort, the dashed outline.
        drawLine(content.copy(alpha = 0.55f), Offset(12.dp.toPx(), y), Offset(size.width - 12.dp.toPx(), y), 2.dp.toPx())
        if (state == SortState.DROPPED) {
          drawRoundRect(
            colors.inkMuted,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
              1.5.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            ),
          )
        }
      }
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
      .semantics {
        contentDescription = label
        this.selected = selected
      }
      .padding(PhonoSpacing.s12),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.CenterVertically),
  ) {
    PhonoText(
      symbol,
      style = PhonoTheme.type.ipaSort,
      color = content,
      modifier = Modifier.alpha(if (state == SortState.DROPPED) 0.45f else 1f),
    )
    caption?.let { PhonoText(it, style = MaterialTheme.typography.labelMedium, color = content) }
  }
}

@PreviewLightDark
@Composable
private fun TypeSortPreview() {
  PhonoPreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      TypeSort("w", "w, same", onClick = {})
      TypeSort("ɾ", "t, voiced alveolar tap, changed", state = SortState.CHANGED, selected = true, caption = "changed", onClick = {})
      TypeSort("d", "d, dropped", state = SortState.DROPPED, onClick = {})
    }
  }
}
