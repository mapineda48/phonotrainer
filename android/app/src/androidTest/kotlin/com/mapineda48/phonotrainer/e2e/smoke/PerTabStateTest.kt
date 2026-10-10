package com.mapineda48.phonotrainer.e2e.smoke

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.home.HomeTags
import com.mapineda48.phonotrainer.feature.library.LibraryTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The per-tab state proof of architecture 16.2 (Android review H1, work-plan WP-02 Proof): the Library's list,
 * scrolled and searched, keeps its ViewModel instance and its state across a tab switch and a recreate, and its state
 * (with a new ViewModel) across a process-death-like restore from the saved state alone.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class PerTabStateTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  private val robot = PhonoRobot(compose)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    robot.launch()
    robot.tap(HomeTags.LINK_LIBRARY)
    robot.assertShown(LibraryKey)
    compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput(QUERY)
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToIndex(SCROLLED_TO_INDEX)
    assertLibraryState()
  }

  @After
  fun tearDown() {
    shell("settings put global always_finish_activities 0")
    if (robot.isLaunched()) robot.scenario.close()
  }

  @Test
  fun aTabSwitchKeepsTheViewModelAndItsState() {
    val before = instanceId()
    robot.tab(Tab.LEARN)
    robot.assertShown(LearnKey)
    robot.tab(Tab.HOME)
    robot.assertShown(LibraryKey)

    assertLibraryState()
    assertThat(instanceId()).isEqualTo(before)
  }

  @Test
  fun aRecreateKeepsTheViewModelAndItsState() {
    val before = instanceId()
    robot.tab(Tab.LEARN)
    robot.tab(Tab.HOME)
    robot.scenario.recreate()
    robot.assertShown(LibraryKey)

    assertLibraryState()
    assertThat(instanceId()).isEqualTo(before)
  }

  @Test
  fun aRestoreFromSavedStateAloneKeepsTheState() {
    val before = instanceId()
    // "Don't keep activities": the activity is destroyed when it leaves the screen, its ViewModels are cleared, and
    // coming back restores it from its saved state only, as after process death. Reset in tearDown.
    shell("settings put global always_finish_activities 1")
    robot.device.pressHome()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { robot.scenario.state == Lifecycle.State.DESTROYED }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.startActivity(
      context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    robot.waitForTag(LibraryTags.SEARCH)

    assertLibraryState()
    assertThat(instanceId()).isNotEqualTo(before)
  }

  private fun assertLibraryState() {
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SEARCH) and hasText(QUERY), PhonoRobot.TIMEOUT_MS)
    compose.onNodeWithTag(LibraryTags.row(SCROLLED_TO_INDEX + 1)).assertIsDisplayed()
  }

  private fun instanceId(): String =
    compose.onNodeWithTag(LibraryTags.INSTANCE).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)!!.joinToString { it.text }

  private fun shell(command: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
  }

  private companion object {
    const val QUERY = "walk"
    const val SCROLLED_TO_INDEX = 40
  }
}
