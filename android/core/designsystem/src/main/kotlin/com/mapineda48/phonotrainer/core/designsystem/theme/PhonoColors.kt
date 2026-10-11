package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.Family

/**
 * Roles Material 3 has no slot for (architecture 17, design 2.1 and 12.1): the neutral tokens, the four family hues of
 * the chosen palette with their tints, the articulator's tract palette and the brand tones of illustrations, the audio
 * drum and the poster fallback. Every value comes from `Palette.kt`, generated from `design-src/palette.json`.
 *
 * Hue is reserved for the families (A-02): chrome is ink and paper, [good] and [critical] appear only as icons or
 * outlines next to words, and family marks sit only on [page] or [sheet] (design 2.3 rule 1).
 */
@Immutable
data class PhonoColors(
  /** Screen ground; carries the paper grain. */
  val page: Color,
  /** Cards, slips, sheets, dialogs, bars. */
  val sheet: Color,
  /** Chips, wells, tracks, notices, badges. Never under a family mark. */
  val galley: Color,
  /** Text, icons, the primary fill, the selected fill, the trace. */
  val ink: Color,
  /** Secondary text, ledes, data series. */
  val ink2: Color,
  /** Tertiary text, dimmed caption phrases, the unplayed trace. */
  val inkMuted: Color,
  /** Text and icons on ink fills. */
  val onInk: Color,
  /** Dividers and card rings. */
  val hairline: Color,
  /** The 3 dp focus ring (keyboard, D-pad, switch access). */
  val focus: Color,
  /** Check icons beside a word; never text, never a fill. */
  val good: Color,
  /** Alert icons, danger outlines, the invalid-field ring; never text, never a fill. */
  val critical: Color,
  /** Behind sheets and dialogs, with its alpha applied. */
  val scrim: Color,
  /** Pressed states, the selected-row wash, rails behind content. */
  val deepSheet: Color = page,
  /** Essential control boundaries (fields, switches, radios, segmented controls): at least 3:1 everywhere. */
  val outline: Color = inkMuted,
  /** Rings of secondary buttons and chips. */
  val lineStrong: Color = hairline,
  /** Snackbars, coach marks, tooltips. */
  val inverseSurface: Color = ink,
  val inverseOnSurface: Color = onInk,
  /** Behind video that does not fill its box. */
  val letterbox: Color = Color(0xFF0E0D0C),
  /** Illustration fills only, never next to marks. */
  val kraft: Color = galley,
  val kraft2: Color = deepSheet,
  /** Spectrogram textures (alpha applied); never under text. */
  val halftone: Color = ink.copy(alpha = 0.11f),
  val halftoneStrong: Color = ink.copy(alpha = 0.2f),
  /** Opacity of the paper grain on [page]. */
  val grainAlpha: Float = 0.06f,
  /** The generated audio-only visual's paper, chosen per clip (design 7.3). */
  val drumPapers: List<Color> = listOf(page),
  /** The poster fallback's ground, chosen per clip (design 7.4). */
  val posterPapers: List<Color> = listOf(page),
  /** The family hues of the chosen palette and their tints. */
  val family: FamilyColors = FamilyColors.monochrome(ink, inkMuted, galley),
  /** The articulator's palette (design 2.4). */
  val tract: TractColors = TractColors.light(),
  /** True for "Scratch on soot". */
  val isDark: Boolean = false,
) {
  /** [scrim] is the colour with its alpha; this is the opaque base, for components that set their own alpha. */
  val scrimOpaque: Color get() = scrim.copy(alpha = 1f)

  /** An overlay plate over video or posters: [sheet] at 94 % (gate G9). */
  val overlayPlate: Color get() = sheet.copy(alpha = OVERLAY_PLATE_ALPHA)

  /** The coach-mark slip over media: [inverseSurface] at 96 % (gate G9). */
  val coachPlate: Color get() = inverseSurface.copy(alpha = COACH_PLATE_ALPHA)

  /** The paper of the drum for a clip whose hash is [hash] (FNV-1a 32 of the job id, design 7.3). */
  fun drumPaper(hash: Int): Color = drumPapers[Math.floorMod(hash, drumPapers.size)]

  /** The poster fallback's paper for a clip whose hash is [hash] (design 7.4). */
  fun posterPaper(hash: Int): Color = posterPapers[Math.floorMod(hash, posterPapers.size)]

  companion object {
    const val OVERLAY_PLATE_ALPHA: Float = 0.94f
    const val COACH_PLATE_ALPHA: Float = 0.96f

    /** The 45 % opacity of disabled content (design 9). */
    const val DISABLED_ALPHA: Float = 0.45f
  }
}

/**
 * The four family hues (design 2.3) of one palette and theme, plus the two families drawn in ink: lexical contraction
 * ([lexical] = ink) and other change ([other] = inkMuted). Tints fill chips and the gap box; inside a tinted chip the
 * icon and the name stay ink and the hue is the ring.
 */
@Immutable
data class FamilyColors(
  val reduction: Color,
  val td: Color,
  val assimilation: Color,
  val boundary: Color,
  val lexical: Color,
  val other: Color,
  val reductionTint: Color,
  val tdTint: Color,
  val assimilationTint: Color,
  val boundaryTint: Color,
  /** The fill of a chip of a family drawn in ink (lexical, other): galley. */
  val neutralTint: Color,
) {
  /** The mark colour of [family]. */
  fun of(family: Family): Color = when (family) {
    Family.REDUCTION -> reduction
    Family.TD -> td
    Family.ASSIMILATION -> assimilation
    Family.BOUNDARY -> boundary
    Family.LEXICAL -> lexical
    Family.OTHER -> other
  }

  /** The chip fill of [family]. */
  fun tintOf(family: Family): Color = when (family) {
    Family.REDUCTION -> reductionTint
    Family.TD -> tdTint
    Family.ASSIMILATION -> assimilationTint
    Family.BOUNDARY -> boundaryTint
    Family.LEXICAL, Family.OTHER -> neutralTint
  }

  companion object {
    internal fun monochrome(ink: Color, muted: Color, fill: Color) =
      FamilyColors(ink, ink, ink, ink, ink, muted, fill, fill, fill, fill, fill)
  }
}

