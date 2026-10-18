package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > Notifications (spec F-23): the two kinds with their switches, Android's settings, and "Notifications are
 * off for PhonoTrainer" with "Open settings" after a refusal or "Turn on notifications" when the app never asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class NotificationSettingsTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = NotificationSettingsActions(
    onBack = { calls += "back" },
    onReady = { calls += "ready:$it" },
    onAttention = { calls += "attention:$it" },
    onSystemSettings = { calls += "system" },
    onTurnOn = { calls += "turnOn" },
  )

  @Test
  fun withNotificationsAllowedTheTwoKindsAreOnAndNothingIsOff() {
    screen.checkEach(ScreenVariant.REQUIRED, { NotificationSettingsScreen(NotificationSettingsUiState(NotificationPermission.GRANTED), actions) }) {
      compose.onNodeWithTag(SettingsTags.NOTIFY_OFF).assertDoesNotExist()
      compose.onNodeWithTag(SettingsTags.NOTIFY_READY).assertIsOn()
      compose.onNodeWithTag(SettingsTags.NOTIFY_ATTENTION).assertIsOn()
      assertThat(compose.textsOf(SettingsTags.NOTIFY_READY)).contains("When a clip is ready")
      assertThat(compose.textsOf(SettingsTags.NOTIFY_ATTENTION)).contains("When a request needs attention")
      assertThat(compose.textsOf(SettingsTags.NOTIFY_SYSTEM)).contains("Notification settings")
    }
    compose.onNodeWithTag(SettingsTags.NOTIFY_READY).performClick()
    compose.onNodeWithTag(SettingsTags.NOTIFY_ATTENTION).performClick()
    compose.onNodeWithTag(SettingsTags.NOTIFY_SYSTEM).performClick()
    assertThat(calls).containsExactly("ready:false", "attention:false", "system").inOrder()
  }

  @Test
  fun aRefusalSaysNotificationsAreOffAndOpensAndroidsSettings() {
    val state = NotificationSettingsUiState(NotificationPermission.DENIED, ready = false, attention = true)
    screen.checkEach(ScreenVariant.REQUIRED, { NotificationSettingsScreen(state, actions) }) {
      val off = compose.textsOf(SettingsTags.NOTIFY_OFF)
      assertThat(off).contains("Notifications are off for PhonoTrainer")
      assertThat(off).contains("Open settings")
      compose.onNodeWithTag(SettingsTags.NOTIFY_TURN_ON).assertDoesNotExist()
      compose.onNodeWithTag(SettingsTags.NOTIFY_READY).assertIsOff()
    }
    compose.onNodeWithTag(SettingsTags.NOTIFY_OPEN_SETTINGS).performClick()
    assertThat(calls).containsExactly("system")
  }

  @Test
  fun anAppThatNeverAskedOffersToTurnThemOn() {
    screen.checkEach(ScreenVariant.REQUIRED, { NotificationSettingsScreen(NotificationSettingsUiState(NotificationPermission.NOT_ASKED), actions) }) {
      assertThat(compose.textsOf(SettingsTags.NOTIFY_OFF)).contains("Notifications are off for PhonoTrainer")
      compose.onNodeWithTag(SettingsTags.NOTIFY_OPEN_SETTINGS).assertDoesNotExist()
    }
    compose.onNodeWithTag(SettingsTags.NOTIFY_TURN_ON).performClick()
    assertThat(calls).containsExactly("turnOn")
  }
}
