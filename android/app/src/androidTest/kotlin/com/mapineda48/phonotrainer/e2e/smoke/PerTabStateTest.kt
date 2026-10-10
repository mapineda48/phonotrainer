package com.mapineda48.phonotrainer.e2e.smoke

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
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
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
 * scrolled and searched, keeps its ViewModel instance and its state across tab switches and a recreate, and its state
 * (with a new ViewModel) across a restore from the saved state alone, as after process death.
 *
 * The Library is opened inside the Watch tab's stack, not from Home. The shell always displays the Home stack, so an
 * entry of the Home stack never leaves the displayed list and would keep its state even with the defect H1 is about
 * (one decorated list made of "Home + current tab", which drops the inactive tab's state and ViewModels on every
 * switch). An entry of a tab stack leaves the displayed list whenever another tab shows, so these tests fail when the
 * stacks stop being decorated one by one (verified by running them against exactly that mutation of `PhonoShell`).
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
    robot.tab(OWNER)
    robot.assertShown(OWNER.rootKey)
    robot.open(LibraryKey)
    robot.assertShown(LibraryKey)
    assertTheLibraryIsOnTopOfItsTabStackOnly()
    compose.onNodeWithTag(LibraryTags.SEARCH).performTextInput(QUERY)
    compose.onNodeWithTag(LibraryTags.LIST).performScrollToIndex(SCROLLED_TO_INDEX)
    assertLibraryState()
  }

  @After
  fun tearDown() {
    if (robot.isLaunched()) robot.scenario.close()
  }

  @Test
  fun aTabSwitchKeepsTheViewModelAndItsState() {
    val before = instanceId()
    // Learn is the work plan's switch; Home shows the Home stack alone; Practice is the other tab stack.
    listOf(Tab.LEARN, Tab.HOME, Tab.PRACTICE).forEach { other ->
      leaveTheLibraryFor(other)
      robot.tab(OWNER)
      robot.assertShown(LibraryKey)

      assertLibraryState()
      assertThat(instanceId()).isEqualTo(before)
    }
    assertTheLibraryIsOnTopOfItsTabStackOnly()
  }

  @Test
  fun aRecreateKeepsTheViewModelAndItsState() {
    val before = instanceId()
    // Recreated while the Library's tab is in the background: its stack is decorated but not displayed.
    leaveTheLibraryFor(Tab.LEARN)
    robot.scenario.recreate()
    robot.assertShown(LearnKey)
    robot.assertNotShown(LibraryKey)
    robot.tab(OWNER)
    robot.assertShown(LibraryKey)

    assertLibraryState()
    assertThat(instanceId()).isEqualTo(before)

    // Recreated while the Library shows.
    robot.scenario.recreate()
    robot.assertShown(LibraryKey)

    assertLibraryState()
    assertThat(instanceId()).isEqualTo(before)
    assertTheLibraryIsOnTopOfItsTabStackOnly()
  }

  @Test
  fun aRestoreFromSavedStateAloneKeepsTheState() {
    val before = instanceId()
    leaveTheLibraryFor(Tab.LEARN)
    // Process death, as far as the app's state is concerned: every ViewModel is gone and the activity comes back from
    // its saved state alone (back stacks, saveable state, SavedStateHandles; their Parcel round trip is
    // PhonoNavSerializationTest's). A real kill would take this instrumentation, which runs in the app's process,
    // with it; the black-box version belongs to the R8 smoke (:qasmoke, WP-19).
    robot.scenario.onActivity { it.viewModelStore.clear() }
    robot.scenario.recreate()
    robot.assertShown(LearnKey)
    robot.assertNotShown(LibraryKey)
    robot.tab(OWNER)
    robot.assertShown(LibraryKey)

    assertLibraryState()
    assertThat(instanceId()).isNotEqualTo(before)
    assertTheLibraryIsOnTopOfItsTabStackOnly()
  }

  /** Shows [other]; the Library's entry leaves the displayed list (it is in [OWNER]'s stack, not Home's). */
  private fun leaveTheLibraryFor(other: Tab) {
    robot.tab(other)
    robot.assertShown(other.rootKey)
    robot.assertNotShown(LibraryKey)
  }

  /** The test's premise: the Library sits on [OWNER]'s stack, and the Home stack (always displayed) is its root alone. */
  private fun assertTheLibraryIsOnTopOfItsTabStackOnly() {
    var home = emptyList<NavKey>()
    var owner = emptyList<NavKey>()
    robot.onNavigator {
      home = it.stack(Tab.HOME).toList()
      owner = it.stack(OWNER).toList()
    }
    assertThat(home).containsExactly(HomeKey)
    assertThat(owner).containsExactly(OWNER.rootKey, LibraryKey).inOrder()
  }

  private fun assertLibraryState() {
    compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SEARCH) and hasText(QUERY), PhonoRobot.TIMEOUT_MS)
    compose.onNodeWithTag(LibraryTags.row(SCROLLED_TO_INDEX + 1)).assertIsDisplayed()
  }

  private fun instanceId(): String =
    compose.onNodeWithTag(LibraryTags.INSTANCE).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)!!.joinToString { it.text }

  private companion object {
    /** The tab whose stack holds the Library: a tab stack, which the shell displays only while that tab is current. */
    val OWNER = Tab.WATCH
    const val QUERY = "walk"
    const val SCROLLED_TO_INDEX = 40
  }
}
