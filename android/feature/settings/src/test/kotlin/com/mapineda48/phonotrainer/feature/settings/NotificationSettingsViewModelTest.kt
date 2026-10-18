package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.FakeNotifier
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeNotificationPermissionAsker
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Settings > Notifications' state and actions (spec F-23): the two kinds and the way to turn notifications on. */
class NotificationSettingsViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  @Test
  fun theViewModelWritesTheTwoKindsAndAsksThroughTheAsker() = runTest {
    val settings = FakeSettingsRepository()
    val asker = FakeNotificationPermissionAsker()
    val notifier = FakeNotifier(NotificationPermission.NOT_ASKED)
    val viewModel = NotificationSettingsViewModel(settings, asker, notifier)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
    assertThat(viewModel.state.value.off).isTrue()

    viewModel.setReady(false)
    viewModel.setAttention(false)
    assertThat(settings.state.value.notifyReady).isFalse()
    assertThat(settings.state.value.notifyAttention).isFalse()

    var prompted = 0
    viewModel.turnOn { prompted++ }
    assertThat(asker.answers).containsExactly("notify_me")
    assertThat(prompted).isEqualTo(1)
    viewModel.onPromptResult(true)
    assertThat(asker.promptResults).containsExactly(true)

    notifier.permission.value = NotificationPermission.GRANTED
    assertThat(viewModel.state.value.off).isFalse()
  }

  @Test
  fun belowAndroid13NoPromptFollows() = runTest {
    val asker = FakeNotificationPermissionAsker(systemPermission = null)
    val viewModel = NotificationSettingsViewModel(FakeSettingsRepository(), asker, FakeNotifier(NotificationPermission.DENIED))
    var prompted = 0
    viewModel.turnOn { prompted++ }
    assertThat(prompted).isEqualTo(0)
  }
}
