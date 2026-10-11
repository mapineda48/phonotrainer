package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The colour maths of `design-src/tools/check_palette.py` (culori 4.0.2, the library of the web's palette test),
 * ported for the JVM gate test: WCAG luminance and contrast, OKLab / OKLCH with culori's sign-preserving transfer
 * function, the OKLab mix of the tints, source-over compositing in gamma-encoded sRGB, and the Machado 2009
 * colour-vision simulations at severity 1.0 and 0.6.
 */
data class Rgb(val r: Double, val g: Double, val b: Double) {
  val hex: String get() = "#%02x%02x%02x".format(ch(r), ch(g), ch(b))

  private fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255).let { kotlin.math.round(it).toInt() }

  companion object {
    fun of(color: Color): Rgb = Rgb(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

    fun hex(value: String): Rgb {
      val v = value.trim().removePrefix("#")
      require(v.length == 6) { "not #rrggbb: $value" }
      return Rgb(v.substring(0, 2).toInt(16) / 255.0, v.substring(2, 4).toInt(16) / 255.0, v.substring(4, 6).toInt(16) / 255.0)
    }
  }
}

data class Lab(val l: Double, val a: Double, val b: Double)

object ColorMath {
  private fun toLinear(c: Double): Double {
    val a = abs(c)
    return if (a <= 0.04045) c / 12.92 else (if (c != 0.0) sign(c) else 1.0) * ((a + 0.055) / 1.055).pow(2.4)
  }

  private fun toGamma(c: Double): Double {
    val a = abs(c)
    return if (a > 0.0031308) (if (c != 0.0) sign(c) else 1.0) * (1.055 * a.pow(1 / 2.4) - 0.055) else c * 12.92
  }

  fun luminance(c: Rgb): Double = 0.2126 * toLinear(c.r) + 0.7152 * toLinear(c.g) + 0.0722 * toLinear(c.b)

  fun contrast(a: Rgb, b: Rgb): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  fun oklab(c: Rgb): Lab {
    val r = toLinear(c.r)
    val g = toLinear(c.g)
    val b = toLinear(c.b)
    val l = cbrt(0.412221469470763 * r + 0.5363325372617348 * g + 0.0514459932675022 * b)
    val m = cbrt(0.2119034958178252 * r + 0.6806995506452344 * g + 0.1073969535369406 * b)
    val s = cbrt(0.0883024591900564 * r + 0.2817188391361215 * g + 0.6299787016738222 * b)
    return Lab(
      0.210454268309314 * l + 0.7936177747023054 * m - 0.0040720430116193 * s,
      1.9779985324311684 * l - 2.4285922420485799 * m + 0.450593709617411 * s,
      0.0259040424655478 * l + 0.7827717124575296 * m - 0.8086757549230774 * s,
    )
  }

  fun fromOklab(lab: Lab): Rgb {
    val l = (lab.l + 0.3963377773761749 * lab.a + 0.2158037573099136 * lab.b).pow(3)
    val m = (lab.l - 0.1055613458156586 * lab.a - 0.0638541728258133 * lab.b).pow(3)
    val s = (lab.l - 0.0894841775298119 * lab.a - 1.2914855480194092 * lab.b).pow(3)
    return Rgb(
      toGamma(4.0767416360759574 * l - 3.3077115392580616 * m + 0.2309699031821044 * s),
      toGamma(-1.2684379732850317 * l + 2.6097573492876887 * m - 0.3413193760026573 * s),
      toGamma(-0.0041960761386756 * l - 0.7034186179359362 * m + 1.7076146940746117 * s),
    )
  }

  /** OKLCH lightness and chroma. */
  fun lightness(c: Rgb): Double = oklab(c).l

  fun chroma(c: Rgb): Double = oklab(c).let { hypot(it.a, it.b) }

  fun hue(c: Rgb): Double = oklab(c).let { (Math.toDegrees(atan2(it.b, it.a)) + 360) % 360 }

  fun deltaEok(x: Rgb, y: Rgb): Double {
    val a = oklab(x)
    val b = oklab(y)
    return 100 * sqrt((a.l - b.l).pow(2) + (a.a - b.a).pow(2) + (a.b - b.b).pow(2))
  }

  /** culori interpolate([base, color], "oklab")(share), back to sRGB, unclamped. */
  fun mix(base: Rgb, color: Rgb, share: Double): Rgb {
    val x = oklab(base)
    val y = oklab(color)
    return fromOklab(Lab(x.l + (y.l - x.l) * share, x.a + (y.a - x.a) * share, x.b + (y.b - x.b) * share))
  }

  /** Source-over compositing in gamma-encoded sRGB, as Android and browsers blend. */
  fun over(fg: Rgb, alpha: Double, bg: Rgb): Rgb =
    Rgb(fg.r * alpha + bg.r * (1 - alpha), fg.g * alpha + bg.g * (1 - alpha), fg.b * alpha + bg.b * (1 - alpha))

  enum class Deficiency { PROTAN, DEUTAN, TRITAN }

  // Machado 2009, severity 0.0 to 1.0 in steps of 0.1 (culori src/deficiency.js); only 0.6 and 1.0 are used.
  private val prot = mapOf(
    6 to doubleArrayOf(0.38545, 0.769005, -0.154455, 0.100526, 0.829802, 0.069673, -0.007442, -0.02219, 1.029632),
    10 to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
  )
  private val deuter = mapOf(
    6 to doubleArrayOf(0.498864, 0.674741, -0.173604, 0.205199, 0.754872, 0.039929, -0.011131, 0.030969, 0.980162),
    10 to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881),
  )
  private val trit = mapOf(
    6 to doubleArrayOf(1.104996, -0.046633, -0.058363, -0.032137, 0.971635, 0.060503, 0.001336, 0.317922, 0.680742),
    10 to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.3039),
  )

  /** culori's filterDeficiency*, applied to gamma-encoded sRGB without clamping. */
  fun simulate(kind: Deficiency, severity: Double, c: Rgb): Rgb {
    val step = kotlin.math.round(severity.coerceIn(0.0, 1.0) / 0.1).toInt()
    val table = when (kind) {
      Deficiency.PROTAN -> prot
      Deficiency.DEUTAN -> deuter
      Deficiency.TRITAN -> trit
    }
    val m = requireNotNull(table[step]) { "severity $severity is not tabulated here" }
    return Rgb(
      m[0] * c.r + m[1] * c.g + m[2] * c.b,
      m[3] * c.r + m[4] * c.g + m[5] * c.b,
      m[6] * c.r + m[7] * c.g + m[8] * c.b,
    )
  }
}
