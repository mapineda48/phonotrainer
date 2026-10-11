package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The explicit Material 3 mapping of design 2.2 (dynamic colour off: the palette is the identity, as in Artemi). Every
// Material component used with its defaults lands on a neutral token; the components of design 9 still set their
// colours explicitly. There is no third hue (tertiary = ink2), no red surface (errorContainer = galley) and no tonal
// overlay (surfaceTint transparent, tonal elevation always 0).

internal val phonoLightScheme: ColorScheme = with(Neutrals.Light) {
  lightColorScheme(
    primary = ink,
    onPrimary = onInk,
    primaryContainer = deepSheet,
    onPrimaryContainer = ink,
    inversePrimary = onInk,
    secondary = ink2,
    onSecondary = onInk,
    secondaryContainer = galley,
    onSecondaryContainer = ink,
    tertiary = ink2,
    onTertiary = onInk,
    tertiaryContainer = galley,
    onTertiaryContainer = ink,
    background = page,
    onBackground = ink,
    surface = page,
    onSurface = ink,
    surfaceVariant = galley,
    onSurfaceVariant = ink2,
    surfaceTint = Color.Transparent,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = critical,
    onError = onInk,
    errorContainer = galley,
    onErrorContainer = ink,
    outline = outline,
    outlineVariant = hairline,
    scrim = scrim,
    surfaceBright = sheet,
    surfaceContainer = sheet,
    surfaceContainerHigh = sheet,
    surfaceContainerHighest = deepSheet,
    surfaceContainerLow = sheet,
    surfaceContainerLowest = sheet,
    surfaceDim = deepSheet,
    primaryFixed = galley,
    primaryFixedDim = deepSheet,
    onPrimaryFixed = ink,
    onPrimaryFixedVariant = ink2,
    secondaryFixed = galley,
    secondaryFixedDim = deepSheet,
    onSecondaryFixed = ink,
    onSecondaryFixedVariant = ink2,
    tertiaryFixed = galley,
    tertiaryFixedDim = deepSheet,
    onTertiaryFixed = ink,
    onTertiaryFixedVariant = ink2,
  )
}

internal val phonoDarkScheme: ColorScheme = with(Neutrals.Dark) {
  darkColorScheme(
    primary = ink,
    onPrimary = onInk,
    primaryContainer = deepSheet,
    onPrimaryContainer = ink,
    inversePrimary = onInk,
    secondary = ink2,
    onSecondary = onInk,
    secondaryContainer = galley,
    onSecondaryContainer = ink,
    tertiary = ink2,
    onTertiary = onInk,
    tertiaryContainer = galley,
    onTertiaryContainer = ink,
    background = page,
    onBackground = ink,
    surface = page,
    onSurface = ink,
    surfaceVariant = galley,
    onSurfaceVariant = ink2,
    surfaceTint = Color.Transparent,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = critical,
    onError = onInk,
    errorContainer = galley,
    onErrorContainer = ink,
    outline = outline,
    outlineVariant = hairline,
    scrim = scrim,
    surfaceBright = deepSheet,
    surfaceContainer = sheet,
    surfaceContainerHigh = sheet,
    surfaceContainerHighest = deepSheet,
    surfaceContainerLow = sheet,
    surfaceContainerLowest = sheet,
    surfaceDim = page,
    primaryFixed = galley,
    primaryFixedDim = deepSheet,
    onPrimaryFixed = ink,
    onPrimaryFixedVariant = ink2,
    secondaryFixed = galley,
    secondaryFixedDim = deepSheet,
    onSecondaryFixed = ink,
    onSecondaryFixedVariant = ink2,
    tertiaryFixed = galley,
    tertiaryFixedDim = deepSheet,
    onTertiaryFixed = ink,
    onTertiaryFixedVariant = ink2,
  )
}

/** The Material 3 scheme of [darkTheme] (design 2.2). */
fun phonoColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) phonoDarkScheme else phonoLightScheme
