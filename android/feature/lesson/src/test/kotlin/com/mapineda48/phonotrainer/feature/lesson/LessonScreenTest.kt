package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.MouthMode
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTags
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import com.mapineda48.phonotrainer.core.ui.extension.MouthFrame
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.extension.MouthTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of the word lesson (work-plan WP-18 Proof): every step and state (low confidence, nothing heard, no
 * dictionary form, no audio, espeak data), the Compare sorts' TalkBack words ("changed", "not heard": F-10-AC1,
 * AC2), Compact guidance (F-10-AC3), the boundary-gap line, "What's this?" next to "What was said" (F-14-AC1), each
 * with the accessibility audit at font scale 2.0 in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class LessonScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  private fun lesson(
    state: LessonUiState,
    actions: LessonActions = LessonActions(),
    presentation: LessonPresentation = (state as? LessonUiState.Ready)?.presentation ?: LessonPresentation.SHEET,
    mouth: MouthRenderer? = null,
    record: RecordSlot? = null,
  ): @Composable () -> Unit = {
    LessonScreen(
      state = state,
      presentation = presentation,
      actions = actions,
      glossary = LessonPreviewData.glossary::get,
      mouth = mouth,
      record = record,
    )
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  private fun scrollToAndClick(tag: String) {
    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()
  }

  @Test
  fun theHeaderNamesTheWordWhereItIsAndItsChanges() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.water())) {
      compose.onNodeWithTag(LessonTags.WORD).assertTextContains("“water”")
      compose.onNodeWithTag(LessonTags.META).assertTextContains("Word 12 of 431 · phrase 4 · 0:44.8–0:45.2")
      compose.onNode(hasContentDescription("Flapping", substring = true) and hasAnyAncestor(hasTestTag(LessonTags.CHANGES))).assertExists()
      compose.onNode(hasText("Safe to produce · universal") and hasAnyAncestor(hasTestTag(LessonTags.CHANGES)), useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LessonTags.PREVIOUS).assert(hasContentDescription("Previous word"))
      compose.onNodeWithTag(LessonTags.NEXT).assert(hasContentDescription("Next word"))
      compose.onNodeWithTag(LessonTags.NEXT_CHANGE).assertTextContains("Next change")
      compose.onNodeWithTag(LessonTags.PAUSED).assertTextContains("Paused · Two friends at the lake")
    }

  @Test
  fun theHeaderActionsReachTheViewModelAndTheShell() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(
      onClose = { calls += "close" },
      onToggleSaved = { calls += "save" },
      onPrevious = { calls += "previous" },
      onNext = { calls += "next" },
      onNextChange = { calls += "nextChange" },
      onOpenChange = { calls += "change:${it.value}" },
    )
    screen.setContent(content = lesson(LessonPreviewData.water(), actions))
    compose.onNodeWithTag(LessonTags.SAVE).performClick()
    compose.onNodeWithTag(LessonTags.PREVIOUS).performClick()
    compose.onNodeWithTag(LessonTags.NEXT).performClick()
    compose.onNodeWithTag(LessonTags.NEXT_CHANGE).performClick()
    compose.onNode(hasContentDescription("Flapping", substring = true) and hasAnyAncestor(hasTestTag(LessonTags.CHANGES))).performClick()
    compose.onNodeWithTag(LessonTags.CLOSE).performClick()
    assertThat(calls).containsExactly("save", "previous", "next", "nextChange", "change:flapping", "close").inOrder()
  }

  @Test
  fun atTheLastChangeNextChangeIsDisabled() {
    val state = LessonPreviewData.water().let { it.copy(header = it.header.copy(canNextChange = false, canNext = false)) }
    screen.setContent(content = lesson(state))
    compose.onNodeWithTag(LessonTags.NEXT_CHANGE).assertIsNotEnabled()
    compose.onNodeWithTag(LessonTags.NEXT).assertIsNotEnabled()
    compose.onNodeWithTag(LessonTags.PREVIOUS).assertIsEnabled()
  }

  @Test
  fun aSavedWordShowsItsToggleOn() {
    screen.setContent(content = lesson(LessonPreviewData.water(saved = true)))
    val toggle = compose.onNodeWithTag(LessonTags.SAVE).fetchSemanticsNode()
    assertThat(toggle.config.getOrNull(SemanticsProperties.ToggleableState).toString()).isEqualTo("On")
  }

  @Test
  fun compactGuidanceShowsOnlyTheFiveTitlesAndEachOpensOnTap() {
    // F-10-AC3: only the five step titles are visible, and each opens on tap.
    screen.setContent(content = {
      var state by remember { mutableStateOf(LessonPreviewData.water(open = emptySet(), selected = false)) }
      LessonScreen(
        state = state,
        presentation = LessonPresentation.SHEET,
        actions = LessonActions(onToggleStep = { step ->
          state = state.copy(open = if (step in state.open) state.open - step else state.open + step)
        }),
        glossary = LessonPreviewData.glossary::get,
      )
    })
    LessonStep.entries.forEach { step ->
      compose.onNodeWithTag(LessonTags.step(step)).performScrollTo().assertIsDisplayed()
      compose.onNodeWithTag(LessonTags.stepBody(step)).assertDoesNotExist()
    }
    compose.onNodeWithTag(LessonTags.step(LessonStep.LISTEN)).assertTextContains("played 2×", substring = true)
    LessonStep.entries.forEach { step ->
      scrollToAndClick(LessonTags.step(step))
      compose.onNodeWithTag(LessonTags.stepBody(step)).assertExists()
    }
    LessonAudit.assertClean(compose, compose.density)
  }

  @Test
  fun fullGuidanceOpensTheFirstFourSteps() {
    screen.setContent(content = lesson(LessonPreviewData.water()))
    listOf(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.WHY, LessonStep.SEE).forEach {
      compose.onNodeWithTag(LessonTags.stepBody(it)).assertExists()
    }
    compose.onNodeWithTag(LessonTags.stepBody(LessonStep.PRACTICE)).assertDoesNotExist()
    val listen = compose.onNodeWithTag(LessonTags.step(LessonStep.LISTEN)).fetchSemanticsNode()
    assertThat(listen.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("Open")
    assertThat(listen.config.getOrNull(SemanticsProperties.ContentDescription)).contains("Step 1")
  }

  // --- step 1 ------------------------------------------------------------------------------------------------------

  @Test
  fun listenPlaysTheWordWithTheNextWordAndThePhrase() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(
      onPlay = { calls += "play:$it" },
      onSpeed = { calls += "speed:$it" },
      onLoop = { calls += "loop:$it" },
      onTrack = { calls += "track:$it" },
    )
    screen.setContent(content = lesson(LessonPreviewData.didYou(open = setOf(LessonStep.LISTEN)), actions))
    compose.onNodeWithTag(LessonTags.PLAY_WITH_NEXT).assertTextContains("Play with “you”")
    compose.onNodeWithTag(LessonTags.CROSSING)
      .assertTextContains("This change happens between “did” and “you”: listen to them together.")
    scrollToAndClick(LessonTags.PLAY_WORD)
    scrollToAndClick(LessonTags.PLAY_WITH_NEXT)
    scrollToAndClick(LessonTags.PLAY_PHRASE)
    compose.onNode(hasText("0.5×") and hasAnyAncestor(hasTestTag(LessonTags.SPEED))).performScrollTo().performClick()
    scrollToAndClick(LessonTags.LOOP)
    compose.onNode(hasText("Original mix") and hasAnyAncestor(hasTestTag(LessonTags.TRACK))).performScrollTo().performClick()
    assertThat(calls).containsExactly(
      "play:WORD", "play:WITH_NEXT", "play:PHRASE", "speed:0.5", "loop:false", "track:${Track.MIX}",
    ).inOrder()
  }

  @Test
  fun withoutAudioStepsOneAndFiveAreDisabledWithTheReasonAndTwoToFourStayReadable() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.noAudio())) {
      compose.onNode(
        hasText("This clip came without its audio: you can read it, but not listen to it.") and hasAnyAncestor(hasTestTag(LessonTags.NO_AUDIO)),
        useUnmergedTree = true,
      ).assertExists()
      compose.onNodeWithTag(LessonTags.PLAY_WORD).assertIsNotEnabled()
      compose.onNodeWithTag(LessonTags.PLAY_PHRASE).assertIsNotEnabled()
      compose.onNodeWithTag(LessonTags.SOUND_PLAY).assertIsNotEnabled()
      compose.onNodeWithTag(ShadowingTags.START).assertIsNotEnabled()
      tag(ShadowingTags.REASON).assertTextContains("This clip came without its audio: you can read it, but not listen to it.")
      compose.onNodeWithTag(LessonTags.sort(2)).assertExists()
    }

  // --- step 2 ------------------------------------------------------------------------------------------------------

  @Test
  fun compareShowsBothFormsTheLegendAndEverySoundWithItsMark() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.water())) {
      compose.onNodeWithTag(LessonTags.FORM_DICTIONARY).assertExists()
      compose.onNodeWithText("/ˈwɔtɚ/", useUnmergedTree = true).assertExists()
      compose.onNodeWithText("[ˈwɔɾɚ]", useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LessonTags.LEGEND)
        .assert(hasContentDescription("What the marks mean: = same, ≠ changed, – dropped, + added"))
      // Every sound shows, without truncation, at every font scale (spec F-10 step 2).
      (0..3).forEach { compose.onNodeWithTag(LessonTags.sort(it)).performScrollTo().assertIsDisplayed() }
    }

  @Test
  fun theChangedSortPairsTheExpectedTWithTheHeardTapAndTalkBackSaysChanged() {
    // F-10-AC1: the expected [t] and the heard [ɾ] are paired with "≠", read as "changed".
    screen.setContent(content = lesson(LessonPreviewData.water()))
    val sort = compose.onNodeWithTag(LessonTags.sort(2))
    sort.assert(hasContentDescription("t changed to ɾ"))
    sort.assertIsSelected()
    compose.onNode(hasText("≠") and hasAnyAncestor(hasTestTag(LessonTags.sort(2))), useUnmergedTree = true).assertExists()
    compose.onNode(hasText("t") and hasAnyAncestor(hasTestTag(LessonTags.sort(2))), useUnmergedTree = true).assertExists()
    compose.onNode(hasText("ɾ") and hasAnyAncestor(hasTestTag(LessonTags.sort(2))), useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(LessonTags.sort(0)).assert(hasContentDescription("w, same"))
  }

  @Test
  fun aDroppedSoundCarriesItsDashAndTalkBackSaysNotHeard() {
    // F-10-AC2: the dropped [j] carries "–" and is read "j, not heard".
    screen.setContent(content = lesson(LessonPreviewData.didYou()))
    val sort = compose.onNodeWithTag(LessonTags.sort(3))
    sort.assert(hasContentDescription("j, not heard"))
    compose.onNode(hasText("–") and hasAnyAncestor(hasTestTag(LessonTags.sort(3))), useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(LessonTags.sort(4)).assert(hasContentDescription("uː changed to ə"))
  }

  @Test
  fun anAddedSoundReadsAdded() {
    screen.setContent(content = lesson(LessonPreviewData.contraction()))
    compose.onNodeWithTag(LessonTags.sort(2)).assert(hasContentDescription("ʔ, added"))
    compose.onNode(hasText("+") and hasAnyAncestor(hasTestTag(LessonTags.sort(2))), useUnmergedTree = true).assertExists()
    compose.onNodeWithText("+ added · [ʔ]", useUnmergedTree = true).assertExists()
  }

  @Test
  fun acrossABoundaryEachWordHasItsHeaderAndTheGapLineSaysTheyRunTogether() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.didYou())) {
      compose.onNodeWithTag(LessonTags.sortGroup(next = false)).assertTextContains("“did”")
      compose.onNodeWithTag(LessonTags.sortGroup(next = true)).assertTextContains("“you”")
      compose.onNodeWithTag(LessonTags.GAP).performScrollTo().assertTextContains(
        "Gap between “did” and “you”: 12 ms — they run together: that is the linking (an unlinked boundary sits nearer 60 ms).",
      )
    }

  @Test
  fun aWideGapSaysThereIsNoClearLinkingAndAnOverlapSaysSo() {
    var state by mutableStateOf(LessonPreviewData.didYou().let { it.copy(compare = it.compare.copy(gap = GapUi(64, it.compare.gap!!.family))) })
    screen.setContent(content = { LessonScreen(state, LessonPresentation.FULL, LessonActions()) })
    compose.onNodeWithTag(LessonTags.GAP).assertTextContains("Gap between “did” and “you”: 64 ms — no clear linking at this boundary.")
    state = state.copy(compare = state.compare.copy(gap = GapUi(-5, state.compare.gap!!.family)))
    compose.waitForIdle()
    compose.onNodeWithTag(LessonTags.GAP).assertTextContains("none, the sounds overlap", substring = true)
  }

  @Test
  fun aSortPlaysItsSoundAndItsCardOffersTheSoundTheMouthAndTheChart() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(
      onPickSort = { calls += "pick:$it" },
      onPlaySound = { calls += "play" },
      onSeeMouth = { calls += "mouth" },
      onIpaChart = { calls += "chart:$it" },
    )
    screen.setContent(content = lesson(LessonPreviewData.water(), actions))
    scrollToAndClick(LessonTags.sort(1))
    compose.onNodeWithText("≠ changed · /t/ → [ɾ]", useUnmergedTree = true).assertExists()
    compose.onNodeWithText("voiced alveolar tap", useUnmergedTree = true).assertExists()
    compose.onNodeWithText("As in “water”.", useUnmergedTree = true).assertExists()
    scrollToAndClick(LessonTags.SOUND_PLAY)
    scrollToAndClick(LessonTags.SOUND_MOUTH)
    scrollToAndClick(LessonTags.SOUND_CHART)
    assertThat(calls).containsExactly("pick:1", "play", "mouth", "chart:ɾ").inOrder()
  }

  @Test
  fun aSoundOutsideTheInventoryIsSaidSoAndHasNoMouthButton() {
    screen.setContent(content = lesson(LessonPreviewData.contraction()))
    compose.onNodeWithText("a sound outside the drawable inventory", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(LessonTags.SOUND_MOUTH).assertDoesNotExist()
  }

  @Test
  fun theHelpNextToWhatWasSaidShowsTheWebEntryAndClosingReturnsTheFocus() {
    // F-14-AC1: "?" next to "What was said" opens the entry with its example; closing returns the focus to the "?".
    screen.setContent(content = lesson(LessonPreviewData.water()))
    val help = compose.onNode(hasContentDescription("What's this: What was said") and hasTestTag(UiTags.EXPLAIN_BUTTON))
    help.performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithText("The sounds the recognizer heard, in square brackets: [ˈwɔɾɚ].", useUnmergedTree = true).assertExists()
    compose.onNodeWithText("water → [ˈwɔɾɚ]", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(UiTags.EXPLAIN_CLOSE).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(UiTags.EXPLAIN_CLOSE).assertDoesNotExist()
    help.assertIsFocused()
  }

  @Test
  fun learnMoreInAHelpSheetGoesWhereTheEntrySays() {
    val targets = mutableListOf<String>()
    screen.setContent(content = lesson(LessonPreviewData.water(), LessonActions(onLearnMore = { targets += it.toString() })))
    compose.onNode(hasContentDescription("What's this: What was said") and hasTestTag(UiTags.EXPLAIN_BUTTON)).performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(UiTags.EXPLAIN_LEARN_MORE).performClick()
    compose.waitForIdle()
    assertThat(targets).containsExactly("Ipa(symbol=null)")
    compose.onNodeWithTag(UiTags.EXPLAIN_CLOSE).assertDoesNotExist()
  }

  @Test
  fun aTermTheContentLacksHasNoHelpButton() {
    screen.setContent(content = { LessonScreen(LessonPreviewData.water(), LessonPresentation.SHEET, LessonActions(), glossary = { null }) })
    compose.onNode(hasContentDescription("What's this: What was said")).assertDoesNotExist()
    compose.onNode(hasContentDescription("What's this: Dictionary")).assertDoesNotExist()
  }

  @Test
  fun noDictionaryFormShowsTheWebNoticeInsteadOfTheSorts() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.noDictionaryForm())) {
      tag(LessonTags.NO_DICTIONARY).assertExists()
      compose.onNodeWithText(
        "“%” is a number or symbol the dictionary cannot spell out, so there is nothing to compare what was said against.",
        substring = true,
        useUnmergedTree = true,
      ).assertExists()
      compose.onNodeWithTag(LessonTags.SORTS).assertDoesNotExist()
      compose.onNodeWithTag(LessonTags.SPOKEN).assertTextContains("Written “%”, said as “percent”: the comparison uses the spoken form.")
      compose.onNodeWithTag(LessonTags.NO_CHANGE)
        .assertTextContains("With no dictionary form to compare against, no change can be labeled here.")
    }

  @Test
  fun anEspeakAnalysisShowsTheAlignersRowAndFormScoring() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.espeak())) {
      compose.onNodeWithTag(LessonTags.FORM_ALIGNER).assertExists()
      compose.onNodeWithText("Expected by the aligner", useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LessonTags.COMPARE_NOTE).assertTextContains("With the espeak engine", substring = true)
      compose.onNodeWithText("From form scoring:", substring = true, useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LessonTags.FORM_SCORING, useUnmergedTree = true)
        .assertTextContains("Form scoring heard the weak form [tə] — the weak form scored ahead by 1.4 against the citation form [tuː].")
    }

  // --- step 3 ------------------------------------------------------------------------------------------------------

  @Test
  fun whyItChangesExplainsTheChangeWithItsAdviceAndTwoWaysOn() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(onWatchMore = { calls += "watch:${it.value}" }, onOpenChange = { calls += "learn:${it.value}" })
    screen.setContent(content = lesson(LessonPreviewData.water(), actions))
    compose.onNodeWithTag(LessonTags.card("flapping")).performScrollTo()
    compose.onNodeWithText("Safe to produce and one of the most rewarding things to learn (§3A).", useUnmergedTree = true).assertExists()
    scrollToAndClick(LessonTags.watchMore("flapping"))
    compose.onNodeWithTag(LessonTags.learnAbout("flapping")).assertTextContains("Learn about flapping")
    scrollToAndClick(LessonTags.learnAbout("flapping"))
    assertThat(calls).containsExactly("watch:flapping", "learn:flapping").inOrder()
  }

  @Test
  fun aChangesHelpOpensItsSheetWithTheAdviceAndWatchMore() {
    val calls = mutableListOf<ChangeLabel>()
    screen.setContent(content = lesson(LessonPreviewData.water(), LessonActions(onWatchMore = { calls += it })))
    val help = compose.onNode(hasContentDescription("What's this: Flapping") and hasAnyAncestor(hasTestTag(LessonTags.card("flapping"))))
    help.performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(LessonTags.CHANGE_SHEET).assertExists()
    compose.onNodeWithTag(LessonTags.CHANGE_SHEET_WATCH).performClick()
    compose.waitForIdle()
    assertThat(calls).containsExactly(ChangeLabel("flapping"))
    compose.onNodeWithTag(LessonTags.CHANGE_SHEET).assertDoesNotExist()
  }

  @Test
  fun lowConfidenceShowsItsBadgeTheNoticeFirstAndTheFooterFacts() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.lowConfidence())) {
      compose.onNodeWithTag(LessonTags.RELIABILITY).assertTextContains("Low confidence")
      tag(LessonTags.NOTICE_LOW_CONFIDENCE).assertExists()
      val notice = compose.onNodeWithTag(LessonTags.NOTICE_LOW_CONFIDENCE).fetchSemanticsNode().positionInRoot.y
      val card = compose.onNodeWithTag(LessonTags.card("t_deletion")).fetchSemanticsNode().positionInRoot.y
      assertThat(notice).isLessThan(card)
      compose.onNodeWithText("Kind of link: consonant → vowel", useUnmergedTree = true).assertExists()
      compose.onNodeWithText("The peak fell on a grammar word: contrast or emphasis.", useUnmergedTree = true).assertExists()
      compose.onNodeWithText("2 sounds were kept as expected after a recognizer confusion (attraction).", substring = true, useUnmergedTree = true).assertExists()
      compose.onNodeWithText("Not in the dictionary: its dictionary form was guessed from the spelling.", substring = true, useUnmergedTree = true).assertExists()
      compose.onNodeWithText("Approximate timing: the sounds were placed using the transcript's word times.", substring = true, useUnmergedTree = true).assertExists()
      // A "Recognize only" change: shadowing trains the ear (spec F-12).
      tag(ShadowingTags.RECOGNIZE_ONLY).assertExists()
    }

  @Test
  fun nothingHeardShowsItsBadgeAndTheNotice() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.nothingHeard())) {
      compose.onNodeWithTag(LessonTags.RELIABILITY).assertTextContains("Nothing heard here")
      tag(LessonTags.NOTICE_NOTHING_HEARD).assertExists()
      compose.onNodeWithText("Nothing was heard here", substring = true, useUnmergedTree = true).assertExists()
      compose.onNodeWithText("[∅]", useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(LessonTags.sort(0)).assert(hasContentDescription("w, not heard"))
    }

  @Test
  fun aContractionShowsItsReducedForm() {
    screen.setContent(content = lesson(LessonPreviewData.contraction()))
    compose.onNodeWithText("Reduced form: “gonna” ← “going to”", useUnmergedTree = true).assertExists()
  }

  // --- step 4 ------------------------------------------------------------------------------------------------------

  @Test
  fun withoutTheArticulatorEachSoundIsNamedWithWhatTheMouthDoes() =
    screen.checkEachScrolling(content = lesson(LessonPreviewData.water(open = setOf(LessonStep.SEE)))) {
      // The v1-core fallback (work-plan 4.4): no drawing, each sound's name and its sentence.
      compose.onNodeWithTag(LessonTags.MOUTH_DRAWING).assertDoesNotExist()
      compose.onNodeWithTag(LessonTags.mouthSound(2)).assertTextContains("voiced alveolar tap", substring = true)
      compose.onNodeWithTag(LessonTags.mouthSound(2)).assertTextContains("What the mouth does for [ɾ].", substring = true)
      compose.onNodeWithTag(LessonTags.mouthSound(3)).assertIsNotEnabled()
      compose.onNodeWithTag(LessonTags.mouthSound(3)).assertTextContains("No drawing for this sound yet.", substring = true)
    }

  @Test
  fun stepFourHoldsASoundAndSwitchesItsRow() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(onHoldSound = { calls += "hold:$it" }, onStream = { calls += "stream:$it" })
    screen.setContent(content = lesson(LessonPreviewData.water(open = setOf(LessonStep.SEE)), actions))
    scrollToAndClick(LessonTags.mouthSound(1))
    compose.onNode(hasText("Dictionary form") and hasAnyAncestor(hasTestTag(LessonTags.MOUTH_STREAM))).performScrollTo().performClick()
    assertThat(calls).containsExactly("hold:1", "stream:${MouthStream.DICTIONARY}").inOrder()
  }

  @Test
  fun withTheArticulatorStepFourDrawsTheHeldSoundAndCanFollowAgain() {
    val renderer = RecordingMouth()
    val calls = mutableListOf<String>()
    val state = LessonPreviewData.water(open = setOf(LessonStep.SEE)).let { it.copy(see = it.see.copy(held = 2)) }
    screen.setContent(content = lesson(state, LessonActions(onFollow = { calls += "follow" }), mouth = renderer))
    compose.onNodeWithTag(LessonTags.MOUTH_DRAWING).assert(hasContentDescription("Side view of the mouth: [ɾ], voiced alveolar tap"))
    compose.onNodeWithText("[ɾ] voiced alveolar tap · as in “water”", useUnmergedTree = true).assertExists()
    assertThat(renderer.tracked.single().map { it.symbol }).containsExactly("w", "ɔ", "ɾ").inOrder()
    // The held sound's middle: (44 980 + 45 060) / 2.
    assertThat(renderer.frames.last()).isEqualTo(45_020L)
    scrollToAndClick(LessonTags.MOUTH_FOLLOW)
    assertThat(calls).containsExactly("follow")
  }

  // --- step 5 ------------------------------------------------------------------------------------------------------

  @Test
  fun practiceOffersWhatToShadowAndTheSlowSpeedThenStarts() {
    val calls = mutableListOf<String>()
    val actions = LessonActions(onPractice = { target, speed -> calls += "practice:$target:$speed" }, onStartShadowing = { calls += "start" })
    screen.setContent(content = lesson(LessonPreviewData.water(open = setOf(LessonStep.PRACTICE)), actions))
    tag(ShadowingTags.INTRO).assertExists()
    compose.onNode(hasText("The word and the next") and hasAnyAncestor(hasTestTag(ShadowingTags.TARGET))).performScrollTo().performClick()
    compose.onNode(hasText("0.5×") and hasAnyAncestor(hasTestTag(ShadowingTags.SPEED))).performScrollTo().performClick()
    scrollToAndClick(ShadowingTags.START)
    assertThat(calls).containsExactly(
      "practice:${ShadowingTarget.WORD}:0.75", "practice:${ShadowingTarget.PHRASE}:0.5", "start",
    ).inOrder()
  }

  @Test
  fun runningShadowingShowsTheLiveLineTheEightPhaseBarAndStop() {
    val calls = mutableListOf<String>()
    screen.checkEachScrolling(content = lesson(LessonPreviewData.didYou(), LessonActions(onStopShadowing = { calls += "stop" }))) {
      tag(ShadowingTags.LINE).assertTextContains("Round 2 of 4: your turn — say it now")
      compose.onNodeWithTag(ShadowingTags.PHASES).assert(hasContentDescription("Phase 4 of 8"))
    }
    scrollToAndClick(ShadowingTags.STOP)
    assertThat(calls).containsExactly("stop")
  }

  @Test
  fun theRecordSectionFillsItsSlotOnlyWhenItsModuleIsThere() {
    var recorded: Pair<ClipSpan, RecordTarget>? = null
    val record: RecordSlot = { span, target, modifier ->
      recorded = span to target
      Box(modifier.size(48.dp).testTag("record.section"))
    }
    screen.setContent(content = lesson(LessonPreviewData.water(open = setOf(LessonStep.PRACTICE)), record = record))
    compose.onNodeWithTag(LessonTags.RECORD).assertExists()
    assertThat(recorded?.second?.phrase).isTrue()
  }

  @Test
  fun inVersionOneCoreStepFiveIsShadowingOnly() {
    screen.setContent(content = lesson(LessonPreviewData.water(open = setOf(LessonStep.PRACTICE))))
    compose.onNodeWithTag(LessonTags.RECORD).assertDoesNotExist()
    compose.onNodeWithTag(ShadowingTags.START).assertExists()
  }

  // --- frames and states -------------------------------------------------------------------------------------------

  @Test
  fun theFullPageHasNoPausedPill() {
    screen.setContent(content = lesson(LessonPreviewData.water(presentation = LessonPresentation.FULL)))
    compose.onNodeWithTag(LessonTags.PAUSED).assertDoesNotExist()
    compose.onNodeWithTag(LessonTags.ROOT).assertExists()
  }

  @Test
  fun everyStatePassesTheAuditAtLargeTextInLightAndDark() {
    var current by mutableStateOf<LessonUiState>(LessonPreviewData.water())
    screen.setContent(ScreenVariant(fontScale = 2f)) {
      LessonScreen(current, (current as? LessonUiState.Ready)?.presentation ?: LessonPresentation.SHEET, LessonActions(), glossary = LessonPreviewData.glossary::get)
    }
    val states = listOf(
      LessonPreviewData.water(),
      LessonPreviewData.didYou(),
      LessonPreviewData.lowConfidence(),
      LessonPreviewData.nothingHeard(),
      LessonPreviewData.noDictionaryForm(),
      LessonPreviewData.noAudio(),
      LessonPreviewData.espeak(),
      LessonPreviewData.contraction(),
      LessonUiState.Unavailable(Unavailable.CLIP_GONE),
      LessonUiState.Unavailable(Unavailable.CLIP_DAMAGED),
      LessonUiState.Unavailable(Unavailable.WORD_GONE),
    )
    ScreenVariant.REQUIRED.forEach { variant ->
      screen.show(variant)
      states.forEach { state ->
        current = state
        compose.waitForIdle()
        try {
          compose.onNodeWithTag(LessonTags.ROOT).assertExists()
          LessonAudit.assertClean(compose, compose.density)
        } catch (failure: Throwable) {
          throw AssertionError("At $variant with $state: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun theUnavailableStatesSayWhyAndClose() {
    var calls = 0
    var state by mutableStateOf<LessonUiState>(LessonUiState.Unavailable(Unavailable.CLIP_GONE))
    screen.setContent(content = { LessonScreen(state, LessonPresentation.SHEET, LessonActions(onClose = { calls++ })) })
    compose.onNodeWithText("That clip is no longer on this phone.").assertExists()
    compose.onNodeWithText("Close").performClick()
    state = LessonUiState.Unavailable(Unavailable.CLIP_DAMAGED)
    compose.waitForIdle()
    compose.onNodeWithText("The clip's data was damaged on this phone: it needs downloading again.").assertExists()
    state = LessonUiState.Unavailable(Unavailable.WORD_GONE)
    compose.waitForIdle()
    compose.onNodeWithText("That word is no longer in this clip.").assertExists()
    assertThat(calls).isEqualTo(1)
  }

  /**
   * In the sheet every state fills the sheet's height, so the sheet's size never changes as the lesson loads: Material's
   * sheet settles where its content's first size puts it, and a lesson that grew after opening settled fully expanded,
   * where Back only brings the sheet down to half height (found by WP-17's Watch area test on the managed device).
   */
  @Test
  fun inTheSheetEveryStateFillsTheSheetsHeightSoItsSizeNeverChangesWhileItLoads() {
    var state by mutableStateOf<LessonUiState>(LessonUiState.Loading)
    screen.setContent(content = {
      Box(Modifier.size(width = 412.dp, height = 600.dp)) {
        LessonScreen(state, LessonPresentation.SHEET, LessonActions(), glossary = LessonPreviewData.glossary::get)
      }
    })
    listOf(
      LessonUiState.Loading,
      LessonUiState.Unavailable(Unavailable.WORD_GONE),
      LessonPreviewData.water(),
      LessonPreviewData.water(open = emptySet()),
    ).forEach {
      state = it
      compose.waitForIdle()
      compose.onNodeWithTag(LessonTags.ROOT).assertHeightIsEqualTo(600.dp)
    }
  }

  @Test
  fun loadingShowsItsWordsAfterASecond() {
    screen.setContent(content = lesson(LessonUiState.Loading))
    compose.mainClock.advanceTimeBy(1_200)
    compose.onNodeWithText("Opening the lesson…", useUnmergedTree = true).assertExists()
  }

  /** WP-29's renderer as a test double: it records the track it was given and the instants it drew. */
  private class RecordingMouth : MouthRenderer {
    val tracked = mutableListOf<List<TimedPhone>>()
    val frames = mutableListOf<Long>()

    @Composable
    override fun Mouth(frame: MouthFrame, modifier: Modifier) {
      Box(modifier)
    }

    override fun track(phones: List<TimedPhone>, mode: MouthMode): MouthTrack {
      tracked += phones
      return MouthTrack { ms ->
        frames += ms
        MouthFrame(mapOf("jaw" to 0.5))
      }
    }
  }
}
