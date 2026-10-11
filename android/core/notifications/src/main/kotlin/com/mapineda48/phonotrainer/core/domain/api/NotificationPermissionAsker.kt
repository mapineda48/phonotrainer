package com.mapineda48.phonotrainer.core.domain.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The in-context notification question of spec F-23 (architecture 13), asked once, after the first request is safely
 * stored, by the share sheet (WP-23) and "Add a clip" (WP-22): "Get a notification when your clip is ready?" with
 * "Notify me" (then the system prompt) and "Not now" (no system prompt; never asked again in the app). A dismissal
 * counts as "Not now". Implemented by `:core:notifications` (WP-02), which keeps `Notifier.permissionState` current.
 *
 * Temporary location (work-plan 2.2): this file belongs in `:core:domain` (`api/`), where features can reach it; it is
 * written here, in the same package, so that the change request that moves it is a pure `git mv`.
 */
interface NotificationPermissionAsker {
  /** Whether to offer the question now: Android 13 or later, notifications not allowed, and never asked before. */
  val shouldAsk: StateFlow<Boolean>

  /**
   * The runtime permission to request with `ActivityResultContracts.RequestPermission` after "Notify me"
   * (`android.permission.POST_NOTIFICATIONS`); null below Android 13, where none exists.
   */
  val systemPermission: String?

  /** "Not now", or the question dismissed: remembered, so the app never asks again (Settings keeps the way back). */
  suspend fun notNow()

  /**
   * "Notify me": remembered like [notNow]. True when the caller must now launch the system prompt for
   * [systemPermission]; false when nothing more is needed.
   */
  suspend fun notifyMe(): Boolean

  /** The system prompt answered: `Notifier.permissionState` is read again. */
  fun onSystemPromptResult(granted: Boolean)
}
