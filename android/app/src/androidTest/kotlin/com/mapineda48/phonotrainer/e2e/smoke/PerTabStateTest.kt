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
 * (with a new ViewModel) across a restore from the saved state alone, as after process death.
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
    robot.tab(Tab.LEARN)
    robot.tab(Tab.HOME)
    // Process death, as far as the app's state is concerned: every ViewModel is gone and the activity comes back from
    // its saved state alone (back stacks, saveable state, SavedStateHandles; their Parcel round trip is
    // PhonoNavSerializationTest's). A real kill would take this instrumentation, which runs in the app's process,
    // with it; the black-box version belongs to the R8 smoke (:qasmoke, WP-19).
    robot.scenario.onActivity { it.viewModelStore.clear() }
    robot.scenario.recreate()
    robot.assertShown(LibraryKey)

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

  private companion object {
    const val QUERY = "walk"
    const val SCROLLED_TO_INDEX = 40
  }
}
