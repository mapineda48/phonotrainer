package com.mapineda48.phonotrainer.e2e.smoke

import android.app.NotificationManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.notifications.AndroidNotifier
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.shell.ShellTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Work-plan WP-02 Proof (`PendingIntent` test, device half; the Robolectric half is in :core:notifications): a real
 * notification posted by the real `Notifier` opens its target through the internal alias, and a recreate after
 * opening it does not navigate again (architecture 13, 16.4).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class NotificationTapTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var notifier: Notifier

  private val robot = PhonoRobot(compose)
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val manager = context.getSystemService(NotificationManager::class.java)

  @After
  fun tearDown() {
    manager.cancelAll()
    if (robot.isLaunched()) robot.scenario.close()
  }

  @Test
  fun aNotificationOpensItsTargetOnceAndARecreateDoesNotNavigateAgain() {
    hilt.inject()
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    robot.launch()

    // A request this phone does not have: the target falls back to Requests with the spec's notice (16.4).
    val request = RequestId("6b1d5d6f-1c7f-4ce7-8a64-2a2c1ccf8d22")
    notifier.needsAttention(request, "A long talk", null, RequestError("too_long", null, null, false))
    val tag = AndroidNotifier.TAG_REQUEST + request.value
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { manager.activeNotifications.any { it.tag == tag } }

    manager.activeNotifications.single { it.tag == tag }.notification.contentIntent.send()
    robot.assertShown(RequestsKey)
    compose.waitUntilAtLeastOneExists(hasText("This request was removed."), PhonoRobot.TIMEOUT_MS)

    // Once applied, the target is in no intent the activity keeps (architecture 16.4)...
    robot.scenario.onActivity { assertThat(InternalEntry.targetOf(it.intent)).isNull() }
    robot.back()
    robot.assertShown(HomeKey)
    robot.scenario.recreate()
    robot.waitForTag(ShellTags.tab(Tab.HOME))
    robot.assertShown(HomeKey)
    // ...so a recreate does not open it again, not even late (its resolution is asynchronous).
    assertRequestsStayHidden()
    robot.scenario.onActivity { assertThat(InternalEntry.targetOf(it.intent)).isNull() }
  }

  /**
   * Requests does not appear for [STAYS_HIDDEN_MS]: a target is resolved in a coroutine (a lookup of the request), so
   * a check at the first idle frame alone could pass before a wrong navigation lands. A bounded wait, not a sleep.
   */
  private fun assertRequestsStayHidden() {
    val requests = hasTestTag(ShellTags.destination(RequestsKey))
    val appeared = try {
      compose.waitUntil(STAYS_HIDDEN_MS) { compose.onAllNodes(requests).fetchSemanticsNodes().isNotEmpty() }
      true
    } catch (_: ComposeTimeoutException) {
      false
    }
    assertThat(appeared).isFalse()
  }

  private companion object {
    const val STAYS_HIDDEN_MS = 3_000L
  }
}
