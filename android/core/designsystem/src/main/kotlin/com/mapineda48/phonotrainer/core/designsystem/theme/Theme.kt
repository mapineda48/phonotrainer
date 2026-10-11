package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice

/** The palette and pattern choices of Settings > Display, as the theme received them. */
@Immutable
data class PhonoPalette(val colors: ColorChoice, val patterns: PatternChoice, val reducedMotion: Boolean) {
  /**
   * Whether marks carry their pattern icon and the heavier line (design 9.4): "Show patterns as well as colours"
   * follows the palette by default (on with the colour-vision-friendly one, spec F-22), or is forced on or off.
   */
  val showPatterns: Boolean
    get() = when (patterns) {
      PatternChoice.FOLLOW_COLORS -> colors == ColorChoice.COLOR_VISION_FRIENDLY
      PatternChoice.ON -> true
      PatternChoice.OFF -> false
    }
}

val LocalPhonoPalette = staticCompositionLocalOf { PhonoPalette(ColorChoice.STANDARD, PatternChoice.FOLLOW_COLORS, false) }

/**
 * The app theme (architecture 17, design 12.1): an explicit Material 3 scheme for light and dark (dynamic colour off:
 * the palette is the identity), [PhonoTheme.colors] for the roles Material has no slot for, the type scale with the
 * three bundled faces, shapes, and motion that honours [reducedMotion].
 *
 * Dark mode (design 12.1, 12.4): Compose colours follow [darkTheme]; when [darkTheme] differs from the configuration's
 * night mode (the in-app appearance is Light or Dark and the system says otherwise), the content gets a configuration
 * context whose `uiMode` follows the in-app choice, so `drawable-night` illustrations and splash art resolve with it.
 * Window and splash colours come from `values-night`, which [NightMode.apply] aligns on API 31+.
 */
@Composable
fun PhonoTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  colors: ColorChoice = ColorChoice.STANDARD,
  patterns: PatternChoice = PatternChoice.FOLLOW_COLORS,
  reducedMotion: Boolean = false,
  content: @Composable () -> Unit,
) {
  val phonoColors = remember(darkTheme, colors) { phonoColors(darkTheme, colors) }
  val palette = remember(colors, patterns, reducedMotion) { PhonoPalette(colors, patterns, reducedMotion) }
  val motion = remember(reducedMotion) { PhonoMotionScheme(reducedMotion) }
  AppearanceConfiguration(darkTheme) {
    CompositionLocalProvider(
      LocalPhonoColors provides phonoColors,
      LocalPhonoPalette provides palette,
      LocalPhonoType provides DefaultPhonoType,
      LocalPhonoMotion provides motion,
    ) {
      MaterialTheme(
        colorScheme = phonoColorScheme(darkTheme),
        typography = phonoTypography,
        shapes = phonoShapes,
      ) {
        CompositionLocalProvider(LocalContentColor provides phonoColors.ink, content = content)
      }
    }
  }
}

private val DefaultPhonoType = PhonoType()

/**
 * Gives [content] a context, configuration and resources whose night mode is [darkTheme] when the surrounding
 * configuration disagrees (design 12.1). The context's theme is its own `Theme.Phono`, so Compose's vector cache,
 * keyed by theme and resource id, never serves a drawable resolved in one mode to the other.
 */
@Composable
fun AppearanceConfiguration(darkTheme: Boolean, content: @Composable () -> Unit) {
  val base = LocalContext.current
  val configuration = LocalConfiguration.current
  val override = NightMode.isNight(configuration) != darkTheme
  // The same composition structure in both cases, so switching the appearance never resets the content's state.
  val themed = remember(base, configuration, darkTheme) { if (override) NightMode.contextFor(base, darkTheme) else base }
  CompositionLocalProvider(
    LocalContext provides themed,
    LocalConfiguration provides if (override) themed.resources.configuration else configuration,
    LocalResources provides if (override) themed.resources else LocalResources.current,
    content = content,
  )
}

object PhonoTheme {
  /** Every role of design 2.1 to 2.4 for the current theme and palette. */
  val colors: PhonoColors
    @Composable @ReadOnlyComposable
    get() = LocalPhonoColors.current

  /** The palette and pattern choices the theme received. */
  val palette: PhonoPalette
    @Composable @ReadOnlyComposable
    get() = LocalPhonoPalette.current

  /** Whether marks carry their pattern icons (design 9.4). */
  val patterns: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalPhonoPalette.current.showPatterns

  /** The styles design 4.2 adds to Material's type scale. */
  val type: PhonoType
    @Composable @ReadOnlyComposable
    get() = LocalPhonoType.current

  /** Motion specs that become snaps when motion is reduced. */
  val motion: PhonoMotionScheme
    @Composable @ReadOnlyComposable
    get() = LocalPhonoMotion.current
}

/** Whether [appearance] draws dark, given the system's own setting. */
@Composable
@ReadOnlyComposable
fun Appearance.isDark(): Boolean = when (this) {
  Appearance.SYSTEM -> isSystemInDarkTheme()
  Appearance.LIGHT -> false
  Appearance.DARK -> true
}
