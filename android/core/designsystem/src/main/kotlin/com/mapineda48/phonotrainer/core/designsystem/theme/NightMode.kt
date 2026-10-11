package com.mapineda48.phonotrainer.core.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import com.mapineda48.phonotrainer.core.designsystem.R
import com.mapineda48.phonotrainer.core.domain.model.Appearance

/**
 * The dark-mode strategy of design 12.1 and 12.4, outside Compose:
 * - [apply] hands the in-app appearance to the system, so the window, the splash of the next cold start and the
 *   `values-night` colours follow it (API 31+: the per-app night mode; a value that does not change the effective
 *   night mode does not recreate the activity). Below 31 the window follows the system, and Compose follows the
 *   setting through [PhonoTheme]. The app shell calls it whenever `UserSettings.appearance` is read or changes.
 * - [contextFor] is the configuration context [PhonoTheme] gives its content when the in-app appearance differs from
 *   the configuration's, so `drawable-night` resources (illustrations, the splash mark) match the choice.
 */
object NightMode {
  fun apply(context: Context, appearance: Appearance) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    manager.setApplicationNightMode(modeOf(appearance))
  }

  /** The `UiModeManager` night mode of [appearance]. */
  fun modeOf(appearance: Appearance): Int = when (appearance) {
    Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
    Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
  }

  /** Whether [configuration] is in night mode. */
  fun isNight(configuration: Configuration): Boolean =
    (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

  /** [configuration] with its night bits set to [dark]. */
  fun configurationFor(configuration: Configuration, dark: Boolean): Configuration = Configuration(configuration).apply {
    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
      (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
  }

  /**
   * [base] itself when its configuration already matches [dark]; otherwise a wrapper of [base] (the activity stays
   * reachable through `ContextWrapper.getBaseContext`) whose resources resolve with the night bits of [dark] and whose
   * theme is `Theme.Phono` in that mode.
   */
  fun contextFor(base: Context, dark: Boolean): Context {
    if (isNight(base.resources.configuration) == dark) return base
    return ContextThemeWrapper(base, R.style.Theme_Phono).apply {
      applyOverrideConfiguration(configurationFor(base.resources.configuration, dark))
    }
  }
}
