package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings > Notifications (spec F-23). */
@Immutable
data class NotificationSettingsUiState(
  val permission: NotificationPermission = NotificationPermission.NOT_NEEDED,
  val ready: Boolean = true,
  val attention: Boolean = true,
) {
  /** Nothing can be posted: the permission was refused, turned off in Android, or never given (Android 13+). */
  val off: Boolean get() = permission == NotificationPermission.DENIED || permission == NotificationPermission.NOT_ASKED
}

/**
 * Notifications (spec F-23): the two kinds this phone may post ("When a clip is ready", "When a request needs
 * attention", both on), the system's notification settings, and, when notifications are off for the app, "Notifications
 * are off for PhonoTrainer" with the way to turn them on: the system prompt when the app never asked (Android 13+;
 * the in-context question of F-23 is the other way), otherwise Android's settings (a refusal is the system's to undo).
 */
@HiltViewModel
internal class NotificationSettingsViewModel @Inject constructor(
  private val settings: SettingsRepository,
  private val asker: NotificationPermissionAsker,
  notifier: Notifier,
) : ViewModel() {
  val state: StateFlow<NotificationSettingsUiState> = combine(notifier.permissionState, settings.settings) { permission, current ->
    NotificationSettingsUiState(permission, current.notifyReady, current.notifyAttention)
  }.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    NotificationSettingsUiState(notifier.permissionState.value, settings.settings.value.notifyReady, settings.settings.value.notifyAttention),
  )

  /** The runtime permission the route asks for after [turnOn] says so. */
  val systemPermission: String? get() = asker.systemPermission

  fun setReady(on: Boolean) = change { it.copy(notifyReady = on) }

  fun setAttention(on: Boolean) = change { it.copy(notifyAttention = on) }

  /** "Turn on notifications": remembered as asked; [launchPrompt] runs when the system prompt must follow. */
  fun turnOn(launchPrompt: () -> Unit) {
    viewModelScope.launch {
      val prompt = try {
        asker.notifyMe()
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        false
      }
      if (prompt && asker.systemPermission != null) launchPrompt()
    }
  }

  fun onPromptResult(granted: Boolean) {
    asker.onSystemPromptResult(granted)
  }

  private fun change(edit: (UserSettings) -> UserSettings) {
    viewModelScope.launch {
      try {
        settings.update(edit)
      } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
      }
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** What Notifications' controls ask for. */
internal class NotificationSettingsActions(
  val onBack: () -> Unit = {},
  val onReady: (Boolean) -> Unit = {},
  val onAttention: (Boolean) -> Unit = {},
  /** "Open settings" (a refusal) and the "Notification settings" row: Android's page for this app. */
  val onSystemSettings: () -> Unit = {},
  /** "Turn on notifications" (never asked): the system prompt. */
  val onTurnOn: () -> Unit = {},
)

/** Settings > Notifications (spec F-23, design 10.2 P-12). */
@Composable
internal fun NotificationSettingsScreen(state: NotificationSettingsUiState, actions: NotificationSettingsActions, modifier: Modifier = Modifier) {
  SettingsPage(
    title = stringResource(R.string.notifications_title),
    lede = stringResource(R.string.notifications_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.NOTIFY_LIST,
    modifier = modifier,
  ) {
    if (state.off) {
      item(key = "off") {
        val neverAsked = state.permission == NotificationPermission.NOT_ASKED
        Notice(
          lead = stringResource(R.string.notifications_off),
          body = stringResource(R.string.notifications_off_body),
          kind = NoticeKind.CAUTION,
          modifier = Modifier.testTag(SettingsTags.NOTIFY_OFF),
          action = {
            PhonoButton(
              text = stringResource(if (neverAsked) R.string.notifications_turn_on else R.string.notifications_open_settings),
              onClick = if (neverAsked) actions.onTurnOn else actions.onSystemSettings,
              kind = PhonoButtonKind.SECONDARY,
              leadingIcon = if (neverAsked) PhonoIcons.Notifications else PhonoIcons.External,
              modifier = Modifier.testTag(if (neverAsked) SettingsTags.NOTIFY_TURN_ON else SettingsTags.NOTIFY_OPEN_SETTINGS),
            )
          },
        )
      }
    }
    item(key = "ready") {
      PhonoSwitch(
        label = stringResource(R.string.notifications_ready),
        description = stringResource(R.string.notifications_ready_body),
        checked = state.ready,
        onCheckedChange = actions.onReady,
        modifier = Modifier.testTag(SettingsTags.NOTIFY_READY),
      )
    }
    item(key = "attention") {
      PhonoSwitch(
        label = stringResource(R.string.notifications_attention),
        description = stringResource(R.string.notifications_attention_body),
        checked = state.attention,
        onCheckedChange = actions.onAttention,
        modifier = Modifier.testTag(SettingsTags.NOTIFY_ATTENTION),
      )
    }
    item(key = "system") {
      SettingsRow(
        icon = PhonoIcons.Notifications,
        title = stringResource(R.string.notifications_system),
        status = stringResource(R.string.notifications_system_body),
        trailing = PhonoIcons.External,
        onClick = actions.onSystemSettings,
        modifier = Modifier.testTag(SettingsTags.NOTIFY_SYSTEM),
      )
    }
    item(key = "scope") { Notice(stringResource(R.string.notifications_scope)) }
  }
}
