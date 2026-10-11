package com.mapineda48.phonotrainer.core.designsystem.trace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One point of a trace: [x] along the line (0 to 1), [y] its height (0 = top, 1 = bottom of the drawing area; null =
 * unvoiced, which breaks the line) and [weight] the loudness that sets the ribbon's width (0 to 1).
 */
@Immutable
data class TraceSample(val x: Float, val y: Float?, val weight: Float = 0.5f)

/** Sizes of one use of the trace (design 1.2, 7.3, 9.7). */
@Immutable
data class StylusTraceStyle(
  /** Ribbon width at weight 0 and 1. */
  val minWidth: Dp,
  val maxWidth: Dp,
  /** The dotted rest: one dot of [dotSize] every [dotSpacing]. */
  val dotSpacing: Dp,
  val dotSize: Dp,
  /** Phrase boundaries. */
  val tickWidth: Dp,
  val tickHeight: Dp,
  /** The pen nib at the playhead and its 25 % halo. */
  val nibSize: Dp,
  val nibHalo: Dp,
  /** The spotlight pin: a diamond on a stem. */
  val pinSize: Dp,
  val pinStem: Dp,
  /** Room kept above and below the line inside the drawing area. */
  val padding: Dp,
) {
  companion object {
    /** The short's progress bar (design 9.7): 1.6 to 5.4 dp, dots every 5 dp, a 14 dp pin. */
    val Progress = StylusTraceStyle(1.6.dp, 5.4.dp, 5.dp, 1.dp, 1.5.dp, 10.dp, 8.dp, 12.dp, 14.dp, 1.2.dp, 4.dp)

    /** The audio-only drum (design 7.3): 3 to 11 dp, dots every 6 dp, an 8 dp nib with a 12 dp halo. */
    val Drum = StylusTraceStyle(3.dp, 11.dp, 6.dp, 1.dp, 1.5.dp, 12.dp, 8.dp, 12.dp, 14.dp, 1.2.dp, 8.dp)

    /** Route lines and bridges: an even 2.5 dp line. */
    val Route = StylusTraceStyle(2.5.dp, 2.5.dp, 5.dp, 1.dp, 1.5.dp, 8.dp, 8.dp, 12.dp, 12.dp, 1.2.dp, 2.dp)
  }
}

/** The path builder of the trace (design 12.1): runs split at gaps, ribbons from samples and widths. */
object TracePath {
  /** [samples] sorted by x, cut into runs at every unvoiced sample (y null). Runs of one point are kept. */
  fun runs(samples: List<TraceSample>): List<List<TraceSample>> {
    val out = ArrayList<List<TraceSample>>()
    var current = ArrayList<TraceSample>()
    for (s in samples.sortedBy { it.x }) {
      if (s.y == null) {
        if (current.isNotEmpty()) out += current
        current = ArrayList()
      } else {
        current += s
      }
    }
    if (current.isNotEmpty()) out += current
    return out
  }

  /** The part of [run] whose x is at most [until], ending with an interpolated point; null when none is. */
  fun played(run: List<TraceSample>, until: Float): List<TraceSample>? {
    if (run.isEmpty() || run.first().x > until) return null
    val out = run.filter { it.x <= until }.toMutableList()
    val next = run.firstOrNull { it.x > until }
    if (next != null && out.last().x < until) out += interpolate(out.last(), next, until)
    return out
  }

  /** The part of [run] whose x is at least [from], starting with an interpolated point; null when none is. */
  fun rest(run: List<TraceSample>, from: Float): List<TraceSample>? {
    if (run.isEmpty() || run.last().x < from) return null
    val after = run.filter { it.x >= from }
    val before = run.lastOrNull { it.x < from }
    return if (before != null && after.first().x > from) listOf(interpolate(before, after.first(), from)) + after else after
  }

  private fun interpolate(a: TraceSample, b: TraceSample, x: Float): TraceSample {
    val t = if (b.x == a.x) 0f else ((x - a.x) / (b.x - a.x)).coerceIn(0f, 1f)
    val ay = a.y ?: 0.5f
    val by = b.y ?: ay
    return TraceSample(x, ay + (by - ay) * t, a.weight + (b.weight - a.weight) * t)
  }

