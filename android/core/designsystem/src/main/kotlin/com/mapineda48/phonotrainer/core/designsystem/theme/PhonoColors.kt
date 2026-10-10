package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Roles Material 3 has no slot for (architecture 17, design 2.1). Placeholder at the contract freeze: the neutral
 * tokens only; WP-03 adds the family hues, the articulator and illustration palettes (additively).
 */
@Immutable
data class PhonoColors(
  val page: Color,
  val sheet: Color,
  val galley: Color,
  val ink: Color,
  val ink2: Color,
  val inkMuted: Color,
  val onInk: Color,
  val hairline: Color,
  val focus: Color,
  val good: Color,
  val critical: Color,
  val scrim: Color,
)

internal val LightPhonoColors = PhonoColors(
  page = Color(0xFFF8F6EF),
  sheet = Color(0xFFFFFDF8),
  galley = Color(0xFFEFEBE2),
  ink = Color(0xFF1B1A18),
  ink2 = Color(0xFF45433E),
  inkMuted = Color(0xFF5D5A53),
  onInk = Color(0xFFFFFDF8),
  hairline = Color(0xFFDBD9D3),
  focus = Color(0xFF1F5FD1),
  good = Color(0xFF0B7D0B),
  critical = Color(0xFFC0362F),
  scrim = Color(0x75111110),
)

internal val DarkPhonoColors = PhonoColors(
  page = Color(0xFF121110),
  sheet = Color(0xFF1C1B19),
  galley = Color(0xFF272522),
  ink = Color(0xFFF3F0E8),
  ink2 = Color(0xFFD0CCC2),
  inkMuted = Color(0xFFA6A297),
  onInk = Color(0xFF121110),
  hairline = Color(0xFF2F2E2C),
  focus = Color(0xFF8AB4FF),
  good = Color(0xFF3FBF55),
  critical = Color(0xFFFF6B5E),
  scrim = Color(0x9E000000),
)

val LocalPhonoColors = staticCompositionLocalOf { LightPhonoColors }
