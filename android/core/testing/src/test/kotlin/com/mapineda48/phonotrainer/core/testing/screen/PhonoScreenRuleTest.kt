package com.mapineda48.phonotrainer.core.testing.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import com.mapineda48.phonotrainer.core.ui.ipa.LocalIpaReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared screen-test harness (work-plan 4.6; decision I-13) gives each variant the app root's theme and locals. */
@RunWith(RobolectricTestRunner::class)
class PhonoScreenRuleTest {
  private val reading = IpaReading(tokens = listOf("aɪ"))

  @get:Rule
  val screen = PhonoScreenRule(settings = UserSettings(colors = ColorChoice.STANDARD), ipaReading = reading)

  private val seen = mutableListOf<Seen>()

  private data class Seen(
    val fontScale: Float,
    val dark: Boolean,
    val palette: ColorChoice,
    val patterns: Boolean,
    val talkBack: Boolean,
    val reducedMotion: Boolean,
    val themeMotionReduced: Boolean,
    val reading: IpaReading,
  )

  @Composable
  private fun Probe() {
    seen += Seen(
      fontScale = LocalDensity.current.fontScale,
      dark = PhonoTheme.colors.isDark,
      palette = PhonoTheme.palette.colors,
      patterns = PhonoTheme.patterns,
      talkBack = LocalTalkBackOn.current,
      reducedMotion = LocalReducedMotion.current,
      themeMotionReduced = PhonoTheme.motion.reduced,
      reading = LocalIpaReading.current,
    )
    Box(Modifier.size(48.dp).clickable {}.semantics { contentDescription = "Play" })
  }

  @Test
  fun theMatrixIsDesign11s() {
    val matrix = ScreenVariant.MATRIX
    assertThat(matrix).hasSize(12)
    assertThat(matrix.map { it.fontScale }.toSet()).containsExactly(1f, 1.3f, 2f)
    assertThat(matrix.map { it.dark }.toSet()).containsExactly(false, true)
    assertThat(matrix.map { it.colors }.toSet()).containsExactlyElementsIn(ColorChoice.entries)
    assertThat(matrix.toSet()).hasSize(12)
    assertThat(ScreenVariant.REQUIRED).containsExactly(ScreenVariant(fontScale = 2f), ScreenVariant(fontScale = 2f, dark = true))
  }

  @Test
  fun everyVariantReachesTheContentThroughTheAppRoot() {
    val variants = listOf(
      ScreenVariant(),
      ScreenVariant(fontScale = 2f, dark = true, colors = ColorChoice.COLOR_VISION_FRIENDLY),
      ScreenVariant(fontScale = 1.3f, talkBackOn = true, reducedMotion = true),
    )
    val checked = mutableListOf<ScreenVariant>()
    screen.checkEach(variants, content = { Probe() }) { variant ->
      checked += variant
      val last = seen.last()
      assertThat(last.fontScale).isEqualTo(variant.fontScale)
      assertThat(last.dark).isEqualTo(variant.dark)
      assertThat(last.palette).isEqualTo(variant.colors)
      // "Show patterns as well as colours" follows the palette by default (spec F-22).
      assertThat(last.patterns).isEqualTo(variant.colors == ColorChoice.COLOR_VISION_FRIENDLY)
      assertThat(last.talkBack).isEqualTo(variant.talkBackOn)
      // One value feeds both sources of reduced motion (architecture 18; the review's finding 5).
      assertThat(last.reducedMotion).isEqualTo(variant.reducedMotion)
      assertThat(last.themeMotionReduced).isEqualTo(variant.reducedMotion)
      assertThat(last.reading).isSameInstanceAs(reading)
    }
    assertThat(checked).isEqualTo(variants)
  }

  @Test
  fun theAuditRunsAtEveryVariantAndNamesIt() {
    val failure = runCatching {
      screen.checkEach(ScreenVariant.REQUIRED, content = { Box(Modifier.size(48.dp).clickable {}) })
    }.exceptionOrNull()
    assertThat(failure).isInstanceOf(AssertionError::class.java)
    assertThat(failure).hasMessageThat().contains("font scale 2.0, light, STANDARD")
    assertThat(failure).hasMessageThat().contains("has no label")
  }
}