  /** The y (0 to 1) of the line at [x], or null inside a gap. */
  fun yAt(samples: List<TraceSample>, x: Float): Float? {
    for (run in runs(samples)) {
      if (run.size == 1 && run[0].x == x) return run[0].y
      if (x < run.first().x || x > run.last().x) continue
      for (i in 1 until run.size) {
        if (x <= run[i].x) return interpolate(run[i - 1], run[i], x).y
      }
      return run.last().y
    }
    return null
  }

  /**
   * The filled outline of a ribbon through [points] whose width at each point is [widths] (same size): the upper edge
   * forwards, the lower edge backwards, with round ends.
   */
  fun ribbon(points: List<Offset>, widths: List<Float>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) {
      val r = widths[0] / 2f
      path.addOval(androidx.compose.ui.geometry.Rect(points[0].x - r, points[0].y - r, points[0].x + r, points[0].y + r))
      return path
    }
    val upper = ArrayList<Offset>(points.size)
    val lower = ArrayList<Offset>(points.size)
    for (i in points.indices) {
      val prev = points[(i - 1).coerceAtLeast(0)]
      val next = points[(i + 1).coerceAtMost(points.lastIndex)]
      val dx = next.x - prev.x
      val dy = next.y - prev.y
      val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
      val nx = -dy / len
      val ny = dx / len
      val half = widths[i] / 2f
      upper += Offset(points[i].x + nx * half, points[i].y + ny * half)
      lower += Offset(points[i].x - nx * half, points[i].y - ny * half)
    }
    path.moveTo(upper[0].x, upper[0].y)
    for (p in upper.drop(1)) path.lineTo(p.x, p.y)
    for (p in lower.asReversed()) path.lineTo(p.x, p.y)
    path.close()
    // Round caps at both ends.
    val r0 = widths.first() / 2f
    val r1 = widths.last() / 2f
    path.addOval(androidx.compose.ui.geometry.Rect(points.first().x - r0, points.first().y - r0, points.first().x + r0, points.first().y + r0))
    path.addOval(androidx.compose.ui.geometry.Rect(points.last().x - r1, points.last().y - r1, points.last().x + r1, points.last().y + r1))
    return path
  }

  /** A flat line across the whole width, for a short with no measurable pitch. */
  val FLAT: List<TraceSample> = listOf(TraceSample(0f, 0.5f), TraceSample(1f, 0.5f))
}

/**
 * The signature element (design 1.2, 12.1): one continuous ink line whose width changes like a pen stroke. The part
 * up to [progress] (0 to 1) is a solid ribbon whose width follows loudness; the rest is a dotted pencil hairline;
 * unvoiced gaps break the line. [ticks] mark phrase boundaries; [pin] places the spotlight's diamond on its stem,
 * filled while [pinNow]; the nib marks the playhead. It is content that follows playback (it never animates by itself)
 * and it is invisible to accessibility: the control that hosts it carries the words.
 */
@Composable
fun StylusTrace(
  samples: List<TraceSample>,
  progress: Float,
  modifier: Modifier = Modifier,
  ticks: List<Float> = emptyList(),
  pin: Float? = null,
  pinNow: Boolean = false,
  style: StylusTraceStyle = StylusTraceStyle.Progress,
  showNib: Boolean = true,
  playedColor: Color = Color.Unspecified,
  restColor: Color = Color.Unspecified,
) {
  val colors = PhonoTheme.colors
  val ink = if (playedColor == Color.Unspecified) colors.ink else playedColor
  val muted = if (restColor == Color.Unspecified) colors.inkMuted else restColor
  val pinFill = colors.sheet
  val line = samples.ifEmpty { TracePath.FLAT }
  Canvas(modifier.clearAndSetSemantics { }) {
    drawTrace(line, progress.coerceIn(0f, 1f), ticks, pin, pinNow, style, ink, muted, pinFill, showNib)
  }
}

