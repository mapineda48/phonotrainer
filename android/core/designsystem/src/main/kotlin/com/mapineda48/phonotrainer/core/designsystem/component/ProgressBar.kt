package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular

/**
 * A progress bar (design 9.1): a 6 dp galley track with a hairline ring and an ink fill; the [label] at the start and
 * the [value] at the end (tabular figures). [progress] null is indeterminate: a third-width ink segment slides while
 * motion is allowed, and stays still otherwise (the words then say it is working).
 */
@Composable
fun PhonoProgressBar(
  progress: Float?,
  modifier: Modifier = Modifier,
  label: String? = null,
  value: String? = null,
) {
  val colors = PhonoTheme.colors
  val reduced = PhonoTheme.motion.reduced
  val slide = if (progress == null && !reduced) {
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val x by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(INDETERMINATE_MS, easing = LinearEasing), RepeatMode.Restart),
      label = "slide",
    )
    x
  } else {
    0f
  }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .semantics {
        progressBarRangeInfo = if (progress == null) {
          ProgressBarRangeInfo.Indeterminate
        } else {
          ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        }
      },
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    if (label != null || value != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PhonoText(label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.ink2, modifier = Modifier.weight(1f))
        value?.let { PhonoText(it, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.ink) }
      }
    }
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
      val radius = CornerRadius(size.height / 2f)
      drawRoundRect(colors.galley, cornerRadius = radius)
      drawRoundRect(colors.hairline, cornerRadius = radius, style = Stroke(1.dp.toPx()))
      if (progress != null) {
        val w = size.width * progress.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(colors.ink, size = Size(w, size.height), cornerRadius = radius)
      } else {
        val w = size.width / 3f
        val x = (size.width + w) * slide - w
        drawRoundRect(
          colors.ink,
          topLeft = Offset(x.coerceAtLeast(0f), 0f),
          size = Size((w + x.coerceAtMost(0f)).coerceAtMost(size.width - x.coerceAtLeast(0f)), size.height),
          cornerRadius = radius,
        )
      }
    }
  }
}

private const val INDETERMINATE_MS = 1400

@PreviewLightDark
@Composable
internal fun ProgressBarPreview() {
  PhonoPreviewSurface {
    PhonoProgressBar(0.42f, label = "Bringing 2 clips to this phone", value = "34 MB of 61 MB")
    PhonoProgressBar(1f, label = "Storage", value = "1.2 GB")
    PhonoProgressBar(null, label = "Syncing…")
  }
}
