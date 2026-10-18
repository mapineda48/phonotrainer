package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shadowing panel (spec F-12, design 9.11 step 5; WP-18): the rail's plain use, the lesson's choices, the reason
 * Start is disabled, the "Recognize only" note, and the running card's live line and 8-phase bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ShadowingPanelTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  /**
   * [PhonoScreenRule.checkEach] at font scale 2.0 in light and dark, with the shared audit's rules except for the
   * options of WP-03's `SegmentedControl`: 42 dp tall in a 48 dp row (design 9.1), a defect of the shared control that
   * WP-18 reported to the integrator (the shared rule measures each option).
   */
  private fun checkEachWithSegments(content: @Composable () -> Unit, check: () -> Unit) {
    screen.setContent(ScreenVariant.REQUIRED.first(), content)
    ScreenVariant.REQUIRED.forEach { variant ->
      screen.show(variant)
      check()
      val problems = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filterNot { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
      }.mapNotNull { node ->
        val bounds = node.touchBoundsInRoot
        val min = 48f * compose.density.density - 1f
        val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
          ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        when {
          label.isNullOrBlank() -> "node ${node.id} has no label at $variant"
          bounds.width < min || bounds.height < min -> "\"$label\" is under 48 × 48 dp at $variant"
          else -> null
        }
      }
      assertThat(problems).isEmpty()
    }
  }

  @Test
  fun theRailsPanelIsStartAloneAndStarts() {
    var starts = 0
    screen.setContent(ScreenVariant(fontScale = 2f)) {
      ShadowingPanel(ShadowingState.Idle, onStart = { starts++ }, onStop = {}, options = ShadowingOptions())
    }
    compose.onNodeWithTag(ShadowingTags.INTRO).assertDoesNotExist()
    compose.onNodeWithTag(ShadowingTags.TARGET).assertDoesNotExist()
    compose.onNodeWithTag(ShadowingTags.SPEED).assertDoesNotExist()
    compose.onNodeWithTag(ShadowingTags.START).assertTextContains("Start shadowing").performClick()
    assertThat(starts).isEqualTo(1)
    screen.audit()
  }

  @Test
  fun theLessonsPanelOffersWhatToShadowAndTheSlowSpeed() {
    var options by mutableStateOf(ShadowingOptions(target = ShadowingTarget.PHRASE))
    checkEachWithSegments(content = {
      ShadowingPanel(ShadowingState.Idle, onStart = {}, onStop = {}, options = options, onOptionsChange = { options = it })
    }) {
      compose.onNodeWithTag(ShadowingTags.INTRO, useUnmergedTree = true).assertTextContains(
        "Shadowing: listen, then say it in the silence that follows — copying the rhythm and the reductions, not just " +
          "the sounds. Start slow; the last round is at full speed.",
      )
    }
    compose.onNode(hasText("The whole phrase") and hasAnyAncestor(hasTestTag(ShadowingTags.TARGET))).assertIsSelected()
    compose.onNode(hasText("0.75×") and hasAnyAncestor(hasTestTag(ShadowingTags.SPEED))).assertIsSelected()
    compose.onNode(hasText("The word and the next") and hasAnyAncestor(hasTestTag(ShadowingTags.TARGET))).performClick()
    compose.onNode(hasText("0.5×") and hasAnyAncestor(hasTestTag(ShadowingTags.SPEED))).performClick()
    assertThat(options).isEqualTo(ShadowingOptions(target = ShadowingTarget.WORD, slowSpeed = 0.5f))
  }

  @Test
  fun withoutANextWordTheWordOptionIsTheWordAlone() {
    screen.setContent {
      ShadowingPanel(
        ShadowingState.Idle, onStart = {}, onStop = {},
        options = ShadowingOptions(target = ShadowingTarget.WORD, wordAndNext = false), onOptionsChange = {},
      )
    }
    compose.onNode(hasText("The word") and hasAnyAncestor(hasTestTag(ShadowingTags.TARGET))).assertIsSelected()
  }

  @Test
  fun withoutAudioStartIsDisabledAndSaysWhy() {
    var starts = 0
    screen.setContent {
      ShadowingPanel(
        ShadowingState.Idle, onStart = { starts++ }, onStop = {},
        options = ShadowingOptions(unavailableReason = "This clip came without its audio."),
      )
    }
    compose.onNodeWithTag(ShadowingTags.START).assertIsNotEnabled().performClick()
    compose.onNodeWithTag(ShadowingTags.REASON, useUnmergedTree = true).assertTextContains("This clip came without its audio.")
    assertThat(starts).isEqualTo(0)
  }

  @Test
  fun aRecognizeOnlyChangeSaysShadowingTrainsTheEar() {
    screen.setContent {
      ShadowingPanel(ShadowingState.Idle, onStart = {}, onStop = {}, options = ShadowingOptions(recognizeOnly = true))
    }
    compose.onNode(
      hasText("You can shadow it to train your ear; you don't need to copy it.") and
        hasAnyAncestor(hasTestTag(ShadowingTags.RECOGNIZE_ONLY)),
      useUnmergedTree = true,
    ).assertExists()
  }

  @Test
  fun runningShowsEachPhaseInTheRunnersWordsOnTheEightPhaseBar() {
    var state by mutableStateOf<ShadowingState>(ShadowingState.Running(1, 4, ShadowingPhase.LISTEN, 0.75f))
    var stops = 0
    checkEachWithSegments(content = {
      ShadowingPanel(
        state, onStart = {}, onStop = { stops++ },
        options = ShadowingOptions(target = ShadowingTarget.WORD), onOptionsChange = {},
      )
    }) {
      compose.onNodeWithTag(ShadowingTags.LINE, useUnmergedTree = true).assertTextContains("Round 1 of 4: listen at 0.75×")
      compose.onNodeWithTag(ShadowingTags.PHASES).assert(hasContentDescription("Phase 1 of 8"))
      // While it runs the choices wait.
      compose.onNode(hasText("0.5×") and hasAnyAncestor(hasTestTag(ShadowingTags.SPEED))).assertIsNotEnabled()
      compose.onNodeWithTag(ShadowingTags.START).assertDoesNotExist()
    }
    state = ShadowingState.Running(2, 4, ShadowingPhase.YOUR_TURN, 0.75f)
    compose.waitForIdle()
    compose.onNodeWithTag(ShadowingTags.LINE, useUnmergedTree = true).assertTextContains("Round 2 of 4: your turn — say it now")
    compose.onNodeWithTag(ShadowingTags.PHASES).assert(hasContentDescription("Phase 4 of 8"))
    state = ShadowingState.Running(4, 4, ShadowingPhase.LISTEN, 1f)
    compose.waitForIdle()
    compose.onNodeWithTag(ShadowingTags.LINE, useUnmergedTree = true).assertTextContains("Round 4 of 4: listen at full speed")
    compose.onNodeWithTag(ShadowingTags.PHASES).assert(hasContentDescription("Phase 7 of 8"))
    compose.onNodeWithTag(ShadowingTags.STOP).assertIsEnabled().performClick()
    assertThat(stops).isEqualTo(1)
    state = ShadowingState.Idle
    compose.waitForIdle()
    compose.onNodeWithTag(ShadowingTags.RUNNING).assertDoesNotExist()
    compose.onNodeWithTag(ShadowingTags.START).assertIsEnabled()
  }

  @Test
  fun aPhaseIsAnIndexOfThePlan() {
    assertThat(phaseIndex(ShadowingState.Running(1, 4, ShadowingPhase.LISTEN, 0.75f))).isEqualTo(0)
    assertThat(phaseIndex(ShadowingState.Running(1, 4, ShadowingPhase.YOUR_TURN, 0.75f))).isEqualTo(1)
    assertThat(phaseIndex(ShadowingState.Running(4, 4, ShadowingPhase.YOUR_TURN, 1f))).isEqualTo(7)
    assertThat(speedWords(0.75f)).isEqualTo("0.75")
    assertThat(speedWords(0.5f)).isEqualTo("0.5")
    assertThat(speedWords(1f)).isEqualTo("1")
  }
}
