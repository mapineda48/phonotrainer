package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.ui.component.ClipCardCopy
import kotlin.math.tan

/** How a storage category is drawn in the meter (design 9.20): told apart by pattern, never by hue. */
enum class StoragePattern { SOLID, HATCH, DOTTED_OUTLINE, OUTLINE }

/** One category of the meter: its key (test tags), its words, its bytes and its pattern. */
@Immutable
data class StorageCategory(val key: String, val label: String, val bytes: Long, val pattern: StoragePattern)

/** The four categories of spec F-21 in the meter's order, from [usage]. */
@Composable
internal fun storageCategories(usage: StorageUsage): List<StorageCategory> = listOf(
  StorageCategory("clips", stringResource(R.string.storage_clips), usage.clipsBytes, StoragePattern.SOLID),
  StorageCategory("videos", stringResource(R.string.storage_videos), usage.videosBytes, StoragePattern.HATCH),
  StorageCategory("waiting", stringResource(R.string.storage_waiting), usage.waitingCopiesBytes, StoragePattern.DOTTED_OUTLINE),
  StorageCategory("cache", stringResource(R.string.storage_cache), usage.cacheBytes, StoragePattern.OUTLINE),
)

/** The bytes the meter's full width stands for: the budget, or (no limit) what is used plus what is free. */
internal fun meterCapacity(usage: StorageUsage): Long {
  val used = usage.clipsBytes + usage.videosBytes + usage.waitingCopiesBytes + usage.cacheBytes
  val capacity = when (val budget = usage.budget) {
    is StorageBudget.Limit -> budget.bytes
    StorageBudget.FreeSpaceOnly -> used + usage.freeBytes.coerceAtLeast(0)
  }
  return maxOf(capacity, used, 1L)
}

/**
 * The storage meter (design 9.20): one 28 dp bar whose full width is the budget, a segment per category in ink2 told
 * apart by pattern (clips solid, videos a 30° hatch, waiting to send a dotted outline, cache an outline), then the
 * table twin: each category with its swatch, its words and its size. TalkBack reads the bar once, as words.
 */
@Composable
internal fun StorageMeter(usage: StorageUsage, modifier: Modifier = Modifier) {
  val categories = storageCategories(usage)
  val resources = LocalResources.current
  val capacity = meterCapacity(usage)
  val described = categories.joinToString("; ") {
    resources.getString(R.string.storage_part, it.label, ClipCardCopy.bytes(it.bytes, resources))
  }
  val used = ClipCardCopy.bytes(categories.sumOf { it.bytes }, resources)
  val description = stringResource(R.string.storage_meter, used, described)
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(BAR_HEIGHT)
        .testTag(SettingsTags.STORAGE_METER)
        .semantics { contentDescription = description },
    ) {
      val corner = CornerRadius(4.dp.toPx())
      drawRoundRect(colors.galley, cornerRadius = corner)
      drawRoundRect(colors.outline, cornerRadius = corner, style = Stroke(1.dp.toPx()))
      val minimum = 4.dp.toPx()
      var x = 0f
      categories.filter { it.bytes > 0 }.forEach { category ->
        val width = (size.width * category.bytes / capacity.toFloat()).coerceAtLeast(minimum).coerceAtMost(size.width - x)
        if (width > 0f) {
          drawSegment(category.pattern, colors.ink2, Offset(x, 0f), Size(width, size.height))
          x += width + GAP.toPx()
        }
      }
    }
    Column(
      Modifier.fillMaxWidth().testTag(SettingsTags.STORAGE_TABLE),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
    ) {
      PhonoText(stringResource(R.string.storage_table), style = MaterialTheme.typography.labelLarge, color = colors.ink2)
      categories.forEach { category ->
        val amount = ClipCardCopy.bytes(category.bytes, resources)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTags.storageCategory(category.key))
            .clearAndSetSemantics { contentDescription = resources.getString(R.string.storage_part, category.label, amount) },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
        ) {
          Canvas(Modifier.size(SWATCH)) { drawSegment(category.pattern, colors.ink2, Offset.Zero, this.size) }
          PhonoText(category.label, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
          PhonoText(amount, style = MaterialTheme.typography.bodyLarge, color = colors.ink, textAlign = TextAlign.End)
        }
      }
    }
  }
}

/** Draws one segment at [topLeft] with [pattern] in [color]. */
private fun DrawScope.drawSegment(pattern: StoragePattern, color: Color, topLeft: Offset, size: Size) {
  val stroke = 1.5.dp.toPx()
  val inset = stroke / 2
  val corner = CornerRadius(3.dp.toPx())
  val outlineTopLeft = Offset(topLeft.x + inset, topLeft.y + inset)
  val outlineSize = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
  when (pattern) {
    StoragePattern.SOLID -> drawRoundRect(color, topLeft, size, corner)
    StoragePattern.HATCH -> {
      clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        val step = 6.dp.toPx()
        val rise = size.height / tan(Math.toRadians(HATCH_DEGREES)).toFloat()
        var start = topLeft.x - rise
        while (start < topLeft.x + size.width) {
          drawLine(color, Offset(start, topLeft.y + size.height), Offset(start + rise, topLeft.y), strokeWidth = stroke)
          start += step
        }
      }
      drawRoundRect(color, outlineTopLeft, outlineSize, corner, style = Stroke(stroke))
    }
    StoragePattern.DOTTED_OUTLINE -> drawRoundRect(
      color,
      outlineTopLeft,
      outlineSize,
      corner,
      style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
    )
    StoragePattern.OUTLINE -> drawRoundRect(color, outlineTopLeft, outlineSize, corner, style = Stroke(stroke))
  }
}

private const val HATCH_DEGREES = 30.0
private val BAR_HEIGHT = 28.dp
private val SWATCH = 18.dp
private val GAP = 2.dp
