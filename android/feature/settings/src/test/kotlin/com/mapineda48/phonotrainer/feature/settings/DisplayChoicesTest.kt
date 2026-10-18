package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.theme.FamilyColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.phonoColors
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.extension.PhonoAppRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Display choices do once the app root reads them (spec F-22-AC1, AC2): the colour-vision-friendly palette
 * turns the pattern icons on unless "Show patterns as well as colors" is Off, and "On" shows them with either palette;
 * Motion "Reduce" reduces motion whatever the system says. The marks and the feed read these values (WP-03, WP-17).
 */
@RunWith(RobolectricTestRunner::class)
class DisplayChoicesTest {
  @get:Rule
  val compose = createComposeRule()

  private data class Seen(val patterns: Boolean, val family: FamilyColors, val reduced: Boolean, val themeReduced: Boolean)

  /** Renders the app root over [settings] for each of [cases] and returns what the content saw. */
  private fun seen(cases: List<UserSettings>): List<Seen> {
    val seen = arrayOfNulls<Seen>(cases.size)
    compose.setContent {
      cases.forEachIndexed { index, settings ->
        PhonoAppRoot(settings = settings, talkBackOn = false) {
          seen[index] = Seen(PhonoTheme.patterns, PhonoTheme.colors.family, LocalReducedMotion.current, PhonoTheme.palette.reducedMotion)
        }
      }
    }
    compose.waitForIdle()
    return seen.map { checkNotNull(it) }
  }

  @Test
  fun theColorVisionFriendlyPaletteShowsThePatternsUnlessTheyAreTurnedOff() {
    val base = UserSettings(appearance = Appearance.LIGHT)
    val cases = listOf(
      base.copy(colors = ColorChoice.COLOR_VISION_FRIENDLY, patterns = PatternChoice.FOLLOW_COLORS),
      base.copy(colors = ColorChoice.COLOR_VISION_FRIENDLY, patterns = PatternChoice.OFF),
      base.copy(colors = ColorChoice.STANDARD, patterns = PatternChoice.FOLLOW_COLORS),
      base.copy(colors = ColorChoice.STANDARD, patterns = PatternChoice.ON),
    )

    val seen = seen(cases)

    assertThat(seen.map { it.patterns }).containsExactly(true, false, false, true).inOrder()
    assertThat(seen[0].family).isEqualTo(phonoColors(false, ColorChoice.COLOR_VISION_FRIENDLY).family)
    assertThat(seen[2].family).isEqualTo(phonoColors(false, ColorChoice.STANDARD).family)
    assertThat(seen[0].family).isNotEqualTo(seen[2].family)
  }

  @Test
  fun motionReduceReducesMotionEverywhereTheRootReaches() {
    val seen = seen(listOf(UserSettings(motion = MotionChoice.REDUCE)))

    assertThat(seen.single().reduced).isTrue()
    assertThat(seen.single().themeReduced).isTrue()
  }
}
