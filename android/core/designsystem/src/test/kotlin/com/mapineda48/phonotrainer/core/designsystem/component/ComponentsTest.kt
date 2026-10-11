package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.testing.a11y.A11yAudit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The foundation controls of design 9.1 (A-03, A-04, A-06, A-13): every interactive element has a label and a target
 * of at least 48 × 48 dp at font scale 1.0 and 2.0, controls expose their role and state, and keyboard focus shows
 * the focus ring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h4000dp")
class ComponentsTest {
  @get:Rule
  val compose = createComposeRule()

  private lateinit var density: Density
  private var fontScale by mutableFloatStateOf(1f)

  private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
    compose.setContent {
      val base = LocalDensity.current
      density = Density(base.density, fontScale)
      CompositionLocalProvider(LocalDensity provides density) {
        PhonoTheme(darkTheme = dark) {
          Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) { content() }
        }
      }
    }
  }

  /** The audit at font scale 1.0, then again at 2.0 (screen tests run at 2.0, architecture 18). */
  private fun auditAtBothScales() {
    A11yAudit.assertClean(compose, density)
    fontScale = 2f
    compose.waitForIdle()
    A11yAudit.assertClean(compose, density)
  }

  @Test
  fun everyControlIsLabelledAndLargeEnoughAtBothFontScales() {
    show {
      PhonoBaseButton("Start", {}, emphasis = ButtonEmphasis.PRIMARY, buttonSize = ButtonSize.LARGE)
      PhonoBaseButton("All clips", {}, trailingIcon = PhonoIcons.ArrowRight)
      PhonoBaseButton("Next change", {}, emphasis = ButtonEmphasis.TONAL)
      PhonoBaseButton("Not now", {}, emphasis = ButtonEmphasis.QUIET)
      PhonoBaseButton("Remove", {}, emphasis = ButtonEmphasis.DANGER)
      LinkButton("Learn more", {}, trailingArrow = true)
      MenuButton("Mix", {}, icon = PhonoIcons.ModeMix)
      PhonoIconButton(PhonoIcons.Back, "Back", {})
      PhonoIconToggle(PhonoIcons.Save, "Save", checked = false, onCheckedChange = {})
      SegmentedControl(listOf(SegmentedOption("Marks"), SegmentedOption("Plain")), 0, {})
      PhonoSwitch("Listen first", checked = true, onCheckedChange = {})
      RadioCard("Standard", selected = true, onClick = {}, body = "The web's colours.")
      PhonoTextField("", {}, label = "Link", placeholder = "Paste a link")
      PhonoChip("Mix", kind = ChipKind.SOLID, onClick = {})
      PhonoChip("Only: flapping", onRemove = {}, removeLabel = "Practice everything instead")
      PaperCard(onClick = {}) { PhonoText("A card") }
      PhonoTopBar("Storage", onBack = {}, backLabel = "Back")
      PhonoBottomBar(listOf(BottomBarItem("Home", PhonoIcons.TabHome), BottomBarItem("Watch", PhonoIcons.TabWatch)), 0, {})
      DialogCard("Forget this computer?", "This phone stops syncing.", "Forget", {}, "Cancel", {}, destructive = true)
      PhonoSnackbar("This request was removed.", actionLabel = "Undo")
      CoachMarkSlip("Tap any word.", "It opens its lesson.", "Got it", {})
      TypeSort("ɾ", "t, voiced alveolar tap, changed", state = SortState.CHANGED, onClick = {})
    }
    auditAtBothScales()
  }

  @Test
  fun controlsExposeTheirRoleAndState() {
    var switched by mutableStateOf(false)
    var segment by mutableStateOf(0)
    show {
      PhonoSwitch("Listen first", checked = switched, onCheckedChange = { switched = it }, modifier = Modifier.testTag("switch"))
      SegmentedControl(
        listOf(SegmentedOption("Marks"), SegmentedOption("Plain")),
        segment,
        { segment = it },
      )
      PhonoIconToggle(PhonoIcons.Saved, "Saved", checked = true, onCheckedChange = {}, modifier = Modifier.testTag("toggle"))
      PhonoBaseButton("Start", {}, modifier = Modifier.testTag("button"))
    }
    compose.onNodeWithTag("switch").assertIsOff().performClick().assertIsOn()
    compose.onNodeWithTag("switch").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    compose.onNodeWithText("Plain").assertIsNotSelected().performClick()
    compose.onNodeWithText("Plain").assertIsSelected()
    compose.onNodeWithText("Marks").assertIsNotSelected()
    compose.onNodeWithTag("toggle").assertIsOn()
    compose.onNodeWithTag("button").assertHasClickAction()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    assertThat(switched).isTrue()
    assertThat(segment).isEqualTo(1)
  }

  @Test
  fun disabledControlsDoNotAct() {
    var clicks = 0
    show { PhonoBaseButton("Send now", { clicks += 1 }, enabled = false, modifier = Modifier.testTag("b")) }
    compose.onNodeWithTag("b").performClick()
    assertThat(clicks).isEqualTo(0)
  }

  @OptIn(ExperimentalComposeUiApi::class)
  @Test
  fun keyboardFocusShowsTheFocusRing() {
    lateinit var input: InputModeManager
    val requesters = List(4) { FocusRequester() }
    show {
      input = LocalInputModeManager.current
      PhonoBaseButton("Start", {}, modifier = Modifier.focusRequester(requesters[0]).testTag("b"))
      PhonoIconButton(PhonoIcons.Back, "Back", {}, modifier = Modifier.focusRequester(requesters[1]).testTag("i"))
      PhonoSwitch("Listen first", false, {}, modifier = Modifier.focusRequester(requesters[2]).testTag("s"))
      PhonoChip("Mix", onClick = {}, modifier = Modifier.focusRequester(requesters[3]).testTag("c"))
    }
    val ring = { shown: Boolean -> SemanticsMatcher.expectValue(FocusRingShown, shown) }
    for ((index, tag) in listOf("b", "i", "s", "c").withIndex()) {
      compose.onNodeWithTag(tag).assert(ring(false))
      compose.runOnIdle {
        input.requestInputMode(InputMode.Keyboard)
        requesters[index].requestFocus()
      }
      compose.onNodeWithTag(tag).assert(ring(true))
    }
  }

  @Test
  fun textFieldErrorsAreSaidInWordsAndAnnounced() {
    show { PhonoTextField("not a link", {}, label = "Link", error = "That doesn't look like a link.") }
    compose.onNodeWithText("That doesn't look like a link.").assertExists()
    compose.onNodeWithContentDescription("Link").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
  }

  @Test
  fun headingsDecorationsAndProgressHaveTheRightSemantics() {
    show {
      Kicker("Listen for", count = "3 of 8")
      PhonoTopBar("Connection details")
      Illustration(PhonoIllustrations.FeedEmpty, Modifier.testTag("art"))
      PhonoProgressBar(0.4f, label = "Bringing 2 clips", value = "40 %", modifier = Modifier.testTag("bar"))
      RouteLine(listOf(RouteNode("Phone", PhonoIcons.AvailOnPhone), RouteNode("Ready", PhonoIcons.StatusReady)), 0,
        "Waiting on this phone.")
      Stepper(listOf(StepperStep("Download", StepState.DONE), StepperStep("Audio", StepState.CURRENT)), "Step 2 of 6, Audio.")
    }
    compose.onAllNodesWithText("LISTEN FOR", useUnmergedTree = true).fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }
    compose.onNodeWithText("Connection details").assert(isHeading())
    compose.onNodeWithTag("art").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    compose.onNodeWithTag("bar").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    compose.onNodeWithContentDescription("Waiting on this phone.").assertExists()
    compose.onNodeWithContentDescription("Step 2 of 6, Audio.").assertExists()
  }

  @Test
  fun theDarkThemeRendersEveryComponentToo() {
    show(dark = true) {
      PhonoBaseButton("Start", {}, emphasis = ButtonEmphasis.PRIMARY)
      PhonoChip("Mix", kind = ChipKind.SOLID, onClick = {})
      PaperCard(variant = CardVariant.STACK) { PhonoText("Today's set") }
      Notice("Clips wait on this phone.", kind = NoticeKind.CAUTION)
      PhonoBadge("Low confidence", dashed = true)
    }
    A11yAudit.assertClean(compose, density)
    compose.onNodeWithText("Today's set").assertExists()
  }

  @Test
  fun theDestructiveDialogPutsTheFocusOnCancel() {
    show { DialogCard("Remove all videos?", "Videos are removed.", "Remove", {}, "Cancel", {}, destructive = true) }
    compose.waitForIdle()
    compose.onNodeWithText("Cancel").assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
  }

  @Test
  fun rememberedStateIsKeptWhenTheThemeChanges() {
    var dark by mutableStateOf(false)
    compose.setContent {
      PhonoTheme(darkTheme = dark) {
        var count by remember { mutableStateOf(0) }
        PhonoBaseButton("Pressed $count", { count += 1 }, modifier = Modifier.testTag("b"))
      }
    }
    compose.onNodeWithTag("b").performClick()
    dark = true
    compose.waitForIdle()
    compose.onNodeWithText("Pressed 1").assertExists()
  }
}
