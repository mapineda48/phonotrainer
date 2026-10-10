package com.mapineda48.phonotrainer.core.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.mapineda48.phonotrainer.core.domain.model.Appearance

/**
 * Applies the in-app appearance to the window and to the next cold start's splash (design 12.4, architecture 16.5):
 * on API 31+ the system keeps a per-app night mode, and a value that does not change the effective night mode does
 * not recreate the activity; below 31 the window follows the system and Compose follows the setting. The app shell
 * calls it when `UserSettings.appearance` is read or changes. WP-03 owns this helper after the contract freeze.
 */
object NightMode {
  fun apply(context: Context, appearance: Appearance) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    manager.setApplicationNightMode(
      when (appearance) {
        Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
        Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
      },
    )
  }
}
