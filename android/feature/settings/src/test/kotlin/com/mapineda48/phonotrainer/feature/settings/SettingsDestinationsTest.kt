package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import org.junit.Test

/** Where Settings' controls lead (architecture 16.1). */
class SettingsDestinationsTest {
  @Test
  fun restartTheTourOpensTheWelcomeInItsRestartMode() {
    assertThat(SettingsDestinations.tour).isEqualTo(WelcomeKey(restart = true))
  }

  @Test
  fun yourComputersWaysOnOpenConnectInTheirModesOrConnectionDetails() {
    assertThat(SettingsDestinations.details).isEqualTo(ConnectionDetailsKey)
    assertThat(SettingsDestinations.newAddress).isEqualTo(ConnectKey(ConnectMode.NEW_ADDRESS))
    assertThat(SettingsDestinations.connectAgain).isEqualTo(ConnectKey(ConnectMode.CONNECT_AGAIN))
    assertThat(SettingsDestinations.connect).isEqualTo(ConnectKey(ConnectMode.FROM_HOME))
  }

  @Test
  fun everyStatusCardControlLeadsWhereHomesDoes() {
    val expected = mapOf(
      ConnectionAction.CONNECT to ConnectKey(ConnectMode.FROM_HOME),
      ConnectionAction.CONNECT_AGAIN to ConnectKey(ConnectMode.CONNECT_AGAIN),
      ConnectionAction.NEW_ADDRESS to ConnectKey(ConnectMode.NEW_ADDRESS),
      ConnectionAction.WHY to ConnectionDetailsKey,
      ConnectionAction.DETAILS to ConnectionDetailsKey,
      ConnectionAction.SEE_REQUESTS to RequestsKey,
      ConnectionAction.ATTENTION to RequestsKey,
      ConnectionAction.STORAGE to StorageKey,
      ConnectionAction.SYNC_NOW to null,
      ConnectionAction.BATTERY_SETTINGS to null,
    )
    assertThat(expected.keys).containsExactlyElementsIn(ConnectionAction.entries)
    expected.forEach { (action, key) -> assertThat(SettingsDestinations.card(action)).isEqualTo(key) }
  }
}
