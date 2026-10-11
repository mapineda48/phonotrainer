package com.mapineda48.phonotrainer.core.ui.marks

import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.Family
import kotlin.math.PI
import kotlin.math.sin

/**
 * The underline vocabulary of design 9.4 (the web's, exactly): each family has its own line style, so the mark reads
 * without colour (A-01). "Show patterns" makes every line heavier and adds the family icon after the word.
 */
object MarkLines {
  /** The family's icon (design 6), drawn after a marked word when patterns are on and in every chip and legend. */
  @DrawableRes
  fun iconOf(family: Family): Int = when (family) {
    Family.REDUCTION -> PhonoIcons.FamilyReduction
    Family.TD -> PhonoIcons.FamilyTd
    Family.ASSIMILATION -> PhonoIcons.FamilyAssimilation
    Family.BOUNDARY -> PhonoIcons.FamilyBoundary
    Family.LEXICAL -> PhonoIcons.FamilyLexical
    Family.OTHER -> PhonoIcons.FamilyOther
  }

  /** The total height the underline of [family] needs below its top, in dp. */
  fun heightDp(family: Family, patterns: Boolean): Float = when (family) {
    Family.ASSIMILATION -> if (patterns) 4f else 3.5f
    Family.BOUNDARY -> 4f
    Family.LEXICAL -> 2.5f
    else -> if (patterns) 3f else 2f
  }
}

/**
 * Draws the underline of [family] from [startX] to [endX] with its top at [y] (design 9.4):
 * reduction solid; t/d dashed (6 on, 4 off); assimilation double (two 1.25 dp lines, 1 dp apart); word boundary wavy
 * (wavelength 8, amplitude 1.5); lexical contraction dotted (2.5 dp); other change dash-dot.
 */
fun DrawScope.drawFamilyLine(family: Family, color: Color, startX: Float, endX: Float, y: Float, patterns: Boolean) {
  if (endX <= startX) return
  val heavy = patterns
  when (family) {
    Family.REDUCTION -> {
      val w = (if (heavy) 3 else 2).dp.toPx()
      drawLine(color, Offset(startX, y + w / 2), Offset(endX, y + w / 2), w)
    }
    Family.TD -> {
      val w = (if (heavy) 3 else 2).dp.toPx()
      drawLine(
        color, Offset(startX, y + w / 2), Offset(endX, y + w / 2), w,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
      )
    }
    Family.ASSIMILATION -> {
      val w = (if (heavy) 1.5f else 1.25f).dp.toPx()
      val gap = 1.dp.toPx()
      drawLine(color, Offset(startX, y + w / 2), Offset(endX, y + w / 2), w)
      drawLine(color, Offset(startX, y + w + gap + w / 2), Offset(endX, y + w + gap + w / 2), w)
    }
    Family.BOUNDARY -> {
      val w = (if (heavy) 2f else 1.5f).dp.toPx()
      val amplitude = 1.5.dp.toPx()
      val wavelength = 8.dp.toPx()
      val mid = y + amplitude + w / 2
      val path = Path().apply {
        moveTo(startX, mid)
        var x = startX
        val step = wavelength / 8f
        while (x < endX) {
          x = (x + step).coerceAtMost(endX)
          lineTo(x, mid - amplitude * sin(2f * PI.toFloat() * (x - startX) / wavelength))
        }
      }
      drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round))
    }
    Family.LEXICAL -> {
      val d = 2.5.dp.toPx()
      drawLine(
        color, Offset(startX + d / 2, y + d / 2), Offset(endX, y + d / 2), d,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx())),
      )
    }
    Family.OTHER -> {
      val w = (if (heavy) 3 else 2).dp.toPx()
      drawLine(
        color, Offset(startX, y + w / 2), Offset(endX, y + w / 2), w,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx(), 1.5.dp.toPx(), 3.dp.toPx())),
      )
    }
  }
}

/**
 * The ‿ link of a word boundary (design 9.4): a small arc (13 × 7 dp, 2 dp) under the gap between two linked words,
 * centred on [centerX] with its top at [y], in the boundary hue (gated 3:1).
 */
fun DrawScope.drawLinkArc(color: Color, centerX: Float, y: Float) {
  val w = 13.dp.toPx()
  val h = 7.dp.toPx()
  val stroke = 2.dp.toPx()
  val path = Path().apply {
    moveTo(centerX - w / 2, y)
    quadraticTo(centerX, y + h * 2f, centerX + w / 2, y)
  }
  drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
}
