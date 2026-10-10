package com.mapineda48.phonotrainer.core.ui.a11y

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice

// The accessibility hooks of architecture 18 (spec section 10).

/** True when motion is reduced: the system animator scale is 0 or Settings > Motion says "Reduce". */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True while TalkBack (touch exploration) is on: autoplay and the share sheet's auto-close are off (A-08, A-11). */
val LocalTalkBackOn = staticCompositionLocalOf { false }

/** One summary for a short plus its custom actions (Play/Pause, Next short, … F-07-AC11). */
fun Modifier.shortSemantics(summary: String, actions: List<CustomAccessibilityAction>): Modifier =
  semantics(mergeDescendants = true) {
    contentDescription = summary
    customActions = actions
  }

/** Announces [title] when the pane appears (destinations and sheets). */
fun Modifier.paneTitle(title: String): Modifier = semantics { paneTitle = title }

/** [LocalReducedMotion]'s value: the system's animator scale combined with the in-app [choice]. */
@Composable
fun rememberReducedMotion(choice: MotionChoice): Boolean {
  val context = LocalContext.current
  return remember(choice, context) { choice == MotionChoice.REDUCE || animatorScale(context) == 0f }
}

/** [LocalTalkBackOn]'s value, following the touch-exploration state while composed. */
@Composable
fun rememberTalkBackOn(): Boolean {
  val context = LocalContext.current
  val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
  var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
  DisposableEffect(manager) {
    val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
    manager?.addTouchExplorationStateChangeListener(listener)
    onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
  }
  return on
}

private fun animatorScale(context: Context): Float =
  Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
