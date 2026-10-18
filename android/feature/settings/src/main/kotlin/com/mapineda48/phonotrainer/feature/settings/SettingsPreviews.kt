package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission

// Previews of every state of :feature:settings (work-plan 2.5 item 7), on the samples the screen tests use.

@PreviewLightDark
@Composable
private fun SettingsPreview() {
  PhonoPreviewSurface { SettingsScreen(SettingsSamples.settings, SettingsActions()) }
}

@PreviewLightDark
@Composable
private fun SettingsColorVisionPreview() {
  PhonoPreviewSurface { SettingsScreen(SettingsSamples.settingsCvd, SettingsActions()) }
}

@PreviewLightDark
@Composable
private fun MixPreview() {
  PhonoPreviewSurface { MixContent(SettingsSamples.settings.clips, onInMix = { _, _ -> }, onDone = {}) }
}

@PreviewLightDark
@Composable
private fun MixEmptyPreview() {
  PhonoPreviewSurface { MixContent(emptyList(), onInMix = { _, _ -> }, onDone = {}) }
}

@PreviewLightDark
@Composable
private fun YourComputerPreview() {
  PhonoPreviewSurface { YourComputerScreen(SettingsSamples.connected, YourComputerActions()) }
}

@PreviewLightDark
@Composable
private fun YourComputerUnreachablePreview() {
  PhonoPreviewSurface { YourComputerScreen(SettingsSamples.unreachable, YourComputerActions()) }
}

@PreviewLightDark
@Composable
private fun YourComputerNonePreview() {
  PhonoPreviewSurface { YourComputerScreen(SettingsSamples.notConnected, YourComputerActions()) }
}

@PreviewLightDark
@Composable
private fun YourComputerForgottenPreview() {
  PhonoPreviewSurface { YourComputerScreen(SettingsSamples.forgotten, YourComputerActions()) }
}

@PreviewLightDark
@Composable
private fun ForgetDialogPreview() {
  PhonoPreviewSurface {
    ConfirmCard(
      title = stringResource(R.string.computer_forget_title),
      body = stringResource(R.string.computer_forget_body, SettingsSamples.computer.name),
      cancelLabel = stringResource(R.string.computer_forget_cancel),
      confirmLabel = stringResource(R.string.computer_forget_confirm),
      onConfirm = {},
      onDismiss = {},
      tags = FORGET_TAGS,
    )
  }
}

@PreviewLightDark
@Composable
private fun StoragePreview() {
  PhonoPreviewSurface { StorageScreen(SettingsSamples.storage, StorageActions()) }
}

@PreviewLightDark
@Composable
private fun StorageLimitsPreview() {
  PhonoPreviewSurface { StorageScreen(SettingsSamples.storageLimits, StorageActions()) }
}

@PreviewLightDark
@Composable
private fun StorageFirstRunPreview() {
  PhonoPreviewSurface { StorageScreen(SettingsSamples.storageFirstRun, StorageActions()) }
}

@PreviewLightDark
@Composable
private fun StorageLoadingPreview() {
  PhonoPreviewSurface { StorageScreen(StorageUiState(), StorageActions()) }
}

@PreviewLightDark
@Composable
private fun StorageErrorPreview() {
  PhonoPreviewSurface { StorageScreen(StorageUiState(read = StorageRead.Failed), StorageActions()) }
}

@PreviewLightDark
@Composable
private fun RemoveFromPhonePreview() {
  PhonoPreviewSurface {
    ConfirmCard(
      title = stringResource(R.string.storage_remove_title, SettingsSamples.usage.perClip.first().title),
      body = stringResource(R.string.storage_remove_body),
      cancelLabel = stringResource(R.string.storage_remove_cancel),
      confirmLabel = stringResource(R.string.storage_remove_confirm),
      onConfirm = {},
      onDismiss = {},
      tags = REMOVE_TAGS,
    )
  }
}

@PreviewLightDark
@Composable
private fun NotificationsPreview() {
  PhonoPreviewSurface { NotificationSettingsScreen(NotificationSettingsUiState(NotificationPermission.GRANTED), NotificationSettingsActions()) }
}

@PreviewLightDark
@Composable
private fun NotificationsDeniedPreview() {
  PhonoPreviewSurface {
    NotificationSettingsScreen(NotificationSettingsUiState(NotificationPermission.DENIED, ready = false), NotificationSettingsActions())
  }
}

@PreviewLightDark
@Composable
private fun NotificationsNeverAskedPreview() {
  PhonoPreviewSurface { NotificationSettingsScreen(NotificationSettingsUiState(NotificationPermission.NOT_ASKED), NotificationSettingsActions()) }
}

@PreviewLightDark
@Composable
private fun HelpPreview() {
  PhonoPreviewSurface { HelpScreen(HelpActions()) }
}

@PreviewLightDark
@Composable
private fun AboutPreview() {
  PhonoPreviewSurface { AboutScreen(AboutUiState(version = "1.0.0", computerApi = 6), AboutActions()) }
}

@PreviewLightDark
@Composable
private fun AboutNoComputerPreview() {
  PhonoPreviewSurface { AboutScreen(AboutUiState(version = "1.0.0"), AboutActions()) }
}

@PreviewLightDark
@Composable
private fun LicencesPreview() {
  PhonoPreviewSurface { LicencesScreen(LicencesUiState(LicencesRead.Ready(SettingsSamples.catalogue)), LicencesActions()) }
}

@PreviewLightDark
@Composable
private fun LicencesLoadingPreview() {
  PhonoPreviewSurface { LicencesScreen(LicencesUiState(), LicencesActions()) }
}

@PreviewLightDark
@Composable
private fun LicencesErrorPreview() {
  PhonoPreviewSurface { LicencesScreen(LicencesUiState(LicencesRead.Failed), LicencesActions()) }
}

@PreviewLightDark
@Composable
private fun LicenceTextPreview() {
  PhonoPreviewSurface { LicenceText(SettingsSamples.licenceText, onClose = {}) }
}
