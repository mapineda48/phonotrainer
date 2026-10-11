package com.mapineda48.phonotrainer.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.theme.ColorMath.Deficiency
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import org.junit.Test

/**
 * The palette gates G1 to G11 of design 3 (A-10: 7:1 body text, 4.5:1 secondary text, 3:1 marks, focus and control
 * outlines), ported from `design-src/tools/check_palette.py` and run on the values the theme actually provides:
 * [phonoColors] for both themes and both palettes, and the launcher constants of `Palette.kt`. The thresholds are the
 * spec's and the design's, written here, so loosening `palette.json` cannot loosen this test. The port runs exactly
 * the 499 checks of the Python tool.
 */
class PaletteGatesTest {
  private class Gates {
    val failures = mutableListOf<String>()
    var checks = 0

    fun check(ok: Boolean, label: String) {
      checks += 1
      if (!ok) failures += label
    }
  }

  private val themes = listOf(false, true)
  private val choices = listOf(ColorChoice.STANDARD, ColorChoice.COLOR_VISION_FRIENDLY)
  private val frames = mapOf("white" to Rgb.hex("#ffffff"), "mid-grey" to Rgb.hex("#808080"), "black" to Rgb.hex("#000000"))

  private fun name(dark: Boolean) = if (dark) "dark" else "light"

  private fun neutralsOf(c: PhonoColors): Map<String, Rgb> = linkedMapOf(
    "page" to c.page, "sheet" to c.sheet, "galley" to c.galley, "deepSheet" to c.deepSheet, "ink" to c.ink,
    "ink2" to c.ink2, "inkMuted" to c.inkMuted, "onInk" to c.onInk, "outline" to c.outline, "hairline" to c.hairline,
    "lineStrong" to c.lineStrong, "focus" to c.focus, "good" to c.good, "critical" to c.critical,
    "inverseSurface" to c.inverseSurface, "inverseOnSurface" to c.inverseOnSurface, "scrim" to c.scrim,
    "letterbox" to c.letterbox,
  ).mapValues { Rgb.of(it.value) }

  private fun familiesOf(c: PhonoColors): Map<String, Pair<Rgb, Rgb>> = linkedMapOf(
    "reduction" to (c.family.reduction to c.family.reductionTint),
    "td" to (c.family.td to c.family.tdTint),
    "assimilation" to (c.family.assimilation to c.family.assimilationTint),
    "boundary" to (c.family.boundary to c.family.boundaryTint),
  ).mapValues { Rgb.of(it.value.first) to Rgb.of(it.value.second) }

  private fun g1(g: Gates) {
    val minimums = linkedMapOf("ink" to 7.0, "ink2" to 4.5, "inkMuted" to 4.5, "focus" to 3.0, "outline" to 3.0,
      "good" to 3.0, "critical" to 3.0)
    for (dark in themes) {
      val n = neutralsOf(phonoColors(dark))
      for (surface in listOf("page", "sheet", "galley", "deepSheet")) {
        for ((key, min) in minimums) {
          val ratio = ColorMath.contrast(n.getValue(key), n.getValue(surface))
          g.check(ratio >= min, "G1 ${name(dark)} $key on $surface: %.2f < $min".format(ratio))
        }
      }
    }
  }

  private fun g2(g: Gates) {
    for (dark in themes) {
      val n = neutralsOf(phonoColors(dark))
      for ((fg, bg) in listOf("onInk" to "ink", "inverseOnSurface" to "inverseSurface", "ink" to "page")) {
        val ratio = ColorMath.contrast(n.getValue(fg), n.getValue(bg))
        g.check(ratio >= 7.0, "G2 ${name(dark)} $fg on $bg: %.2f".format(ratio))
      }
    }
  }

