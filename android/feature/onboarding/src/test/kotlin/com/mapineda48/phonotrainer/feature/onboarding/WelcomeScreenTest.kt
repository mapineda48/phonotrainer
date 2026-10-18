package com.mapineda48.phonotrainer.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Welcome (spec F-01 behaviour 1, F-01-AC1; design 10.2 P-13): each page's kicker, title and sentence, "Skip" on
 * every page, "Next" then "Get started" ("Done" when replayed), the sample slip on page 1, system Back through the
 * pages, and the accessibility audit at font scale 2.0, light and dark, on every page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class WelcomeScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()
  private val actions = WelcomeActions(
    onNext = { calls += "next" },
    onPrevious = { calls += "previous" },
    onSkip = { calls += "skip" },
    onFinish = { calls += "finish" },
    onLeave = { calls += "leave" },
  )

  private fun checkPage(page: WelcomePage, title: String, body: String, button: String, restart: Boolean = false) {
    screen.checkEach(ScreenVariant.REQUIRED, { WelcomeScreen(page, restart = restart, busy = false, actions = actions) }) {
      compose.onNodeWithTag(OnboardingTags.PAGE_TITLE).assertTextContains("${page.number} OF 3", substring = true)
      compose.onNodeWithTag(OnboardingTags.PAGE_TITLE).assertTextContains(title, substring = true)
      compose.onNodeWithTag(OnboardingTags.PAGE_BODY).assertTextContains(body)
      // F-01-AC1: "Skip" is reachable on every page.
      compose.onNodeWithTag(OnboardingTags.SKIP).assertIsDisplayed()
      val tag = if (page.isLast) OnboardingTags.GET_STARTED else OnboardingTags.NEXT
      assertThat(compose.textsOf(tag)).contains(button)
    }
  }

  @Test
  fun pageOneSaysWhatThePhoneDoesWithASampleSlip() {
    checkPage(
      WelcomePage.LISTEN,
      "Hear English as it's really spoken",
      "PhonoTrainer shows how native speakers change words when they talk, and which of those changes you can copy.",
      "Next",
    )
    // The slip reads as one sentence; the page indicator is decorative (the kicker says "1 of 3").
    assertThat(compose.spokenOf(OnboardingTags.SAMPLE)).contains("flapping turns the t into a quick tap")
    assertThat(compose.spokenOf(OnboardingTags.INDICATOR)).isEmpty()
  }

  @Test
  fun pageTwoSaysWhereClipsComeFrom() {
    checkPage(
      WelcomePage.CLIPS,
      "Your clips become shorts",
      "Share a video from YouTube or X, or pick one on this phone. Your computer analyzes it; your phone turns it into " +
        "shorts you can swipe, slow down and repeat.",
      "Next",
    )
    assertThat(compose.onAllNodes(hasTestTag(OnboardingTags.SAMPLE)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun pageThreeSaysAFewMinutesADayAndGetsStarted() {
    checkPage(
      WelcomePage.DAILY,
      "A few minutes a day",
      "Each day you get a short set: listen, notice one change, say it back, then answer a few questions. A few " +
        "minutes a day is enough.",
      "Get started",
    )
  }

  @Test
  fun theReplayedTourEndsWithDone() {
    checkPage(
      WelcomePage.DAILY,
      "A few minutes a day",
      "Each day you get a short set: listen, notice one change, say it back, then answer a few questions. A few " +
        "minutes a day is enough.",
      "Done",
      restart = true,
    )
  }

  @Test
  fun nextSkipAndGetStartedCallTheirActions() {
    var page by mutableStateOf(WelcomePage.LISTEN)
    screen.setContent { WelcomeScreen(page, restart = false, busy = false, actions = actions) }
    compose.onNodeWithTag(OnboardingTags.NEXT).performClick()
    compose.onNodeWithTag(OnboardingTags.SKIP).performClick()
    page = WelcomePage.DAILY
    compose.waitForIdle()
    compose.onNodeWithTag(OnboardingTags.GET_STARTED).performClick()
    assertThat(calls).containsExactly("next", "skip", "finish").inOrder()
  }

  @Test
  fun backOnALaterPageGoesToThePageBeforeAndOnTheFirstLeaves() {
    var page by mutableStateOf(WelcomePage.CLIPS)
    var dispatcher: OnBackPressedDispatcher? = null
    screen.setContent {
      dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      WelcomeScreen(page, restart = false, busy = false, actions = actions)
    }
    compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
    compose.waitForIdle()
    page = WelcomePage.LISTEN
    compose.waitForIdle()
    compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
    compose.waitForIdle()
    assertThat(calls).containsExactly("previous", "leave").inOrder()
  }

  @Test
  fun whileFinishingTheButtonsWait() {
    screen.setContent { WelcomeScreen(WelcomePage.DAILY, restart = false, busy = true, actions = actions) }
    compose.onNodeWithTag(OnboardingTags.SKIP).assertIsNotEnabled()
    compose.onNodeWithTag(OnboardingTags.GET_STARTED).assertIsNotEnabled()
  }
}
