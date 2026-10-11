package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The 4 dp grid of design 5.2 (2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 32) and the sizes the components share. Screens
 * use these, never a literal (design 12.5).
 */
object PhonoSpacing {
  val s2: Dp = 2.dp
  val s4: Dp = 4.dp
  val s6: Dp = 6.dp
  val s8: Dp = 8.dp
  val s10: Dp = 10.dp
  val s12: Dp = 12.dp
  val s14: Dp = 14.dp
  val s16: Dp = 16.dp
  val s20: Dp = 20.dp
  val s24: Dp = 24.dp
  val s32: Dp = 32.dp

  /** Screen gutter. */
  val gutter: Dp = 16.dp

  /** The feed card's gutter. */
  val feedGutter: Dp = 14.dp

  /** Card padding. */
  val cardPadding: Dp = 16.dp

  /** Gap between sections of a screen. */
  val sectionGap: Dp = 20.dp

  /** Gap of card grids. */
  val gridGap: Dp = 12.dp

  /** Gap between chips. */
  val chipGap: Dp = 8.dp
}

/** Touch targets and fixed component sizes (design 5.2, 9, A-03). */
object PhonoSizes {
  /** The minimum touch target, everywhere (A-03). */
  val minTarget: Dp = 48.dp

  /** Quiz answers. */
  val quizAnswerMin: Dp = 56.dp

  /** An action-rail item's target. */
  val railItem: DpSize = DpSize(64.dp, 60.dp)

  /** Buttons: small and medium, large. */
  val buttonHeight: Dp = 48.dp
  val buttonHeightLarge: Dp = 56.dp

  /** Text fields. */
  val fieldHeight: Dp = 56.dp

  /** Rows of settings and lists. */
  val row: Dp = 56.dp

  /** Icon sizes of design 6: inline, in buttons and rows, in bars, in empty-state wells. */
  val iconInline: Dp = 16.dp
  val iconButton: Dp = 20.dp
  val iconBar: Dp = 24.dp
  val iconWell: Dp = 28.dp

  /** The focus ring (A-13): width and offset. */
  val focusRing: Dp = 3.dp
  val focusOffset: Dp = 2.dp

  /** The 1.5 dp ring of secondary buttons, chips and controls. */
  val ring: Dp = 1.5.dp

  /** Hairlines. */
  val hairline: Dp = 1.dp
}
