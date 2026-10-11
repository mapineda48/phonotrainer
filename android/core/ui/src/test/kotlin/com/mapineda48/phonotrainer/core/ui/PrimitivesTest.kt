package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.trace.sampleContour
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.testing.a11y.A11yAudit
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import com.mapineda48.phonotrainer.core.ui.ipa.LocalIpaReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The visual primitives of `:core:ui` (architecture 17 and 18, design 9 and 12.2): labels, 48 dp targets, semantics,
 * font scale 2.0 and the never-colour-alone rule, on every primitive other work packages build on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h4000dp")
class PrimitivesTest {
  @get:Rule
  val compose = createComposeRule()

  private lateinit var density: Density
  private var fontScale by mutableFloatStateOf(1f)

  private fun show(choice: ColorChoice = ColorChoice.STANDARD, content: @Composable () -> Unit) {
    compose.setContent {
      density = Density(LocalDensity.current.density, fontScale)
      CompositionLocalProvider(LocalDensity provides density) {
        PhonoTheme(darkTheme = false, colors = choice) {
          Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) { content() }
        }
      }
    }
  }

  private fun auditAtBothScales() {
    A11yAudit.assertClean(compose, density)
    fontScale = 2f
    compose.waitForIdle()
    A11yAudit.assertClean(compose, density)
  }

  private val entry = GlossaryEntry("schwa", "Schwa", "The weak vowel /ə/.", "about → /əˈbaʊt/", LinkTarget.Learn, null)

  @Test
  fun everyInteractivePrimitiveIsLabelledAndLargeEnoughAtBothFontScales() {
    show {
      PhonoButton("Start", {})
      PhonoButton("Remove", {}, kind = PhonoButtonKind.DANGER)
      PhonoButton("Next change", {}, kind = PhonoButtonKind.TONAL, trailingIcon = PhonoIcons.ArrowRight)
      PhonoCard(variant = CardVariant.ALERT, onClick = {}) { IpaText("Needs your attention") }
      ChangeChip("Flapping", Family.TD, onClick = {})
      ExplainButton(term = "schwa", onClick = {})
      ExplainContent(entry, onDismiss = {}, onLearnMore = {})
      EmptyState(PhonoIllustrations.SavedEmpty, "Nothing saved yet", "Save a short to find it here.",
        actions = listOf(EmptyStateAction("Go to Watch", {})))
      CoachMarkHost(CoachMark("Tap any word.", "It opens its lesson."), onDismiss = {}) { IpaText("Captions") }
      ShortProgress(0.5f, samples = sampleContour(), positionMs = 30_000, durationMs = 60_000, onSeek = {})
    }
    auditAtBothScales()
  }

  @Test
  fun theFamilyIsTellableWithoutColour() {
    show {
      FamilyMark(Family.TD, Modifier.testTag("mark"))
      ChangeChip("Flapping", Family.TD, onClick = {}, modifier = Modifier.testTag("chip"))
      MarksLegend()
    }
    compose.onNodeWithTag("mark").assertContentDescriptionEquals("t/d processes, dashed line")
    compose.onNodeWithTag("chip").assertContentDescriptionEquals("Flapping, t/d processes")
      .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    for (name in listOf("Reduction", "t/d processes", "Assimilation", "Word boundary", "Lexical contraction", "Other change",
      "Low confidence", "Stressed word")) {
      compose.onNodeWithText(name, substring = false).assertExists()
    }
    compose.onNodeWithText("What the marks mean").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
  }

  @Test
  fun aChangeChipOpensItsPage() {
    var opened = 0
    show { ChangeChip("Flapping", Family.TD, onClick = { opened += 1 }) }
    compose.onNodeWithTag(UiTags.CHANGE_CHIP).performClick()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun badgesAndStatusLinesAreWordsWithIcons() {
    show {
      PracticeBadge(Advice.PRODUCE)
      PracticeBadge(Advice.UNDERSTAND, register = "casual")
      StatusLine("1 needs your attention", tone = StatusTone.CRITICAL)
      AvailabilityChip(ClipAvailability.Bringing(45))
      AvailabilityChip(ClipAvailability.DownloadPaused(PauseReason.FREE_SPACE_FLOOR))
      VideoAvailabilityChip(VideoAvailability.CannotPlayOnThisPhone)
    }
    compose.onNodeWithText("Safe to produce").assertExists()
    compose.onNodeWithText("Recognize only · casual").assertExists()
    compose.onNodeWithText("1 needs your attention").assertExists()
    compose.onNodeWithText("Bringing to this phone 45 %").assertExists()
    compose.onNodeWithText("Download paused: your phone is almost full").assertExists()
    compose.onNodeWithText("This video can't play on this phone").assertExists()
  }

  @Test
  fun ipaIsReadBySymbolNamesWithAPlayAction() {
    var played = 0
    compose.setContent {
      CompositionLocalProvider(LocalIpaReading provides IpaReading { if (it == "ɾ") "voiced alveolar tap" else null }) {
        PhonoTheme { IpaText("[ɾ]", modifier = Modifier.testTag("ipa"), onPlay = { played += 1 }) }
      }
    }
    compose.onNodeWithTag("ipa").assertContentDescriptionEquals("[ɾ]: voiced alveolar tap")
    val actions = compose.onNodeWithTag("ipa").fetchSemanticsNode().config[SemanticsActions.CustomActions]
    assertThat(actions.map { it.label }).containsExactly("Play this sound")
    compose.runOnIdle { actions.single().action() }
    assertThat(played).isEqualTo(1)
  }

  @Test
  fun theShortProgressIsASliderWithStepActions() {
    var seek: Float? = null
    show {
      ShortProgress(
        progress = 0.5f,
        samples = sampleContour(),
        ticks = listOf(0.25f, 0.75f),
        pin = 0.4f,
        positionMs = 30_000,
        durationMs = 60_000,
        onSeek = { seek = it },
        modifier = Modifier.testTag("progress"),
      )
    }
    val node = compose.onNodeWithTag("progress")
    node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Position 0:30 of 1:00"))
    node.assertContentDescriptionEquals("Short progress")
    val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
    assertThat(actions.map { it.label }).containsExactly("Back 2 s", "Forward 2 s").inOrder()
    compose.runOnIdle { actions[0].action() }
    assertThat(seek).isWithin(1e-4f).of(0.5f - 2f / 60f)
    node.performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
    assertThat(seek).isWithin(1e-4f).of(0.9f)
    assertThat(snap(0.26f, listOf(0.25f, 0.75f))).isEqualTo(0.25f)
    assertThat(snap(0.5f, listOf(0.25f, 0.75f))).isEqualTo(0.5f)
    assertThat(formatClock(65_400)).isEqualTo("1:05")
  }

  @Test
  fun posterArtIsDecorativeUnlessDescribed() {
    show {
      PosterArt(null, JobId("20261004-133519-a77c"), audioOnly = false, title = "A walk in the park",
        modifier = Modifier.size(176.dp, 110.dp).testTag("video"))
      PosterArt(null, JobId("20261005-101010-b001"), audioOnly = true, contentDescription = "Audio clip",
        modifier = Modifier.size(176.dp, 110.dp).testTag("audio"))
    }
    compose.onNodeWithTag("video").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    compose.onNodeWithTag("audio").assertContentDescriptionEquals("Audio clip")
    assertThat(decodePoster(java.io.File("does-not-exist.jpg"))).isNull()
  }

  @Test
  fun theLoadingGateWaitsThenShowsWords() {
    compose.mainClock.autoAdvance = false
    var loading by mutableStateOf(true)
    show { LoadingGate(loading, label = "Loading your clips…") { IpaText("Ready") } }
    compose.mainClock.advanceTimeBy(100)
    compose.onNodeWithText("Loading your clips…").assertDoesNotExist()
    compose.mainClock.advanceTimeBy(1_200)
    compose.onNodeWithText("Loading your clips…").assertExists()
    loading = false
    compose.mainClock.autoAdvance = true
    compose.waitForIdle()
    compose.onNodeWithText("Ready").assertExists()
  }

  @Test
  fun theCoachMarkWaitsForGotIt() {
    var dismissed = 0
    show { CoachMarkHost(CoachMark("Tap any word.", "It opens its lesson."), onDismiss = { dismissed += 1 }) { IpaText("Feed") } }
    compose.onNodeWithTag(UiTags.COACH_GOT_IT).performClick()
    assertThat(dismissed).isEqualTo(1)
    compose.onNodeWithText("Tap any word.").assertExists()
  }

  @Test
  fun theExplainControlNamesItsTermAndLearnMoreFollowsTheLink() {
    var target: LinkTarget? = null
    show {
      ExplainButton(term = "schwa", onClick = {})
      ExplainContent(entry, onDismiss = {}, onLearnMore = { target = it })
    }
    compose.onNodeWithContentDescription("What's this: schwa").assertExists()
    compose.onNodeWithTag(UiTags.EXPLAIN_LEARN_MORE).performClick()
    assertThat(target).isEqualTo(LinkTarget.Learn)
  }

  @Test
  fun theColourVisionPaletteWithPatternsStillPassesTheAudit() {
    show(choice = ColorChoice.COLOR_VISION_FRIENDLY) {
      MarkedWordSpan("water", Family.TD, state = WordMarkState.PLAYING)
      MarkedWordSpan("pick", Family.BOUNDARY, linksToNext = true)
      MarkedWordSpan("probably", null, state = WordMarkState.LOW_CONFIDENCE)
      ChangeChip("Linking", Family.BOUNDARY, onClick = {})
    }
    auditAtBothScales()
    compose.onNodeWithText("water").assertExists()
  }
}