  private fun g3to6(g: Gates, gate: Int) {
    for (choice in choices) {
      for (dark in themes) {
        val colors = phonoColors(dark, choice)
        val n = neutralsOf(colors)
        val fam = familiesOf(colors)
        val label = "${choice.name.lowercase()} ${name(dark)}"
        val share = if (dark) FamilyPalette.TINT_SHARE_DARK.toDouble() else FamilyPalette.TINT_SHARE_LIGHT.toDouble()
        when (gate) {
          3 -> for ((key, pair) in fam) {
            val (hue, tint) = pair
            for (surface in listOf("page", "sheet")) {
              val ratio = ColorMath.contrast(hue, n.getValue(surface))
              g.check(ratio >= 3.0, "G3 $label $key mark on $surface: %.2f".format(ratio))
            }
            val expected = ColorMath.mix(n.getValue("sheet"), hue, share)
            val drift = maxOf(abs(expected.r - tint.r), abs(expected.g - tint.g), abs(expected.b - tint.b)) * 255
            g.check(drift <= 1.01, "G3 $label $key tint ${tint.hex} is not the OKLab mix ${expected.hex}")
            val inkOnTint = ColorMath.contrast(n.getValue("ink"), expected)
            g.check(inkOnTint >= 7.0, "G3 $label ink on $key tint: %.2f".format(inkOnTint))
          }
          4 -> for ((a, b) in pairs(fam.keys.toList())) {
            val d = ColorMath.deltaEok(fam.getValue(a).first, fam.getValue(b).first)
            g.check(d >= 15.0, "G4 $label $a~$b: %.1f dEok".format(d))
          }
          5 -> {
            val target = if (choice == ColorChoice.STANDARD) 8.0 else 12.0
            for (kind in Deficiency.entries) {
              for (severity in listOf(1.0, 0.6)) {
                for ((a, b) in pairs(fam.keys.toList())) {
                  val d = ColorMath.deltaEok(
                    ColorMath.simulate(kind, severity, fam.getValue(a).first),
                    ColorMath.simulate(kind, severity, fam.getValue(b).first),
                  )
                  g.check(d >= target, "G5 $label $kind $severity $a~$b: %.1f dEok".format(d))
                }
              }
            }
          }
          6 -> {
            val (low, high) = when {
              !dark -> 0.43 to 0.77
              choice == ColorChoice.STANDARD -> 0.48 to 0.67
              else -> 0.48 to 0.82
            }
            for ((key, pair) in fam) {
              val c = ColorMath.chroma(pair.first)
              val l = ColorMath.lightness(pair.first)
              g.check(c >= 0.10, "G6 $label $key chroma %.3f".format(c))
              g.check(l in low..high, "G6 $label $key lightness %.3f outside $low-$high".format(l))
            }
          }
        }
      }
    }
  }

  private fun g7(g: Gates) {
    for (dark in themes) {
      val t = phonoColors(dark).tract
      val bone = ColorMath.lightness(Rgb.of(t.bone))
      for ((key, color) in listOf("lip" to t.lip, "tongue" to t.tongue, "tongueDeep" to t.tongueDeep)) {
        val gap = abs(bone - ColorMath.lightness(Rgb.of(color)))
        g.check(gap > 0.15, "G7 ${name(dark)} teeth vs $key: %.3f".format(gap))
      }
    }
  }

  private fun g8(g: Gates) {
    val exempt = setOf("focus", "good", "critical")
    for (dark in themes) {
      val colors = phonoColors(dark)
      for ((key, rgb) in neutralsOf(colors)) {
        if (key in exempt) continue
        val c = ColorMath.chroma(rgb)
        g.check(c <= 0.05, "G8 ${name(dark)} neutrals.$key chroma %.3f".format(c))
      }
      val brand = linkedMapOf("kraft" to listOf(colors.kraft), "kraft2" to listOf(colors.kraft2),
        "halftone" to listOf(colors.halftone), "halftoneStrong" to listOf(colors.halftoneStrong),
        "drumPapers" to colors.drumPapers, "posterPapers" to colors.posterPapers)
      for ((key, list) in brand) {
        list.forEachIndexed { i, color ->
          val c = ColorMath.chroma(Rgb.of(color))
          g.check(c <= 0.05, "G8 ${name(dark)} brand.$key[$i] chroma %.3f".format(c))
        }
      }
    }
  }

  private fun g9(g: Gates) {
    for (dark in themes) {
      val n = neutralsOf(phonoColors(dark))
      val overlays = listOf(
        Triple("caption slip", n.getValue("sheet") to 1.0, mapOf("ink" to 7.0, "ink2" to 4.5, "inkMuted" to 4.5)),
        Triple("media chip", n.getValue("sheet") to PhonoColors.OVERLAY_PLATE_ALPHA.toDouble(), mapOf("ink" to 7.0, "ink2" to 4.5)),
        Triple("coach mark", n.getValue("inverseSurface") to PhonoColors.COACH_PLATE_ALPHA.toDouble(), mapOf("inverseOnSurface" to 7.0)),
      )
      for ((overlay, fill, texts) in overlays) {
        for ((frameName, frame) in frames) {
          val plate = ColorMath.over(fill.first, fill.second, frame)
          for ((fg, min) in texts) {
            val ratio = ColorMath.contrast(n.getValue(fg), plate)
            g.check(ratio >= min, "G9 ${name(dark)} $overlay $fg over $frameName: %.2f".format(ratio))
          }
          if (overlay == "caption slip") {
            for (choice in choices) {
              for ((key, pair) in familiesOf(phonoColors(dark, choice))) {
                val ratio = ColorMath.contrast(pair.first, plate)
                g.check(ratio >= 3.0, "G9 ${name(dark)} caption slip ${choice.name} $key over $frameName: %.2f".format(ratio))
              }
            }
          }
        }
      }
    }
  }

