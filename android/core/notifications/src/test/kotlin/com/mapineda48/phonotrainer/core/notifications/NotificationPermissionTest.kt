package com.mapineda48.phonotrainer.core.notifications

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission.DENIED
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission.GRANTED
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission.NOT_ASKED
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission.NOT_NEEDED
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The permission state of architecture 13 and the in-context question of spec F-23. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val dispatcher = UnconfinedTestDispatcher()
  private val scope = CoroutineScope(dispatcher)
  private val settings = FakeSettingsRepository()

  private fun monitor(sdk: Int = 35) = NotificationPermissionMonitor(context, settings, scope, sdk)

  @After
  fun tearDown() = scope.cancel()

  @Test
  fun theRuleForEveryCase() {
    data class Case(val sdk: Int, val granted: Boolean, val enabled: Boolean, val asked: Boolean)

    val expected = mapOf(
      Case(32, granted = true, enabled = true, asked = false) to NOT_NEEDED,
      Case(32, granted = true, enabled = true, asked = true) to NOT_NEEDED,
      Case(32, granted = true, enabled = false, asked = false) to DENIED,
      Case(29, granted = true, enabled = true, asked = false) to NOT_NEEDED,
      Case(33, granted = true, enabled = true, asked = false) to GRANTED,
      Case(33, granted = true, enabled = true, asked = true) to GRANTED,
      Case(33, granted = true, enabled = false, asked = true) to DENIED,
      Case(33, granted = false, enabled = false, asked = false) to NOT_ASKED,
      Case(35, granted = false, enabled = true, asked = false) to NOT_ASKED,
      Case(35, granted = false, enabled = false, asked = true) to DENIED,
      Case(36, granted = true, enabled = true, asked = false) to GRANTED,
    )
    expected.forEach { (case, state) ->
      assertThat(NotificationPermissionRule.state(case.sdk, case.granted, case.enabled, case.asked)).isEqualTo(state)
    }
    assertThat(NotificationPermission.entries.filter(NotificationPermissionRule::allowsPosting))
      .containsExactly(GRANTED, NOT_NEEDED)
  }

  @Test
  fun theStateFollowsTheSystemAndTheSettings() {
    val monitor = monitor()
    assertThat(monitor.state.value).isEqualTo(NOT_ASKED)

    settings.state.value = settings.state.value.copy(notificationsAsked = true)
    assertThat(monitor.state.value).isEqualTo(DENIED)

    shadowOf(context).grantPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
    assertThat(monitor.refresh()).isEqualTo(GRANTED)

    shadowOf(manager).setNotificationsEnabled(false)
    assertThat(monitor.refresh()).isEqualTo(DENIED)
    assertThat(monitor.state.value).isEqualTo(DENIED)
  }

  @Test
  fun belowAndroid13NothingIsAsked() = runTest(dispatcher) {
    val monitor = monitor(sdk = 32)
    val asker = AndroidNotificationPermissionAsker(monitor, settings, scope)

    assertThat(monitor.state.value).isEqualTo(NOT_NEEDED)
    assertThat(asker.shouldAsk.value).isFalse()
    assertThat(asker.systemPermission).isNull()
    assertThat(asker.notifyMe()).isFalse()

    shadowOf(manager).setNotificationsEnabled(false)
    assertThat(monitor.refresh()).isEqualTo(DENIED)
  }

  @Test
  fun notNowIsRememberedAndNeverAskedAgain() = runTest(dispatcher) {
    val asker = AndroidNotificationPermissionAsker(monitor(), settings, scope)
    assertThat(asker.shouldAsk.value).isTrue()
    assertThat(asker.systemPermission).isEqualTo("android.permission.POST_NOTIFICATIONS")

    asker.notNow()

    assertThat(settings.state.value.notificationsAsked).isTrue()
    assertThat(asker.shouldAsk.value).isFalse()
    // A new process reads the same stored answer.
    assertThat(AndroidNotificationPermissionAsker(monitor(), settings, scope).shouldAsk.value).isFalse()
  }

  @Test
  fun notifyMeHandsOverToTheSystemPromptAndItsAnswerIsRead() = runTest(dispatcher) {
    val monitor = monitor()
    val asker = AndroidNotificationPermissionAsker(monitor, settings, scope)

    assertThat(asker.notifyMe()).isTrue()
    assertThat(asker.shouldAsk.value).isFalse()
    assertThat(monitor.state.value).isEqualTo(DENIED)

    shadowOf(context).grantPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
    asker.onSystemPromptResult(granted = true)
    assertThat(monitor.state.value).isEqualTo(GRANTED)
  }

  @Test
  fun startupCreatesTheChannelsAndAResumedActivityRereadsThePermission() {
    val monitor = monitor()
    NotificationsStartup(monitor).onApplicationCreate(context)
    assertThat(manager.notificationChannels).hasSize(3)
    assertThat(monitor.state.value).isEqualTo(NOT_ASKED)

    // Granted from the system's settings while the app was in the background.
    shadowOf(context).grantPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
    Robolectric.buildActivity(Activity::class.java).setup()

    assertThat(monitor.state.value).isEqualTo(GRANTED)
  }
}
