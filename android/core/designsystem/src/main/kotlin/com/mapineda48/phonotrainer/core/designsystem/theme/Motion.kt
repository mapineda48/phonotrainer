package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens of design 8, "the pen moves, the paper doesn't": durations, easings and the selection spring. No
 * infinite decorative loop exists anywhere (A-07).
 */
object PhonoMotion {
  const val FAST_MS: Int = 120
  const val STANDARD_MS: Int = 220
  const val SLOW_MS: Int = 360
  const val DRAW_MS: Int = 700
  const val PAGER_MS: Int = 280
  const val SHEET_ENTER_MS: Int = 300
  const val PRINT_MS: Int = 160

  /** The web's `--ease`. */
  val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

  /** Emphasized decelerate: sheets entering. */
  val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

  /** Emphasized accelerate: sheets leaving. */
  val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

  const val SELECTION_DAMPING: Float = 0.85f
  const val SELECTION_STIFFNESS: Float = 400f
}

/**
 * The motion of the current theme: every spec becomes a snap when motion is reduced (the system animator scale is 0
 * or Settings > Motion says "Reduce", architecture 18), so a component asks this scheme and never decides by itself.
 * Content motion that follows playback (the trace, the nib) does not animate and is not affected.
 */
@Immutable
class PhonoMotionScheme(val reduced: Boolean) {
  fun <T> fast(): FiniteAnimationSpec<T> = timed(PhonoMotion.FAST_MS, PhonoMotion.Standard)

  fun <T> standard(): FiniteAnimationSpec<T> = timed(PhonoMotion.STANDARD_MS, PhonoMotion.Standard)

  fun <T> slow(): FiniteAnimationSpec<T> = timed(PhonoMotion.SLOW_MS, PhonoMotion.Standard)

  fun <T> enter(): FiniteAnimationSpec<T> = timed(PhonoMotion.SHEET_ENTER_MS, PhonoMotion.Enter)

  fun <T> exit(): FiniteAnimationSpec<T> = timed(PhonoMotion.STANDARD_MS, PhonoMotion.Exit)

  fun <T> draw(): FiniteAnimationSpec<T> = timed(PhonoMotion.DRAW_MS, PhonoMotion.Standard)

  /** Toggles, chips, segmented controls: a low-bounce spring. */
  fun <T> selection(): FiniteAnimationSpec<T> =
    if (reduced) snap() else spring(PhonoMotion.SELECTION_DAMPING, PhonoMotion.SELECTION_STIFFNESS)

  private fun <T> timed(ms: Int, easing: Easing): FiniteAnimationSpec<T> = if (reduced) snap() else tween(ms, 0, easing)

  override fun equals(other: Any?): Boolean = other is PhonoMotionScheme && other.reduced == reduced

  override fun hashCode(): Int = reduced.hashCode()
}

val LocalPhonoMotion = staticCompositionLocalOf { PhonoMotionScheme(reduced = false) }
