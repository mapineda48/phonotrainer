package com.mapineda48.phonotrainer.core.notifications

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The F-23 question over [NotificationPermissionMonitor]: offered while the permission reads NOT_ASKED; either answer
 * stores `UserSettings.notificationsAsked`, so it is never offered again (architecture 13).
 */
@Singleton
class AndroidNotificationPermissionAsker @Inject constructor(
  private val monitor: NotificationPermissionMonitor,
  private val settings: SettingsRepository,
  @ApplicationScope scope: CoroutineScope,
) : NotificationPermissionAsker {
  override val shouldAsk: StateFlow<Boolean> = monitor.state
    .map { it == NotificationPermission.NOT_ASKED }
    .stateIn(scope, SharingStarted.Eagerly, monitor.state.value == NotificationPermission.NOT_ASKED)

  override val systemPermission: String? =
    NotificationPermissionRule.POST_NOTIFICATIONS.takeIf {
      monitor.sdkInt >= NotificationPermissionRule.RUNTIME_PERMISSION_SDK
    }

  override suspend fun notNow() {
    remember()
  }

  override suspend fun notifyMe(): Boolean {
    remember()
    return systemPermission != null && monitor.state.value != NotificationPermission.GRANTED
  }

  override fun onSystemPromptResult(granted: Boolean) {
    monitor.refresh()
  }

  private suspend fun remember() {
    settings.update { it.copy(notificationsAsked = true) }
    monitor.refresh()
  }
}
