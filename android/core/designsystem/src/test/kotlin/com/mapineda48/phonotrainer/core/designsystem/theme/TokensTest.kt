package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Type, shape, spacing and motion tokens of design 4.2, 5 and 8. */
@RunWith(RobolectricTestRunner::class)
class TokensTest {
  private val materialRoles = with(phonoTypography) {
    mapOf(
      "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
      "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
      "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
      "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
      "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )
  }

  @Test
  fun nothingIsSmallerThanFourteenSp() {
    val extras = with(PhonoType()) {
      listOf(spotlightWord, lessonWord, captionCurrent, captionDim, accentItalic, posterTitle, dialogTitle, requestState,
        ipaSlip, ipaForm, ipaSort, ipaSymbol)
    }
    for ((name, style) in materialRoles) assertWithMessage(name).that(style.fontSize.value).isAtLeast(14f)
    for (style in extras) assertThat(style.fontSize.value).isAtLeast(14f)
    for (style in materialRoles.values + extras) assertThat(style.lineHeight.value).isAtLeast(style.fontSize.value)
  }

  @Test
  fun theFacesHaveTheirRoles() {
    // Newsreader only for display roles (15 sp and up), Atkinson for the UI, Charis for IPA.
    for (name in listOf("displayLarge", "displayMedium", "displaySmall", "headlineLarge", "headlineMedium", "headlineSmall")) {
      assertThat(materialRoles.getValue(name).fontFamily).isNotEqualTo(PhonoFonts.Atkinson)
      assertThat(materialRoles.getValue(name).fontSize.value).isAtLeast(15f)
    }
    for (name in listOf("titleLarge", "bodyLarge", "bodySmall", "labelLarge", "labelSmall")) {
      assertThat(materialRoles.getValue(name).fontFamily).isEqualTo(PhonoFonts.Atkinson)
    }
    assertThat(PhonoType().ipaSlip.fontFamily).isEqualTo(PhonoFonts.Charis)
    assertThat(PhonoType().ipaInline.fontFamily).isEqualTo(PhonoFonts.Charis)
    assertThat(PhonoType().ipaInline.fontSize).isEqualTo(1.12.em)
    assertThat(phonoTypography.displayLarge.fontWeight).isEqualTo(FontWeight(650))
    assertThat(phonoTypography.labelSmall.letterSpacing).isEqualTo(0.07.em)
    // Equal arguments give equal families, which share one typeface.
    assertThat(PhonoFonts.newsreader(600, 28f)).isEqualTo(PhonoFonts.newsreader(600, 28f))
    assertThat(phonoTypography.bodySmall.tabular().fontFeatureSettings).isEqualTo("tnum")
  }

  @Test
  fun shapesAreTheRadiiOfDesignFiveOne() {
    val density = Density(1f)
    val size = Size(200f, 200f)
    fun radius(shape: androidx.compose.ui.graphics.Shape) =
      (shape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Rounded).roundRect.topLeftCornerRadius.x
    assertThat(radius(phonoShapes.extraSmall)).isEqualTo(4f)
    assertThat(radius(phonoShapes.small)).isEqualTo(8f)
    assertThat(radius(phonoShapes.medium)).isEqualTo(12f)
    assertThat(radius(phonoShapes.large)).isEqualTo(14f)
    assertThat(radius(phonoShapes.extraLarge)).isEqualTo(28f)
    val slip = PerforatedTopShape().createOutline(Size(300f, 120f), LayoutDirection.Ltr, density)
    assertThat(slip).isInstanceOf(Outline.Generic::class.java)
    assertThat(PerforatedTopShape()).isEqualTo(PerforatedTopShape())
  }

  @Test
  fun spacingIsOnTheGridAndTargetsAreFortyEightDp() {
    val grid = listOf(PhonoSpacing.s2, PhonoSpacing.s4, PhonoSpacing.s6, PhonoSpacing.s8, PhonoSpacing.s10,
      PhonoSpacing.s12, PhonoSpacing.s14, PhonoSpacing.s16, PhonoSpacing.s20, PhonoSpacing.s24, PhonoSpacing.s32)
    assertThat(grid.map { it.value }).containsExactly(2f, 4f, 6f, 8f, 10f, 12f, 14f, 16f, 20f, 24f, 32f).inOrder()
    assertThat(PhonoSizes.minTarget).isEqualTo(48.dp)
    assertThat(PhonoSizes.quizAnswerMin).isEqualTo(56.dp)
    assertThat(PhonoSizes.focusRing).isEqualTo(3.dp)
  }

  @Test
  fun reducedMotionSnapsEverySpec() {
    val reduced = PhonoMotionScheme(reduced = true)
    val normal = PhonoMotionScheme(reduced = false)
    for (spec in listOf(reduced.fast<Float>(), reduced.standard(), reduced.slow(), reduced.enter(), reduced.exit(),
      reduced.draw(), reduced.selection())) {
      assertThat(spec).isInstanceOf(SnapSpec::class.java)
    }
    assertThat((normal.standard<Float>() as TweenSpec).durationMillis).isEqualTo(220)
    assertThat((normal.fast<Float>() as TweenSpec).durationMillis).isEqualTo(120)
    assertThat((normal.slow<Float>() as TweenSpec).durationMillis).isEqualTo(360)
  }
}
