package com.mapineda48.phonotrainer.core.testing.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.testing.a11y.A11yAudit
import com.mapineda48.phonotrainer.core.ui.extension.PhonoAppRoot
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * One way a screen is shown in a screen test (architecture 18, 21.1; design 11): a font scale, light or dark, the
 * Standard or the colour-vision-friendly palette, and TalkBack and reduced motion on or off.
 */
data class ScreenVariant(
  val fontScale: Float = 1f,
  val dark: Boolean = false,
  val colors: ColorChoice = ColorChoice.STANDARD,
  val talkBackOn: Boolean = false,
  val reducedMotion: Boolean = false,
) {
  /** [base] with this variant's appearance and palette, as the app root reads them. */
  fun settings(base: UserSettings): UserSettings =
    base.copy(appearance = if (dark) Appearance.DARK else Appearance.LIGHT, colors = colors)

  override fun toString(): String = buildString {
    append("font scale ").append(fontScale).append(", ").append(if (dark) "dark" else "light").append(", ").append(colors)
    if (talkBackOn) append(", TalkBack on")
    if (reducedMotion) append(", reduced motion")
  }

  companion object {
    /** The font scales of design 11. */
    val FONT_SCALES: List<Float> = listOf(1f, 1.3f, 2f)

    /** Design 11's matrix: the three font scales × light and dark × Standard and colour-vision-friendly. */
    val MATRIX: List<ScreenVariant> = FONT_SCALES.flatMap { scale ->
      listOf(false, true).flatMap { dark -> ColorChoice.entries.map { ScreenVariant(scale, dark, it) } }
    }

    /** What architecture 21.1 asks of every screen test at least: font scale 2.0, light and dark. */
    val REQUIRED: List<ScreenVariant> = listOf(ScreenVariant(fontScale = 2f), ScreenVariant(fontScale = 2f, dark = true))
  }
}

/**
 * The one screen-test harness of the project (work-plan 4.6; decision I-13), for Robolectric screen tests
 * (`@RunWith(RobolectricTestRunner::class)`; every module's tests run on sdk 35, architecture 21.2). It holds the
 * Compose rule ([compose], the `v2` rule, whose effects run on a `StandardTestDispatcher`) and sets content inside the
 * app's own root, `PhonoAppRoot`, with the learner's [settings] and [ipaReading], so a screen meets the theme, palette,
 * patterns, motion, TalkBack and IPA reading the app gives it. Variants switch on the content already set, so one test
 * walks the whole matrix:
 *
 * ```
 * @get:Rule val screen = PhonoScreenRule()
 *
 * @Test fun theEmptyLibraryReadsAtEveryVariant() =
 *   screen.checkEach(content = { LibraryScreen(LibraryState.Empty, onAdd = {}) }) {
 *     screen.compose.onNodeWithTag(LibraryTags.EMPTY).assertIsDisplayed()
 *   }
 * ```
 *
 * Every variant [checkEach] shows is followed by the accessibility audit of architecture 18 ([audit]); a failure names
 * the variant.
 */
class PhonoScreenRule(
  private val settings: UserSettings = UserSettings(),
  private val ipaReading: IpaReading = IpaReading(),
  effectContext: CoroutineContext = EmptyCoroutineContext,
) : TestRule {
  val compose: ComposeContentTestRule = createComposeRule(effectContext)

  private val shown = mutableStateOf(ScreenVariant())

  /** The variant on screen. */
  val variant: ScreenVariant
    get() = shown.value

  override fun apply(base: Statement, description: Description): Statement = compose.apply(base, description)

  /** Sets [content] inside the app root at [variant]; once per test, as Compose rules allow. */
  fun setContent(variant: ScreenVariant = ScreenVariant(), content: @Composable () -> Unit) {
    shown.value = variant
    compose.setContent { Root(shown.value, content) }
  }

  /** Shows [next] on the content already set and waits until it is drawn. */
  fun show(next: ScreenVariant) {
    shown.value = next
    compose.waitForIdle()
  }

  /** The accessibility audit (architecture 18, SC-12) of what is on screen. */
  fun audit() {
    A11yAudit.assertClean(compose, compose.density)
  }

  /**
   * Sets [content] once, then for each of [variants] in order: shows it, runs [check] with it and the [audit]. The
   * first failure is rethrown with the variant in its message.
   */
  fun checkEach(
    variants: List<ScreenVariant> = ScreenVariant.MATRIX,
    content: @Composable () -> Unit,
    check: (ScreenVariant) -> Unit = {},
  ) {
    require(variants.isNotEmpty()) { "No variant to check" }
    setContent(variants.first(), content)
    variants.forEach { variant ->
      show(variant)
      try {
        check(variant)
        audit()
      } catch (failure: Throwable) {
        throw AssertionError("At $variant: ${failure.message}", failure)
      }
    }
  }

  @Composable
  private fun Root(variant: ScreenVariant, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
      PhonoAppRoot(
        settings = variant.settings(settings),
        ipaReading = ipaReading,
        reducedMotion = variant.reducedMotion,
        talkBackOn = variant.talkBackOn,
        content = content,
      )
    }
  }
}
