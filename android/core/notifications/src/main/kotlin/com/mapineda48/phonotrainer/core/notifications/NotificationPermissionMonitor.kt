package com.mapineda48.phonotrainer.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How the notification permission reads (architecture 13), from what the system and the settings say. */
object NotificationPermissionRule {
  /** The first Android version with the runtime permission (13). */
  const val RUNTIME_PERMISSION_SDK: Int = Build.VERSION_CODES.TIRAMISU

  /** The runtime permission, written out so no newer-API constant is inlined. */
  const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"

  /**
   * Below Android 13 no permission exists: [NotificationPermission.NOT_NEEDED], unless the learner turned
   * notifications off in the system settings, which reads as [NotificationPermission.DENIED] so Settings says
   * "Notifications are off for PhonoTrainer". From 13 on: granted and enabled is GRANTED; never asked (in the app) and
   * not granted is NOT_ASKED; anything else is DENIED.
   */
  fun state(sdkInt: Int, permissionGranted: Boolean, notificationsEnabled: Boolean, asked: Boolean) = when {
    sdkInt < RUNTIME_PERMISSION_SDK ->
      if (notificationsEnabled) NotificationPermission.NOT_NEEDED else NotificationPermission.DENIED
    permissionGranted && notificationsEnabled -> NotificationPermission.GRANTED
    permissionGranted || asked -> NotificationPermission.DENIED
    else -> NotificationPermission.NOT_ASKED
  }

  /** Whether anything may be posted in [state]. */
  fun allowsPosting(state: NotificationPermission): Boolean =
    state == NotificationPermission.GRANTED || state == NotificationPermission.NOT_NEEDED
}

/**
 * Keeps `Notifier.permissionState` current (architecture 13): read again when the settings' "asked" flag changes,
 * whenever an activity of the app resumes ([NotificationsStartup]), after the system prompt, and before every post.
 */
@Singleton
class NotificationPermissionMonitor internal constructor(
  private val context: Context,
  private val settings: SettingsRepository,
  scope: CoroutineScope,
  /** The device's Android version; tests pass another. */
  val sdkInt: Int,
) {
  @Inject
  constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
  ) : this(context, settings, scope, Build.VERSION.SDK_INT)

  private val manager = context.getSystemService(NotificationManager::class.java)
  private val mutable = MutableStateFlow(read())

  val state: StateFlow<NotificationPermission> = mutable.asStateFlow()

  init {
    scope.launch {
      settings.settings.map { it.notificationsAsked }.distinctUntilChanged().collect { refresh() }
    }
  }

  /** Reads the permission again and returns it. */
  fun refresh(): NotificationPermission = read().also { mutable.value = it }

  private fun read(): NotificationPermission = NotificationPermissionRule.state(
    sdkInt = sdkInt,
    permissionGranted = sdkInt < NotificationPermissionRule.RUNTIME_PERMISSION_SDK ||
      context.checkSelfPermission(NotificationPermissionRule.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    notificationsEnabled = manager.areNotificationsEnabled(),
    asked = settings.settings.value.notificationsAsked,
  )
}
