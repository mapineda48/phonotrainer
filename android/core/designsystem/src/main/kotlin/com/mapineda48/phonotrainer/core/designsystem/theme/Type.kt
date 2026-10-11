package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mapineda48.phonotrainer.core.designsystem.R

/**
 * The three bundled faces (design 4.1, all SIL OFL 1.1, unmodified upstream files with their licences in
 * `assets/licenses/`): Atkinson Hyperlegible Next for the whole UI, Newsreader for the display voice only (never under
 * 15 sp, never for body, labels or buttons) and Charis for every IPA string. The variable faces are instanced with
 * `FontVariation` settings (weight, and Newsreader's optical size per role).
 */
object PhonoFonts {
  private val atkinsonWeights = listOf(400, 500, 600, 700, 800)

  /** The UI face: body, captions, buttons, labels, numbers. */
  val Atkinson: FontFamily = FontFamily(
    atkinsonWeights.map { w ->
      Font(
        resId = R.font.atkinson_hyperlegible_next,
        weight = FontWeight(w),
        variationSettings = FontVariation.Settings(FontVariation.weight(w)),
      )
    },
  )

  /** The IPA face (Charis 7.000): the whole IPA string, never per-glyph fallback, and the arrows of UI text. */
  val Charis: FontFamily = FontFamily(
    Font(R.font.charis_regular, FontWeight.Normal),
    Font(R.font.charis_bold, FontWeight.Bold),
  )

  /**
   * Newsreader at one weight and optical size. Each display role has its own optical size, so each is its own
   * family of one font; equal arguments give equal families, which share one loaded typeface.
   */
  fun newsreader(weight: Int, opticalSize: Float, italic: Boolean = false): FontFamily = FontFamily(
    Font(
      resId = if (italic) R.font.newsreader_italic else R.font.newsreader,
      weight = FontWeight(weight),
      style = if (italic) FontStyle.Italic else FontStyle.Normal,
      variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize.coerceIn(NEWSREADER_OPSZ_MIN, NEWSREADER_OPSZ_MAX)),
      ),
    ),
  )

  private const val NEWSREADER_OPSZ_MIN = 6f
  private const val NEWSREADER_OPSZ_MAX = 72f
}

private val trimNone = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

private fun ui(weight: Int, size: Float, line: Float, letterSpacing: TextUnit = 0.sp) = TextStyle(
  fontFamily = PhonoFonts.Atkinson,
  fontWeight = FontWeight(weight),
  fontSize = size.sp,
  lineHeight = line.sp,
  letterSpacing = letterSpacing,
  lineHeightStyle = trimNone,
  platformStyle = noFontPadding,
)

private fun display(weight: Int, size: Float, line: Float, italic: Boolean = false, opsz: Float = size) = TextStyle(
  fontFamily = PhonoFonts.newsreader(weight, opsz, italic),
  fontWeight = FontWeight(weight),
  fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
  fontSize = size.sp,
  lineHeight = line.sp,
  lineHeightStyle = trimNone,
  platformStyle = noFontPadding,
)

private fun ipa(size: Float, line: Float) = TextStyle(
  fontFamily = PhonoFonts.Charis,
  fontWeight = FontWeight.Normal,
  fontSize = size.sp,
  lineHeight = line.sp,
  letterSpacing = 0.sp,
  lineHeightStyle = trimNone,
  platformStyle = noFontPadding,
)

/**
 * The Material 3 type scale of design 4.2. Sizes are sp and scale with the system font size (display sizes grow
 * less, through Android 14's non-linear scaling); the minimum is 14 sp everywhere. `labelSmall` is the kicker: the
 * component that draws it upper-cases its words (`Kicker`).
 */
val phonoTypography: Typography = Typography(
  displayLarge = display(650, 44f, 48f),
  displayMedium = display(650, 36f, 40f),
  displaySmall = display(600, 30f, 36f),
  headlineLarge = display(600, 28f, 34f),
  headlineMedium = display(600, 22f, 28f),
  headlineSmall = display(600, 20f, 26f),
  titleLarge = ui(700, 17f, 24f),
  titleMedium = ui(700, 16f, 22f),
  titleSmall = ui(700, 15f, 20f),
  bodyLarge = ui(400, 16f, 24f),
  bodyMedium = ui(400, 15f, 22f),
  bodySmall = ui(400, 14f, 20f),
  labelLarge = ui(600, 16f, 20f),
  labelMedium = ui(600, 14f, 18f),
  labelSmall = ui(700, 14f, 18f, letterSpacing = 0.07.em),
)

/** The styles design 4.2 adds to the Material scale (`PhonoTheme.type`). */
@Immutable
data class PhonoType(
  /** The studied word on the spotlight slip. */
  val spotlightWord: TextStyle = display(600, 34f, 40f),
  /** The word heading the lesson. */
  val lessonWord: TextStyle = display(600, 40f, 44f),
  /** The current caption phrase (in 46 dp line boxes; every word is a 48 dp target). */
  val captionCurrent: TextStyle = ui(500, 20f, 28f),
  /** The previous and next phrases (drawn in inkMuted). */
  val captionDim: TextStyle = ui(400, 15f, 21f),
  /** Datelines, step numerals, counts beside kickers. */
  val accentItalic: TextStyle = display(500, 17f, 24f, italic = true, opsz = 18f),
  /** The poster fallback's title on its paper plate (design 7.4). */
  val posterTitle: TextStyle = display(600, 15f, 20f),
  /** Dialog titles (design 9.23). */
  val dialogTitle: TextStyle = display(600, 24f, 30f),
  /** The state line of a request card (design 9.19). */
  val requestState: TextStyle = ui(800, 17f, 24f),
  /** IPA inside running text: Charis at 1.12 times the surrounding size (a span). */
  val ipaInline: SpanStyle = SpanStyle(fontFamily = PhonoFonts.Charis, fontSize = 1.12.em, letterSpacing = 0.sp),
  /** The slip's IPA line. */
  val ipaSlip: TextStyle = ipa(21f, 28f),
  /** Compare's forms. */
  val ipaForm: TextStyle = ipa(25f, 32f),
  /** The type sorts. */
  val ipaSort: TextStyle = ipa(27f, 34f),
  /** The sound card's symbol. */
  val ipaSymbol: TextStyle = ipa(40f, 48f),
) {
  companion object {
    /** Tabular figures for times, percentages and counts (design 4.2 "numeric"). */
    const val TABULAR = "tnum"
  }
}

/** [this] with tabular figures. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = PhonoType.TABULAR)

/** [this] set in Charis, the IPA face, at the same size. */
fun TextStyle.inCharis(): TextStyle = copy(fontFamily = PhonoFonts.Charis, letterSpacing = 0.sp)

val LocalPhonoType = staticCompositionLocalOf { PhonoType() }
