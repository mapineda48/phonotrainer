package com.mapineda48.phonotrainer.e2e.smoke

import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.shell.ShellTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The shell's frame and its inset contract (architecture 16.5; design 9.24, 10; decision I-12): the shell never pads a
 * destination with the status-bar inset (each destination handles its own top: a full-bleed feed, a top bar drawn
 * behind the status bar), the bottom bar sits on the bottom edge with its sheet behind the navigation bar and its items
 * above it, and a destination ends where the bar begins.
 *
 * The managed device's image (aosp-atd) draws no status bar, so its insets are zero and could not tell a padded
 * destination from one that is not. Each test therefore hands the window content the insets of a phone with a
 * [STATUS_BAR] and a gesture [NAVIGATION_BAR], as the system does, and Compose's insets follow them.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ShellFrameTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  private val robot = PhonoRobot(compose)

  @Before
  fun start() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    robot.launch()
    applySystemBars()
  }

  @After
  fun tearDown() {
    if (robot.isLaunched()) robot.scenario.close()
  }

  @Test
  fun everyDestinationStartsAtTheTopOfTheWindow() {
    val keys: List<NavKey> = listOf(HomeKey, LibraryKey, WatchKey(), LearnKey)
    keys.forEach { key ->
      robot.open(key)
      robot.assertShown(key)
      val top = compose.onNodeWithTag(ShellTags.destination(key)).getUnclippedBoundsInRoot().top
      assertWithMessage("top of $key under a $STATUS_BAR status bar").that(top.value).isWithin(TOLERANCE).of(0f)
    }
  }

  @Test
  fun theBottomBarSitsOnTheBottomEdgeAndADestinationEndsAtIt() {
    val window = compose.onRoot().getUnclippedBoundsInRoot()
    val bar = compose.onNodeWithTag(ShellTags.BOTTOM_BAR).getUnclippedBoundsInRoot()
    val home = compose.onNodeWithTag(ShellTags.destination(HomeKey)).getUnclippedBoundsInRoot()
    val item = compose.onNodeWithTag(ShellTags.tab(Tab.HOME), useUnmergedTree = true).getUnclippedBoundsInRoot()

    assertWithMessage("the bar reaches the bottom edge").that(bar.bottom.value).isWithin(TOLERANCE).of(window.bottom.value)
    assertWithMessage("the destination ends at the bar").that(home.bottom.value).isWithin(TOLERANCE).of(bar.top.value)
    assertWithMessage("the items stay above the navigation bar").that((window.bottom - item.bottom).value)
      .isAtLeast(NAVIGATION_BAR.value - TOLERANCE)
  }

  /** Dispatches a phone's system-bar insets to the window content, as the system does when the bars change. */
  private fun applySystemBars() {
    robot.scenario.onActivity { activity ->
      val density = activity.resources.displayMetrics.density
      fun px(dp: Dp): Int = (dp.value * density).roundToInt()
      val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(STATUS_BAR), 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(NAVIGATION_BAR)))
        .build()
      ViewCompat.dispatchApplyWindowInsets(activity.findViewById<View>(android.R.id.content), insets)
    }
    compose.waitForIdle()
  }

  private companion object {
    const val TOLERANCE = 1f
    val STATUS_BAR = 32.dp
    val NAVIGATION_BAR = 24.dp
  }
}
