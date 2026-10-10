package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:settings at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-24 replaces them with its screens.

@Composable
internal fun SettingsStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_title),
    lede = stringResource(R.string.settings_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.settings_link_computer),
      onClick = { onNavigate(YourComputerKey) },
      modifier = Modifier.testTag(SettingsTags.LINK_COMPUTER),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.settings_link_storage),
      onClick = { onNavigate(StorageKey) },
      modifier = Modifier.testTag(SettingsTags.LINK_STORAGE),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.settings_link_notifications),
      onClick = { onNavigate(NotificationSettingsKey) },
      modifier = Modifier.testTag(SettingsTags.LINK_NOTIFICATIONS),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.settings_link_help),
      onClick = { onNavigate(HelpKey) },
      modifier = Modifier.testTag(SettingsTags.LINK_HELP),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.settings_link_about),
      onClick = { onNavigate(AboutKey) },
      modifier = Modifier.testTag(SettingsTags.LINK_ABOUT),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun SettingsStubScreenPreview() {
  PhonoTheme { SettingsStubScreen(onNavigate = {}) }
}

@Composable
internal fun StorageStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_storage_title),
    lede = stringResource(R.string.settings_storage_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun StorageStubScreenPreview() {
  PhonoTheme { StorageStubScreen() }
}

@Composable
internal fun YourComputerStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_computer_title),
    lede = stringResource(R.string.settings_computer_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun YourComputerStubScreenPreview() {
  PhonoTheme { YourComputerStubScreen() }
}

@Composable
internal fun NotificationSettingsStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_notifications_title),
    lede = stringResource(R.string.settings_notifications_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun NotificationSettingsStubScreenPreview() {
  PhonoTheme { NotificationSettingsStubScreen() }
}

@Composable
internal fun AboutStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_about_title),
    lede = stringResource(R.string.settings_about_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.settings_about_link_licences),
      onClick = { onNavigate(LicencesKey) },
      modifier = Modifier.testTag(SettingsTags.ABOUT_LICENCES),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun AboutStubScreenPreview() {
  PhonoTheme { AboutStubScreen(onNavigate = {}) }
}

@Composable
internal fun LicencesStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_licences_title),
    lede = stringResource(R.string.settings_licences_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun LicencesStubScreenPreview() {
  PhonoTheme { LicencesStubScreen() }
}

@Composable
internal fun HelpStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.settings_help_title),
    lede = stringResource(R.string.settings_help_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.settings_help_link_tour),
      onClick = { onNavigate(WelcomeKey(restart = true)) },
      modifier = Modifier.testTag(SettingsTags.HELP_TOUR),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun HelpStubScreenPreview() {
  PhonoTheme { HelpStubScreen(onNavigate = {}) }
}
