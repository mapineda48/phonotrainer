package com.mapineda48.phonotrainer.core.designsystem.texture

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.mapineda48.phonotrainer.core.designsystem.theme.LocalPhonoColors
import kotlin.math.floor

/**
 * Paper grain (design 5.4): fractal value noise generated once from a fixed seed into a tileable 256 px tile and drawn
 * on the page with `grainAlpha`: multiply on paper (the tile is near white with darker specks) and screen on soot
 * (the inverted tile: near black with light specks). The long fibres are a second, sparser noise stretched along x, at
 * 1.2 times the alpha (Home, Welcome, tab roots; never the feed). Textures are invisible to accessibility and never
 * sit under captions, slips or marks: those paint solid page or sheet over them.
 */
object PaperGrain {
  const val TILE = 256
  private const val SEED = 1857
  private val GRAIN_CELLS = intArrayOf(64, 32, 16, 8, 4)
  private val FIBRE_CELLS = intArrayOf(32, 16)
  private const val FIBRE_STRETCH = 8
  private const val FIBRE_THRESHOLD = 0.66f

  private val grainLevels by lazy { levels(GRAIN_CELLS, stretchX = 1, sparse = false) }
  private val fibreLevels by lazy { levels(FIBRE_CELLS, stretchX = FIBRE_STRETCH, sparse = true) }

  val grainLight: ImageBitmap by lazy { bitmap(grainLevels, invert = false) }
  val grainDark: ImageBitmap by lazy { bitmap(grainLevels, invert = true) }
  val fibresLight: ImageBitmap by lazy { bitmap(fibreLevels, invert = false) }
  val fibresDark: ImageBitmap by lazy { bitmap(fibreLevels, invert = true) }

  /**
   * Grey levels of a tile (0 = black, 255 = white), [TILE] × [TILE] and tileable (every lattice wraps): white paper
   * with darker grain. [cells] are the octaves' cell sizes in pixels; [stretchX] widens the cells along x.
   */
  internal fun levels(cells: IntArray, stretchX: Int, sparse: Boolean, seed: Int = SEED): IntArray {
    val random = SeededRandom(seed)
    val lattices = cells.map { cell -> Lattice(TILE / (cell * stretchX).coerceAtMost(TILE), TILE / cell, random) }
    val out = IntArray(TILE * TILE)
    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        var value = 0f
        var amplitude = 1f
        var total = 0f
        cells.forEachIndexed { i, cell ->
          val cellX = (cell * stretchX).coerceAtMost(TILE).toFloat()
          value += amplitude * lattices[i].sample(x / cellX, y / cell.toFloat())
          total += amplitude
          amplitude *= 0.55f
        }
        val n = value / total
        val darkness = if (sparse) ((n - FIBRE_THRESHOLD) / (1f - FIBRE_THRESHOLD)).coerceIn(0f, 1f) else n
        out[y * TILE + x] = (255 - darkness * 255f).toInt().coerceIn(0, 255)
      }
    }
    return out
  }

  private fun bitmap(levels: IntArray, invert: Boolean): ImageBitmap {
    val pixels = IntArray(levels.size) { i ->
      val g = if (invert) 255 - levels[i] else levels[i]
      (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
    return Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
  }

  /** A periodic lattice of random values, interpolated smoothly. */
  private class Lattice(private val periodX: Int, private val periodY: Int, random: SeededRandom) {
    private val values = FloatArray(periodX.coerceAtLeast(1) * periodY.coerceAtLeast(1)) { random.nextFloat() }

    fun sample(gx: Float, gy: Float): Float {
      val x0 = floor(gx).toInt()
      val y0 = floor(gy).toInt()
      val fx = smooth(gx - x0)
      val fy = smooth(gy - y0)
      val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx
      val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx
      return top + (bottom - top) * fy
    }

    private fun at(x: Int, y: Int): Float {
      val px = periodX.coerceAtLeast(1)
      val py = periodY.coerceAtLeast(1)
      return values[Math.floorMod(y, py) * px + Math.floorMod(x, px)]
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
  }
}

/**
 * Draws the paper grain behind the content (design 5.4); [fibres] adds the long fibres. [enabled] false (battery
 * saver, or a surface that must stay plain) draws nothing.
 */
fun Modifier.paperGrain(fibres: Boolean = false, enabled: Boolean = true): Modifier = if (!enabled) {
  this
} else {
  composed {
    val colors = LocalPhonoColors.current
    val dark = colors.isDark
    val blend = if (dark) BlendMode.Screen else BlendMode.Multiply
    val grain = remember(dark) {
      ShaderBrush(
        ImageShader(if (dark) PaperGrain.grainDark else PaperGrain.grainLight, TileMode.Repeated, TileMode.Repeated),
      )
    }
    val fibre = remember(dark) {
      ShaderBrush(
        ImageShader(if (dark) PaperGrain.fibresDark else PaperGrain.fibresLight, TileMode.Repeated, TileMode.Repeated),
      )
    }
    val alpha = colors.grainAlpha
    drawBehind {
      drawRect(grain, alpha = alpha, blendMode = blend)
      if (fibres) drawRect(fibre, alpha = (alpha * 1.2f).coerceAtMost(1f), blendMode = blend)
    }
  }
}
