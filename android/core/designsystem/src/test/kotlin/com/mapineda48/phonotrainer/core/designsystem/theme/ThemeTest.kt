package com.mapineda48.phonotrainer.core.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.R
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUIModeManager

/**
 * The theme's contract (architecture 17, design 2.2, 12.1, 12.4): the explicit Material scheme, the roles of
 * [PhonoTheme.colors] per theme and palette, the patterns switch, reduced motion, the dark-mode strategy (the
 * configuration context that makes `drawable-night` and `values-night` follow the in-app appearance) and the frozen
 * resources of work-plan 4.1.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
  @get:Rule
  val compose = createComposeRule()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun theMaterialSchemeIsTheExplicitMappingOfDesignTwoTwo() {
    val light = phonoColorScheme(darkTheme = false)
    val dark = phonoColorScheme(darkTheme = true)
    assertThat(light.primary).isEqualTo(Neutrals.Light.ink)
    assertThat(light.onPrimary).isEqualTo(Neutrals.Light.onInk)
    assertThat(light.tertiary).isEqualTo(Neutrals.Light.ink2)
    assertThat(light.errorContainer).isEqualTo(Neutrals.Light.galley)
    assertThat(light.surfaceContainer).isEqualTo(Neutrals.Light.sheet)
    assertThat(light.surfaceContainerHighest).isEqualTo(Neutrals.Light.deepSheet)
    assertThat(light.surfaceTint.alpha).isEqualTo(0f)
    assertThat(dark.primary).isEqualTo(Neutrals.Dark.ink)
    assertThat(dark.background).isEqualTo(Neutrals.Dark.page)
    assertThat(dark.surfaceBright).isEqualTo(Neutrals.Dark.deepSheet)
    assertThat(dark.surfaceDim).isEqualTo(Neutrals.Dark.page)
    assertThat(dark.outline).isEqualTo(Neutrals.Dark.outline)
  }

  @Test
  fun thePaletteChoicePicksTheFamilyHues() {
    assertThat(phonoColors(false, ColorChoice.STANDARD).family.of(Family.TD)).isEqualTo(FamilyPalette.Standard.Light.td)
    assertThat(phonoColors(true, ColorChoice.COLOR_VISION_FRIENDLY).family.of(Family.BOUNDARY))
      .isEqualTo(FamilyPalette.Cvd.Dark.boundary)
    // Lexical contraction is ink, other changes inkMuted; their chips sit on galley.
    assertThat(phonoColors(false).family.of(Family.LEXICAL)).isEqualTo(Neutrals.Light.ink)
    assertThat(phonoColors(true).family.of(Family.OTHER)).isEqualTo(Neutrals.Dark.inkMuted)
    assertThat(phonoColors(false).family.tintOf(Family.OTHER)).isEqualTo(Neutrals.Light.galley)
    assertThat(phonoColors(false).scrim.alpha).isWithin(0.01f).of(0.46f)
    assertThat(phonoColors(true).scrim.alpha).isWithin(0.01f).of(0.62f)
  }

  @Test
  fun patternsFollowTheColoursUnlessForced() {
    assertThat(PhonoPalette(ColorChoice.STANDARD, PatternChoice.FOLLOW_COLORS, false).showPatterns).isFalse()
    assertThat(PhonoPalette(ColorChoice.COLOR_VISION_FRIENDLY, PatternChoice.FOLLOW_COLORS, false).showPatterns).isTrue()
    assertThat(PhonoPalette(ColorChoice.STANDARD, PatternChoice.ON, false).showPatterns).isTrue()
    assertThat(PhonoPalette(ColorChoice.COLOR_VISION_FRIENDLY, PatternChoice.OFF, false).showPatterns).isFalse()
  }

  @Test
  fun theThemeProvidesItsRolesTypeAndMotion() {
    var colors: PhonoColors? = null
    var patterns = false
    var reduced = false
    var primary: androidx.compose.ui.graphics.Color? = null
    compose.setContent {
      PhonoTheme(darkTheme = true, colors = ColorChoice.COLOR_VISION_FRIENDLY, reducedMotion = true) {
        colors = PhonoTheme.colors
        patterns = PhonoTheme.patterns
        reduced = PhonoTheme.motion.reduced
        primary = MaterialTheme.colorScheme.primary
      }
    }
    compose.waitForIdle()
    assertThat(colors).isEqualTo(phonoColors(true, ColorChoice.COLOR_VISION_FRIENDLY))
    assertThat(patterns).isTrue()
    assertThat(reduced).isTrue()
    assertThat(primary).isEqualTo(Neutrals.Dark.ink)
  }

  @Test
  fun theInAppAppearanceReachesNightResourcesThroughTheConfigurationContext() {
    var night: Int? = null
    var systemDark: Boolean? = null
    var hasActivity = false
    compose.setContent {
      PhonoTheme(darkTheme = true) {
        val ctx = LocalContext.current
        night = ctx.getColor(R.color.phono_page)
        systemDark = isSystemInDarkTheme()
        hasActivity = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
          .any { it is android.app.Activity }
      }
    }
    compose.waitForIdle()
    // The test device is in day mode; inside the dark theme, values-night answers.
    assertThat(night).isEqualTo(0xFF121110.toInt())
    assertThat(systemDark).isTrue()
    assertThat(hasActivity).isTrue()
  }

  @Test
  fun theConfigurationContextIsTheBaseWhenItAlreadyMatches() {
    assertThat(NightMode.contextFor(context, dark = false)).isSameInstanceAs(context)
    val dark = NightMode.contextFor(context, dark = true)
    assertThat(NightMode.isNight(dark.resources.configuration)).isTrue()
    assertThat(dark.getColor(R.color.phono_page)).isEqualTo(0xFF121110.toInt())
    assertThat(dark.getColor(R.color.phono_window_background)).isEqualTo(0xFF121110.toInt())
    assertThat(context.getColor(R.color.phono_window_background)).isEqualTo(0xFFF8F6EF.toInt())
    val config = NightMode.configurationFor(Configuration(), dark = true)
    assertThat(config.uiMode and Configuration.UI_MODE_NIGHT_MASK).isEqualTo(Configuration.UI_MODE_NIGHT_YES)
  }

  @Test
  fun theShellHelperMapsTheAppearanceToTheSystemNightMode() {
    assertThat(NightMode.modeOf(Appearance.SYSTEM)).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
    assertThat(NightMode.modeOf(Appearance.LIGHT)).isEqualTo(UiModeManager.MODE_NIGHT_NO)
    assertThat(NightMode.modeOf(Appearance.DARK)).isEqualTo(UiModeManager.MODE_NIGHT_YES)
    NightMode.apply(context, Appearance.DARK)
    val manager = context.getSystemService(UiModeManager::class.java)
    assertThat(Shadow.extract<ShadowUIModeManager>(manager).applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_YES)
    NightMode.apply(context, Appearance.SYSTEM)
  }

  @Test
  @Config(sdk = [35])
  fun theFrozenResourcesResolve() {
    val icon = context.getDrawable(R.mipmap.ic_launcher)
    assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
    icon as AdaptiveIconDrawable
    assertThat(icon.background).isNotNull()
    assertThat(icon.foreground).isNotNull()
    assertThat(icon.monochrome).isNotNull()
    assertThat(context.getDrawable(R.mipmap.ic_launcher_round)).isInstanceOf(AdaptiveIconDrawable::class.java)
    assertThat(context.getDrawable(R.drawable.ic_stat_phono)).isNotNull()
    for (style in listOf(R.style.Theme_Phono, R.style.Theme_Phono_Starting, R.style.Theme_Phono_ShareSheet)) {
      assertThat(context.resources.getResourceName(style)).contains("Theme.Phono")
    }
    for (font in listOf(R.font.atkinson_hyperlegible_next, R.font.newsreader, R.font.newsreader_italic,
      R.font.charis_regular, R.font.charis_bold)) {
      assertThat(context.resources.getResourceTypeName(font)).isEqualTo("font")
    }
  }
}
