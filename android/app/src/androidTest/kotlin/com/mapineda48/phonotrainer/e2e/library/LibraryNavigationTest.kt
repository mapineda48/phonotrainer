package com.mapineda48.phonotrainer.e2e.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.library.LibraryTags
import com.mapineda48.phonotrainer.feature.library.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Library area on the global test set with the repositories of this baseline (work-plan 2.4 and section 6 "GMD
 * area tests"): the Library and the Clip page open, their controls reach their destinations, Back follows architecture
 * 16.2, and the states these repositories produce render. A phone that never paired has an empty catalogue, so the
 * Library shows its first-run state, and a Clip page link to a clip this phone does not know shows "That clip is no
 * longer on this phone." The seeded happy paths are `LibraryStateTest`'s and, once the sync engine is merged, WP-19's
 * flows through [LibraryRobot].
 */
@HiltAndroidTest
class LibraryNavigationTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  private val phono = PhonoRobot(compose)
  private val library = LibraryRobot(compose, phono)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun anUnpairedPhoneSeesTheFirstRunStateAndItsActionsLeadOn() {
    library.open()
    phono.waitForTag(LibraryTags.EMPTY)
    compose.onNode(hasText(library.words(R.string.library_first_title))).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.LEDE) and hasText(library.words(R.string.library_lede))).assertIsDisplayed()

    library.tapAction(LibraryTags.EMPTY, R.string.library_connect)
    phono.assertShown(ConnectKey(ConnectMode.FROM_HOME))
    phono.back()
    phono.assertShown(LibraryKey)

    phono.tap(LibraryTags.ADD)
    phono.assertShown(AddClipKey())
    phono.back()
    phono.assertShown(LibraryKey)

    // Back from the Library: Home at its root.
    phono.back()
    phono.assertShown(HomeKey)
    phono.assertNotShown(LibraryKey)
    assertThat(library.stacks(Tab.HOME).first).containsExactly(HomeKey)
  }

  @Test
  fun aClipThisPhoneDoesNotKnowSaysSoAndLeadsToTheLibrary() {
    val unknown = ClipKey(JobId("e2e-library-unknown"))
    phono.open(unknown)
    phono.assertShown(unknown)
    library.assertClipGone()

    library.tapAction(LibraryTags.CLIP_GONE, R.string.clip_page_see_all)
    phono.assertShown(LibraryKey)
    assertThat(library.stacks(Tab.HOME).first).containsExactly(HomeKey, unknown, LibraryKey).inOrder()
    phono.back()
    phono.assertShown(unknown)
    phono.back()
    phono.assertShown(HomeKey)
  }
}
