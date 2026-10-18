package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Items in lines that wrap, each line's items centred on its height, and an intrinsic height that is the height it
 * really takes. Compose's `FlowRow` breaks its lines on the items' *minimum* intrinsic widths when asked for its
 * intrinsic height (a button "With context" counts as wide as "context"), so it claims fewer lines than it lays out;
 * inside the design system's cards, which take their content's intrinsic height and clip to it, that cut the last
 * controls off the quiz card and the results on the managed device. This one breaks on the widths the items are
 * measured at (their maximum intrinsic width, at most the line), in measurement and in intrinsics alike.
 */
@Composable
fun IntrinsicFlowRow(
  modifier: Modifier = Modifier,
  horizontalSpacing: Dp = 0.dp,
  verticalSpacing: Dp = 0.dp,
  content: @Composable () -> Unit,
) {
  Layout(content = content, modifier = modifier, measurePolicy = FlowPolicy(horizontalSpacing, verticalSpacing))
}

private class FlowPolicy(private val horizontal: Dp, private val vertical: Dp) : MeasurePolicy {
  override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
    val gapX = horizontal.roundToPx()
    val gapY = vertical.roundToPx()
    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
    val lines = lines(placeables.map { it.width }, constraints.maxWidth, gapX)
    val heights = lines.map { line -> line.maxOf { placeables[it].height } }
    val widths = lines.map { line -> line.sumOf { placeables[it].width } + gapX * (line.size - 1) }
    val width = (widths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (heights.sum() + gapY * (lines.size - 1).coerceAtLeast(0)).coerceIn(constraints.minHeight, constraints.maxHeight)
    return layout(width, height) {
      var y = 0
      lines.forEachIndexed { i, line ->
        var x = 0
        line.forEach { index ->
          val placeable = placeables[index]
          placeable.placeRelative(x, y + (heights[i] - placeable.height) / 2)
          x += placeable.width + gapX
        }
        y += heights[i] + gapY
      }
    }
  }

  override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
    intrinsicHeight(measurables, width)

  override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
    intrinsicHeight(measurables, width)

  override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
    measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

  override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
    measurables.sumOf { it.maxIntrinsicWidth(height) } + horizontal.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

  private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
    val gapX = horizontal.roundToPx()
    val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width) }
    val lines = lines(widths, width, gapX)
    val heights = lines.map { line -> line.maxOf { measurables[it].minIntrinsicHeight(widths[it]) } }
    return heights.sum() + vertical.roundToPx() * (lines.size - 1).coerceAtLeast(0)
  }

  /** The items' indices in lines: an item goes on the next line when it does not fit after the line's last one. */
  private fun lines(widths: List<Int>, maxWidth: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { index, width ->
      val line = lines.lastOrNull()
      if (line == null || used + gap + width > maxWidth) {
        lines += mutableListOf(index)
        used = width
      } else {
        line += index
        used += gap + width
      }
    }
    return lines
  }
}
