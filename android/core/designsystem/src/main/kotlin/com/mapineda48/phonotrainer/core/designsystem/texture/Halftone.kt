package com.mapineda48.phonotrainer.core.designsystem.texture

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Where a halftone sits: the whole area, or one corner it fades out from (design 5.4). */
enum class HalftoneArea { FULL, CORNER_TOP_END, CORNER_BOTTOM_END, CORNER_TOP_START }

/** One dot of a halftone, in pixels. */
@Immutable
data class HalftoneDot(val x: Float, val y: Float, val radius: Float)

/**
 * The spectrogram halftone (design 5.4, 7.3, 7.4): a dot screen whose radius follows an energy field of three
 * formant bands drifting across the width, gated by syllables, seeded per clip (FNV-1a 32 of the job id) or per
 * screen. Never under text, icons or family marks; invisible to accessibility.
 */
object Halftone {
  /**
   * The dots of a [width] × [height] px area with cells of [cell] px. [gates] are syllable spans as fractions of the
   * width (the phrase's word timings); null makes them up from [seed].
   */
  fun dots(
    width: Float,
    height: Float,
    cell: Float,
    seed: Int,
    area: HalftoneArea = HalftoneArea.FULL,
    gates: List<ClosedFloatingPointRange<Float>>? = null,
  ): List<HalftoneDot> {
    if (width <= 0f || height <= 0f || cell <= 0f) return emptyList()
    val random = SeededRandom(seed)
    val bands = List(3) { i ->
      Band(
        center = 0.22f + i * 0.27f + (random.nextFloat() - 0.5f) * 0.1f,
        sway = 0.05f + random.nextFloat() * 0.08f,
        frequency = 0.6f + random.nextFloat() * 1.4f,
        phase = random.nextFloat(),
        width = 0.07f + random.nextFloat() * 0.06f,
      )
    }
    val spans = gates ?: madeUpGates(random)
    val out = ArrayList<HalftoneDot>()
    val cols = (width / cell).toInt().coerceAtLeast(1)
    val rows = (height / cell).toInt().coerceAtLeast(1)
    for (r in 0 until rows) {
      for (c in 0 until cols) {
        val cx = (c + 0.5f) * cell
        val cy = (r + 0.5f) * cell
        val fx = cx / width
        val fy = cy / height
        var energy = 0f
        for (band in bands) {
          val y = band.center + band.sway * sin(2f * PI.toFloat() * (fx * band.frequency + band.phase))
          val d = (fy - y) / band.width
          energy = maxOf(energy, exp(-d * d))
        }
        energy *= gate(fx, spans) * mask(fx, fy, area)
        val radius = 0.46f * cell * energy.coerceIn(0f, 1f).pow(0.8f)
        if (radius >= MIN_RADIUS_FRACTION * cell) out += HalftoneDot(cx, cy, radius)
      }
    }
    return out
  }

  private const val MIN_RADIUS_FRACTION = 0.06f

  private data class Band(val center: Float, val sway: Float, val frequency: Float, val phase: Float, val width: Float)

  private fun madeUpGates(random: SeededRandom): List<ClosedFloatingPointRange<Float>> {
    val spans = ArrayList<ClosedFloatingPointRange<Float>>()
    var x = random.nextFloat() * 0.05f
    while (x < 1f) {
      val length = 0.06f + random.nextFloat() * 0.12f
      spans += x..(x + length).coerceAtMost(1f)
      x += length + 0.02f + random.nextFloat() * 0.06f
    }
    return spans
  }

  /** 1 inside a syllable, fading to 0.2 between syllables. */
  private fun gate(fx: Float, spans: List<ClosedFloatingPointRange<Float>>): Float {
    if (spans.isEmpty()) return 1f
    var best = 0.2f
    for (s in spans) {
      val edge = 0.015f
      val v = when {
        fx in s -> 1f
        fx < s.start -> 1f - ((s.start - fx) / edge)
        else -> 1f - ((fx - s.endInclusive) / edge)
      }
      best = maxOf(best, v.coerceIn(0.2f, 1f))
    }
    return best
  }

  private fun mask(fx: Float, fy: Float, area: HalftoneArea): Float {
    val (ox, oy) = when (area) {
      HalftoneArea.FULL -> return 1f
      HalftoneArea.CORNER_TOP_END -> 1f to 0f
      HalftoneArea.CORNER_BOTTOM_END -> 1f to 1f
      HalftoneArea.CORNER_TOP_START -> 0f to 0f
    }
    val d = hypot(fx - ox, fy - oy) / 0.9f
    return (1f - d).coerceIn(0f, 1f)
  }
}

/**
 * Draws the halftone of [seed] behind the content in [color] (`halftone` or `halftoneStrong` of `PhonoTheme.colors`,
 * whose alpha is the texture's strength). Computed once per size and seed; one path per draw.
 */
fun Modifier.halftone(
  seed: Int,
  color: Color,
  cell: Dp = 8.dp,
  area: HalftoneArea = HalftoneArea.FULL,
  gates: List<ClosedFloatingPointRange<Float>>? = null,
): Modifier = drawWithCache {
  val dots = Halftone.dots(size.width, size.height, cell.toPx(), seed, area, gates)
  val path = Path().apply { dots.forEach { addOval(Rect(it.x - it.radius, it.y - it.radius, it.x + it.radius, it.y + it.radius)) } }
  onDrawBehind { drawPath(path, color) }
}
