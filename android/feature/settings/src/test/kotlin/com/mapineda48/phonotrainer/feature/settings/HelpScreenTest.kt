package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > Help (spec F-22 "Help", F-01 item 4): "Restart the tour", "What do the marks mean?" opening the marks
 * legend (the same `MarksLegend` as the short's More sheet) and "How PhonoTrainer works" in plain words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class HelpScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()
  private val actions = HelpActions(onBack = { calls += "back" }, onRestartTour = { calls += "tour" })

  @Test
  fun helpOffersTheTourTheMarksAndHowPhonoTrainerWorks() {
    screen.checkEach(ScreenVariant.REQUIRED, { HelpScreen(actions) }) {
      compose.onNodeWithText("Restart the tour, see what the marks mean, and learn how PhonoTrainer works.").assertExists()
      assertThat(compose.textsOf(SettingsTags.HELP_TOUR)).contains("Restart the tour")
      assertThat(compose.textsOf(SettingsTags.HELP_TOUR)).contains("Your computer stays connected.")
      assertThat(compose.textsOf(SettingsTags.HELP_MARKS)).contains("What do the marks mean?")
      val how = compose.textsOf(SettingsTags.HELP_HOW)
      listOf(
        "HOW PHONOTRAINER WORKS",
        "Download", "Extract the audio", "Separate the dialogue", "Transcribe the words", "Hear the phones", "Compare and save",
        "Whisper writes down every word and when it is said.",
        "The phone engines", "TIMIT-61 (the default)", "espeak",
        "On this phone",
        "Nothing is analyzed on the phone, and nothing goes to the internet.",
      ).forEach { assertThat(how).contains(it) }
      // The step's number is read as its place, not as a bare digit.
      assertThat(how).contains("Step 1 of 6")
    }
  }

  @Test
  fun restartTheTourCallsItsAction() {
    screen.setContent { HelpScreen(actions) }
    compose.onNodeWithTag(SettingsTags.HELP_TOUR).performClick()
    assertThat(calls).containsExactly("tour")
  }

  @Test
  fun whatDoTheMarksMeanOpensTheLegend() {
    screen.setContent { HelpScreen(actions) }
    compose.onNodeWithTag(SettingsTags.HELP_MARKS).performClick()
    compose.onNodeWithTag(SettingsTags.HELP_LEGEND).assertExists()
    val legend = compose.textsOf(SettingsTags.HELP_LEGEND)
    // The legend's four families and the whole-word changes, with their line styles (WP-03's MarksLegend).
    assertThat(legend).contains("Reduction")
    assertThat(legend).contains("Low confidence")
  }

  /** The sheet's content on its own: the shared sheet's drag handle has no label (WP-03's, known; see WatchAudit). */
  @Test
  fun theLegendReadsAtLargeTextInLightAndDark() {
    screen.checkEach(ScreenVariant.REQUIRED, { LegendContent() }) {
      compose.onNodeWithText("What the marks mean").assertExists()
    }
  }
}