  private fun g10(g: Gates) {
    for (dark in themes) {
      val colors = phonoColors(dark)
      val ink = Rgb.of(colors.ink)
      for (surface in listOf(colors.page, colors.sheet)) {
        val ratio = ColorMath.contrast(ink, Rgb.of(surface))
        g.check(ratio >= 3.0, "G10 ${name(dark)} illustration ink: %.2f".format(ratio))
      }
      val onKraft = ColorMath.contrast(Rgb.of(colors.kraft), ink)
      g.check(onKraft >= 3.0, "G10 ${name(dark)} ink on kraft: %.2f".format(onKraft))
    }
    val tile = Rgb.of(Launcher.tile)
    val mark = ColorMath.contrast(Rgb.of(Launcher.mark), tile)
    g.check(mark >= 7.0, "G10 launcher mark on tile: %.2f".format(mark))
    for (sheet in Launcher.shareSheets) {
      val ratio = ColorMath.contrast(tile, Rgb.of(sheet.color))
      g.check(ratio >= sheet.minContrast, "G10 tile on the ${sheet.name} share sheet: %.2f".format(ratio))
    }
    val light = ColorMath.contrast(Rgb.of(Launcher.splashMarkLight), Rgb.of(Launcher.splashBackgroundLight))
    val dark = ColorMath.contrast(Rgb.of(Launcher.splashMarkDark), Rgb.of(Launcher.splashBackgroundDark))
    g.check(light >= 7.0, "G10 light splash mark: %.2f".format(light))
    g.check(dark >= 7.0, "G10 dark splash mark: %.2f".format(dark))
  }

  private fun g11(g: Gates) {
    for (dark in themes) {
      val colors = phonoColors(dark)
      colors.drumPapers.forEachIndexed { i, paper ->
        val ratio = ColorMath.contrast(Rgb.of(colors.ink), Rgb.of(paper))
        g.check(ratio >= 7.0, "G11 ${name(dark)} pitch line on drum paper $i: %.2f".format(ratio))
        val c = ColorMath.chroma(Rgb.of(paper))
        g.check(c <= 0.05, "G11 ${name(dark)} drum paper $i chroma %.3f".format(c))
      }
    }
  }

  private fun <T> pairs(keys: List<T>): List<Pair<T, T>> =
    keys.indices.flatMap { i -> (i + 1 until keys.size).map { j -> keys[i] to keys[j] } }

  private fun abs(x: Double) = kotlin.math.abs(x)

  private fun assertGate(run: (Gates) -> Unit) {
    val g = Gates()
    run(g)
    assertThat(g.failures).isEmpty()
    assertThat(g.checks).isGreaterThan(0)
  }

  @Test fun g1_neutralsOnTextSurfaces() = assertGate(::g1)

  @Test fun g2_textOnInkAndInverse() = assertGate(::g2)

  @Test fun g3_marksAndTints() = assertGate { g3to6(it, 3) }

  @Test fun g4_normalVisionSeparation() = assertGate { g3to6(it, 4) }

  @Test fun g5_simulatedCvdSeparation() = assertGate { g3to6(it, 5) }

  @Test fun g6_realHuesInTheirBand() = assertGate { g3to6(it, 6) }

  @Test fun g7_teethStandApart() = assertGate(::g7)

  @Test fun g8_hueReservation() = assertGate(::g8)

  @Test fun g9_overlaysOverVideoFrames() = assertGate(::g9)

  @Test fun g10_illustrationsAndLauncher() = assertGate(::g10)

  @Test fun g11_drumPapers() = assertGate(::g11)

  @Test
  fun thePortRunsTheSameChecksAsCheckPalette() {
    val g = Gates()
    g1(g)
    g2(g)
    for (gate in 3..6) g3to6(g, gate)
    g7(g)
    g8(g)
    g9(g)
    g10(g)
    g11(g)
    // `python3 android/design-src/tools/check_palette.py` reports "ALL GATES PASS (499 checks)".
    assertThat(g.checks).isEqualTo(499)
    assertThat(g.failures).isEmpty()
  }

  @Test
  fun theMeasuredValuesOfDesignSectionThreeHold() {
    // Spot values of design 3's tables, to two decimals.
    val light = phonoColors(false)
    val dark = phonoColors(true)
    assertThat(ColorMath.contrast(Rgb.of(light.ink), Rgb.of(light.page))).isWithin(0.01).of(16.08)
    assertThat(ColorMath.contrast(Rgb.of(dark.inkMuted), Rgb.of(dark.deepSheet))).isWithin(0.01).of(5.24)
    assertThat(ColorMath.contrast(Rgb.of(light.family.boundary), Rgb.of(light.page))).isWithin(0.01).of(3.13)
    assertThat(ColorMath.contrast(Rgb.of(Launcher.mark), Rgb.of(Launcher.tile))).isWithin(0.01).of(10.00)
  }
}