/** Draws the trace in this scope (shared by [StylusTrace] and the composables that draw it inside a larger picture). */
fun DrawScope.drawTrace(
  samples: List<TraceSample>,
  progress: Float,
  ticks: List<Float>,
  pin: Float?,
  pinNow: Boolean,
  style: StylusTraceStyle,
  ink: Color,
  muted: Color,
  pinFill: Color,
  showNib: Boolean = true,
) {
  val pad = style.padding.toPx()
  val top = if (pin != null) style.pinSize.toPx() + pad else pad
  val bottom = size.height - pad
  val span = (bottom - top).coerceAtLeast(1f)
  fun point(s: TraceSample) = Offset(s.x * size.width, top + (s.y ?: 0.5f) * span)
  val minW = style.minWidth.toPx()
  val maxW = style.maxWidth.toPx()

  // Phrase boundaries first, under the line.
  for (t in ticks) {
    val x = t.coerceIn(0f, 1f) * size.width
    drawLine(muted, Offset(x, bottom - style.tickHeight.toPx()), Offset(x, bottom), style.tickWidth.toPx(), StrokeCap.Round)
  }

  for (run in TracePath.runs(samples)) {
    TracePath.rest(run, progress)?.let { rest -> drawDots(rest.map(::point), style.dotSpacing.toPx(), style.dotSize.toPx(), muted) }
    TracePath.played(run, progress)?.let { played ->
      val pts = played.map(::point)
      val widths = played.map { minW + (maxW - minW) * it.weight.coerceIn(0f, 1f) }
      drawPath(TracePath.ribbon(pts, widths), ink, style = Fill)
    }
  }

  if (pin != null) {
    val x = pin.coerceIn(0f, 1f) * size.width
    val half = style.pinSize.toPx() / 2f
    val y = TracePath.yAt(samples, pin)?.let { top + it * span } ?: bottom
    drawLine(ink, Offset(x, half * 2f), Offset(x, y), style.pinStem.toPx())
    val diamond = Path().apply {
      moveTo(x, 0f)
      lineTo(x + half, half)
      lineTo(x, half * 2f)
      lineTo(x - half, half)
      close()
    }
    drawPath(diamond, if (pinNow) ink else pinFill, style = Fill)
    drawPath(diamond, ink, style = Stroke(1.5.dp.toPx()))
  }

  if (showNib) {
    TracePath.yAt(samples, progress)?.let { yFraction ->
      val center = Offset(progress * size.width, top + yFraction * span)
      drawCircle(ink.copy(alpha = ink.alpha * 0.25f), style.nibHalo.toPx() / 2f, center)
      drawCircle(ink, style.nibSize.toPx() / 2f, center)
    }
  }
}

private fun DrawScope.drawDots(points: List<Offset>, spacing: Float, dot: Float, color: Color) {
  if (points.isEmpty() || spacing <= 0f) return
  var carry = 0f
  drawCircle(color, dot / 2f, points.first())
  for (i in 1 until points.size) {
    val a = points[i - 1]
    val b = points[i]
    val len = hypot(b.x - a.x, b.y - a.y)
    var d = spacing - carry
    while (d <= len) {
      val t = d / len
      drawCircle(color, dot / 2f, Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
      d += spacing
    }
    carry = len - (d - spacing)
  }
}

/** A gentle made-up contour for previews and the audio art: a voice rising and falling, with one unvoiced gap. */
fun sampleContour(points: Int = 48, seed: Int = 7): List<TraceSample> = List(points) { i ->
  val x = i / (points - 1).toFloat()
  val gap = x in 0.46f..0.52f
  val y = 0.5f - 0.32f * sin(x * 5.2f + seed * 0.3f) * (1f - x * 0.4f)
  TraceSample(x, if (gap) null else y.coerceIn(0f, 1f), weight = 0.35f + 0.6f * sin(x * 9f + seed).let { it * it })
}

@PreviewLightDark
@Composable
private fun StylusTracePreview() {
  PhonoPreviewSurface {
    StylusTrace(sampleContour(), progress = 0.62f, ticks = listOf(0.3f, 0.7f), pin = 0.4f, pinNow = true,
      modifier = Modifier.fillMaxWidth().height(36.dp))
    StylusTrace(sampleContour(seed = 3), progress = 0.2f, pin = 0.8f, modifier = Modifier.fillMaxWidth().height(36.dp))
    StylusTrace(emptyList(), progress = 0.5f, modifier = Modifier.fillMaxWidth().height(24.dp), style = StylusTraceStyle.Route)
    StylusTrace(sampleContour(seed = 11), progress = 0.5f, style = StylusTraceStyle.Drum,
      modifier = Modifier.fillMaxWidth().height(120.dp))
  }
}
