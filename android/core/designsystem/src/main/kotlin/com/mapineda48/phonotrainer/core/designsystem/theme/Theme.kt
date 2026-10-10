package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice

/** The palette and pattern choices of Settings > Display, as the theme received them. */
@Immutable
data class PhonoPalette(val colors: ColorChoice, val patterns: PatternChoice, val reducedMotion: Boolean)

val LocalPhonoPalette = staticCompositionLocalOf { PhonoPalette(ColorChoice.STANDARD, PatternChoice.FOLLOW_COLORS, false) }

/**
 * The app theme (architecture 17): an explicit Material 3 scheme for light and dark plus [PhonoTheme.colors].
 * Placeholder at the contract freeze (neutral tokens of design 2.1 and 2.2, platform typography and shapes); WP-03
 * fills type, shapes, family hues and motion without changing this signature.
 */
@Composable
fun PhonoTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  colors: ColorChoice = ColorChoice.STANDARD,
  patterns: PatternChoice = PatternChoice.FOLLOW_COLORS,
  reducedMotion: Boolean = false,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalPhonoColors provides if (darkTheme) DarkPhonoColors else LightPhonoColors,
    LocalPhonoPalette provides PhonoPalette(colors, patterns, reducedMotion),
  ) {
    MaterialTheme(colorScheme = if (darkTheme) phonoDarkScheme else phonoLightScheme, content = content)
  }
}

object PhonoTheme {
  val colors: PhonoColors
    @Composable @ReadOnlyComposable
    get() = LocalPhonoColors.current

  val palette: PhonoPalette
    @Composable @ReadOnlyComposable
    get() = LocalPhonoPalette.current
}

/** Whether [appearance] draws dark, given the system's own setting. */
@Composable
@ReadOnlyComposable
fun Appearance.isDark(): Boolean = when (this) {
  Appearance.SYSTEM -> isSystemInDarkTheme()
  Appearance.LIGHT -> false
  Appearance.DARK -> true
}
