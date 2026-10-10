package com.mapineda48.phonotrainer.e2e

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.mapineda48.phonotrainer.MainActivity
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.shell.AppNavigator
import com.mapineda48.phonotrainer.shell.ShellTags

/**
 * The façade robot of the end-to-end suite (architecture 21.5, integrator-owned): launch, navigate through the real
 * shell, and wait for what is on screen. Area robots (`e2e/<area>/`) build on it. Every action returns after its
 * effect is visible; waits have timeouts and never sleep.
 */
@OptIn(ExperimentalTestApi::class)
class PhonoRobot(private val compose: ComposeTestRule) {
  lateinit var scenario: ActivityScenario<MainActivity>
    private set

  val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  fun isLaunched(): Boolean = ::scenario.isInitialized

  fun launch(): PhonoRobot {
    scenario = ActivityScenario.launch(MainActivity::class.java)
    waitForTag(ShellTags.tab(Tab.HOME))
    return this
  }

  /** Delivers [intent] to the running app (onNewIntent of the singleTask activity). */
  fun deliver(intent: Intent) {
    InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    compose.waitForIdle()
  }

  /** Runs [action] with the shell's navigator on the main thread. */
  internal fun onNavigator(action: (AppNavigator) -> Unit) {
    scenario.onActivity { action(checkNotNull(it.navigator) { "The shell has not composed yet" }) }
    compose.waitForIdle()
  }

  fun open(key: NavKey) = onNavigator { it.navigate(key) }

  /** Every tab back at its root, Home showing. */
  fun resetStacks() = onNavigator { navigator ->
    Tab.entries.forEach { navigator.replace(it, listOf(it.rootKey)) }
    navigator.show(Tab.HOME)
  }

  fun tab(tab: Tab) {
    compose.onNodeWithTag(ShellTags.tab(tab)).performClick()
    compose.waitForIdle()
  }

  fun tap(tag: String) {
    waitForTag(tag)
    compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    compose.waitForIdle()
  }

  /** The system Back, as a key press (predictive back is on, so it reaches the shell's handlers). */
  fun back() {
    device.pressBack()
    compose.waitForIdle()
  }

  fun assertShown(key: NavKey) {
    val tag = ShellTags.destination(key)
    waitForTag(tag)
    compose.onNodeWithTag(tag).assertIsDisplayed()
  }

  fun assertNotShown(key: NavKey) {
    compose.waitUntil(TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(ShellTags.destination(key))).fetchSemanticsNodes().isEmpty()
    }
  }

  fun waitForTag(tag: String) {
    compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
  }

  /** Whether the activity is in the foreground. */
  fun isResumed(): Boolean = scenario.state.isAtLeast(Lifecycle.State.RESUMED)

  companion object {
    const val TIMEOUT_MS = 10_000L
  }
}
