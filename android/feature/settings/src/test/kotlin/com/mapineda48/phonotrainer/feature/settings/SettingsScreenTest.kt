package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings page (spec F-22, design 9.25 and 10.2 P-12, renders `22`): the three groups of choices with their
 * current values, the five sub-page rows with their status lines, each control calling its action, the "At the end of
 * a short" menu and "Clips left out of the Mix". Checked across design 11's matrix with the accessibility audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class SettingsScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = SettingsActions(
    onBack = { calls += "back" },
    onAppearance = { calls += "appearance:$it" },
    onColors = { calls += "colors:$it" },
    onPatterns = { calls += "patterns:$it" },
    onMotion = { calls += "motion:$it" },
    onSpeed = { calls += "speed:$it" },
    onPlayEach = { calls += "playEach:$it" },
    onEndOfShort = { calls += "endOfShort:$it" },
    onCaptions = { calls += "captions:$it" },
    onListenFirst = { calls += "listenFirst:$it" },
    onTrack = { calls += "track:$it" },
    onInMix = { id, inMix -> calls += "mix:${id.value}:$inMix" },
    onQuestions = { calls += "questions:$it" },
    onGuidance = { calls += "guidance:$it" },
    onComputer = { calls += "computer" },
    onStorage = { calls += "storage" },
    onNotifications = { calls += "notifications" },
    onHelp = { calls += "help" },
    onAbout = { calls += "about" },
  )

  private fun option(group: String, text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(group)))

  @Test
  fun everyGroupAndEverySubPageRowReadsAcrossTheMatrix() {
    screen.checkEach(ScreenVariant.MATRIX, { SettingsScreen(SettingsSamples.settings, actions) }) {
      compose.onNodeWithText("All settings apply at once and are remembered on this phone.").assertExists()
      compose.onNodeWithText("DISPLAY").assertExists()
      compose.onNodeWithText("PLAYBACK AND CAPTIONS").assertExists()
      compose.onNodeWithText("LEARNING").assertExists()
      option(SettingsTags.APPEARANCE, "System").assertIsSelected()
      compose.onNodeWithTag(SettingsTags.COLORS_STANDARD).assertIsSelected()
      option(SettingsTags.PATTERNS, "Follow Colors").assertIsSelected()
      option(SettingsTags.MOTION, "Follow system").assertIsSelected()
      option(SettingsTags.SPEED, "1×").assertIsSelected()
      compose.onNodeWithTag(SettingsTags.PLAY_EACH).assertIsOn()
      assertThat(compose.textsOf(SettingsTags.END_OF_SHORT)).contains("Replay it once")
      option(SettingsTags.CAPTIONS, "Marks").assertIsSelected()
      compose.onNodeWithTag(SettingsTags.LISTEN_FIRST).assertIsOff()
      option(SettingsTags.TRACK, "Original mix").assertIsSelected()
      compose.onNodeWithTag(SettingsTags.QUESTIONS).assertIsOn()
      option(SettingsTags.GUIDANCE, "Full").assertIsSelected()
      assertThat(compose.textsOf(SettingsTags.TEXT_SIZE)).isEqualTo("Text size follows your phone's font size, IPA included.")
      assertThat(compose.textsOf(SettingsTags.MIX)).contains("1 clip left out")
      assertThat(compose.textsOf(SettingsTags.LINK_COMPUTER)).contains("Study desk")
      assertThat(compose.textsOf(SettingsTags.LINK_STORAGE)).contains("1.4 GB of 2 GB")
      assertThat(compose.textsOf(SettingsTags.LINK_NOTIFICATIONS)).contains("On")
      assertThat(compose.textsOf(SettingsTags.LINK_HELP)).contains("Help")
      assertThat(compose.textsOf(SettingsTags.LINK_ABOUT)).contains("About and licences")
    }
  }

  @Test
  fun theColorsCardsSayWhatEachPaletteIsAndTheRowsSayWhatIsOff() {
    screen.checkEach(ScreenVariant.REQUIRED, { SettingsScreen(SettingsSamples.settingsCvd, actions) }) {
      compose.onNodeWithTag(SettingsTags.COLORS_CVD).assertIsSelected()
      assertThat(compose.textsOf(SettingsTags.COLORS_CVD)).contains(
        "Blue, orange, vermilion and sky — kept apart even with red-green or blue-yellow color blindness. Turns on pattern emphasis.",
      )
      assertThat(compose.textsOf(SettingsTags.COLORS_STANDARD)).contains("Calm blue, amber, plum and teal.")
      // The preview is decorative: the card's words are all TalkBack reads.
      assertThat(compose.spokenOf(SettingsTags.COLORS_STANDARD)).isEqualTo("Standard Calm blue, amber, plum and teal.")
      assertThat(compose.textsOf(SettingsTags.MIX)).contains("None: every clip is in the Mix.")
      assertThat(compose.textsOf(SettingsTags.LINK_COMPUTER)).contains("Not connected to a computer")
      assertThat(compose.textsOf(SettingsTags.LINK_STORAGE)).contains("used")
      assertThat(compose.textsOf(SettingsTags.LINK_NOTIFICATIONS)).contains("Off for PhonoTrainer")
    }
  }

  @Test
  fun eachChoiceCallsItsActionAndEachRowItsPage() {
    screen.setContent { SettingsScreen(SettingsSamples.settings, actions) }
    option(SettingsTags.APPEARANCE, "Dark").performClick()
    compose.onNodeWithTag(SettingsTags.COLORS_CVD).performClick()
    option(SettingsTags.PATTERNS, "On").performClick()
    option(SettingsTags.MOTION, "Reduce").performClick()
    option(SettingsTags.SPEED, "0.75×").performClick()
    compose.onNodeWithTag(SettingsTags.PLAY_EACH).performClick()
    option(SettingsTags.CAPTIONS, "IPA").performClick()
    compose.onNodeWithTag(SettingsTags.LISTEN_FIRST).performClick()
    option(SettingsTags.TRACK, "Dialogue only").performClick()
    compose.onNodeWithTag(SettingsTags.QUESTIONS).performClick()
    option(SettingsTags.GUIDANCE, "Compact").performClick()
    listOf(SettingsTags.LINK_COMPUTER, SettingsTags.LINK_STORAGE, SettingsTags.LINK_NOTIFICATIONS, SettingsTags.LINK_HELP, SettingsTags.LINK_ABOUT)
      .forEach { compose.onNodeWithTag(it).performClick() }

    assertThat(calls).containsExactly(
      "appearance:${Appearance.DARK}",
      "colors:${ColorChoice.COLOR_VISION_FRIENDLY}",
      "patterns:${PatternChoice.ON}",
      "motion:${MotionChoice.REDUCE}",
      "speed:0.75",
      "playEach:false",
      "captions:${CaptionMode.IPA}",
      "listenFirst:true",
      "track:${Track.DIALOGUE}",
      "questions:false",
      "guidance:${LessonGuidance.COMPACT}",
      "computer",
      "storage",
      "notifications",
      "help",
      "about",
    ).inOrder()
  }

  @Test
  fun theEndOfAShortMenuOffersTheThreeChoices() {
    screen.setContent { SettingsScreen(SettingsSamples.settings, actions) }
    compose.onNodeWithTag(SettingsTags.END_OF_SHORT).performClick()
    compose.onNodeWithTag(SettingsTags.endOfShort(EndOfShort.REPLAY_ONCE)).assertIsSelected()
    assertThat(compose.textsOf(SettingsTags.endOfShort(EndOfShort.STOP))).contains("Stop")
    assertThat(compose.textsOf(SettingsTags.endOfShort(EndOfShort.NEXT))).contains("Go to the next one")
    compose.onNodeWithTag(SettingsTags.endOfShort(EndOfShort.NEXT)).performClick()
    assertThat(calls).containsExactly("endOfShort:${EndOfShort.NEXT}")
  }

  @Test
  fun clipsLeftOutOfTheMixOpenAsASheetWithASwitchPerClip() {
    screen.setContent { SettingsScreen(SettingsSamples.settings, actions) }
    compose.onNodeWithTag(SettingsTags.MIX).performClick()
    compose.onNodeWithTag(SettingsTags.MIX_SHEET).assertExists()
    compose.onNodeWithText("Turn a clip off to leave it out of the Mix and of new sets. It still plays in order from its clip page.").assertExists()
    compose.onNodeWithTag(SettingsTags.mixClip(SettingsSamples.coffee)).assertIsOn()
    compose.onNodeWithTag(SettingsTags.mixClip(SettingsSamples.market)).assertIsOff()
    assertThat(compose.textsOf(SettingsTags.mixClip(SettingsSamples.market))).contains("Left out of the Mix")

    compose.onNodeWithTag(SettingsTags.mixClip(SettingsSamples.coffee)).performClick()
    compose.onNodeWithTag(SettingsTags.mixClip(SettingsSamples.market)).performClick()
    assertThat(calls).containsExactly("mix:${SettingsSamples.coffee.value}:false", "mix:${SettingsSamples.market.value}:true").inOrder()

    compose.onNodeWithTag(SettingsTags.MIX_DONE).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(SettingsTags.MIX_SHEET).assertDoesNotExist()
  }

  /** The sheet's content on its own: the shared sheet's drag handle has no label (WP-03's, known; see WatchAudit). */
  @Test
  fun theMixSheetsContentReadsAtLargeTextInLightAndDark() {
    screen.checkEach(ScreenVariant.REQUIRED, { MixContent(SettingsSamples.settings.clips, onInMix = { _, _ -> }, onDone = {}) }) {
      compose.onNodeWithTag(SettingsTags.mixClip(SettingsSamples.park)).assertIsOn()
    }
  }

  @Test
  fun withoutClipsTheMixSheetSaysWhereTheyComeFrom() {
    screen.checkEach(ScreenVariant.REQUIRED, { MixContent(emptyList(), onInMix = { _, _ -> }, onDone = {}) }) {
      assertThat(compose.textsOf(SettingsTags.MIX_EMPTY)).isEqualTo("No clips yet. The clips your computer analyzes appear here.")
    }
  }
}