/** The articulator's tract palette (design 2.4): anatomy told apart by lightness and outline, never a family hue. */
@Immutable
data class TractColors(
  val air: Color,
  val tissue: Color,
  val bone: Color,
  val tongue: Color,
  val tongueDeep: Color,
  val lip: Color,
  val line: Color,
  val flow: Color,
  val accent: Color,
) {
  companion object {
    fun light() = with(Tract.Light) { TractColors(air, tissue, bone, tongue, tongueDeep, lip, line, flow, accent) }

    fun dark() = with(Tract.Dark) { TractColors(air, tissue, bone, tongue, tongueDeep, lip, line, flow, accent) }
  }
}

/** The family hues of [choice] in the light or dark theme. */
fun familyColors(darkTheme: Boolean, choice: ColorChoice): FamilyColors {
  val ink = if (darkTheme) Neutrals.Dark.ink else Neutrals.Light.ink
  val muted = if (darkTheme) Neutrals.Dark.inkMuted else Neutrals.Light.inkMuted
  val galley = if (darkTheme) Neutrals.Dark.galley else Neutrals.Light.galley
  return when (choice) {
    ColorChoice.STANDARD -> if (darkTheme) {
      with(FamilyPalette.Standard.Dark) {
        FamilyColors(
          reduction, td, assimilation, boundary, ink, muted,
          reductionTint, tdTint, assimilationTint, boundaryTint, galley,
        )
      }
    } else {
      with(FamilyPalette.Standard.Light) {
        FamilyColors(
          reduction, td, assimilation, boundary, ink, muted,
          reductionTint, tdTint, assimilationTint, boundaryTint, galley,
        )
      }
    }
    ColorChoice.COLOR_VISION_FRIENDLY -> if (darkTheme) {
      with(FamilyPalette.Cvd.Dark) {
        FamilyColors(
          reduction, td, assimilation, boundary, ink, muted,
          reductionTint, tdTint, assimilationTint, boundaryTint, galley,
        )
      }
    } else {
      with(FamilyPalette.Cvd.Light) {
        FamilyColors(
          reduction, td, assimilation, boundary, ink, muted,
          reductionTint, tdTint, assimilationTint, boundaryTint, galley,
        )
      }
    }
  }
}

/** Every role of the theme for [darkTheme] and the palette [choice] (design 2.1 to 2.4). */
fun phonoColors(darkTheme: Boolean, choice: ColorChoice = ColorChoice.STANDARD): PhonoColors =
  if (darkTheme) darkColors(familyColors(true, choice)) else lightColors(familyColors(false, choice))

private fun lightColors(family: FamilyColors): PhonoColors = with(Neutrals.Light) {
  PhonoColors(
    page = page,
    sheet = sheet,
    galley = galley,
    ink = ink,
    ink2 = ink2,
    inkMuted = inkMuted,
    onInk = onInk,
    hairline = hairline,
    focus = focus,
    good = good,
    critical = critical,
    scrim = scrim.copy(alpha = Neutrals.Light.SCRIM_ALPHA),
    deepSheet = deepSheet,
    outline = outline,
    lineStrong = lineStrong,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    letterbox = letterbox,
    kraft = Brand.Light.kraft,
    kraft2 = Brand.Light.kraft2,
    halftone = Brand.Light.halftone.copy(alpha = Brand.Light.HALFTONE_ALPHA),
    halftoneStrong = Brand.Light.halftoneStrong.copy(alpha = Brand.Light.HALFTONE_STRONG_ALPHA),
    grainAlpha = Brand.Light.GRAIN_ALPHA,
    drumPapers = Brand.Light.drumPapers,
    posterPapers = Brand.Light.posterPapers,
    family = family,
    tract = TractColors.light(),
    isDark = false,
  )
}

private fun darkColors(family: FamilyColors): PhonoColors = with(Neutrals.Dark) {
  PhonoColors(
    page = page,
    sheet = sheet,
    galley = galley,
    ink = ink,
    ink2 = ink2,
    inkMuted = inkMuted,
    onInk = onInk,
    hairline = hairline,
    focus = focus,
    good = good,
    critical = critical,
    scrim = scrim.copy(alpha = Neutrals.Dark.SCRIM_ALPHA),
    deepSheet = deepSheet,
    outline = outline,
    lineStrong = lineStrong,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    letterbox = letterbox,
    kraft = Brand.Dark.kraft,
    kraft2 = Brand.Dark.kraft2,
    halftone = Brand.Dark.halftone.copy(alpha = Brand.Dark.HALFTONE_ALPHA),
    halftoneStrong = Brand.Dark.halftoneStrong.copy(alpha = Brand.Dark.HALFTONE_STRONG_ALPHA),
    grainAlpha = Brand.Dark.GRAIN_ALPHA,
    drumPapers = Brand.Dark.drumPapers,
    posterPapers = Brand.Dark.posterPapers,
    family = family,
    tract = TractColors.dark(),
    isDark = true,
  )
}

internal val LightPhonoColors: PhonoColors = phonoColors(darkTheme = false)
internal val DarkPhonoColors: PhonoColors = phonoColors(darkTheme = true)

val LocalPhonoColors = staticCompositionLocalOf { LightPhonoColors }
