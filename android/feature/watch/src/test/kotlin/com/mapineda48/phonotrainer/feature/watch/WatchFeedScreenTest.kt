package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.QuestionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of the feed around the cards (work-plan WP-17 Proof): the header, moving between shorts (and the
 * instant move with reduced motion, F-06-AC9), the set's questions and summary, the end cards, the empty states and
 * the sheets, each with its accessibility audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class WatchFeedScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  private fun feed(state: WatchUiState, actions: WatchActions = WatchActions(), sheet: WatchSheet = WatchSheet.NONE): @androidx.compose.runtime.Composable () -> Unit = {
    WatchScreen(state = state, positionMs = { 44_900 }, volumeMuted = false, actions = actions, initialSheet = sheet)
  }

  @Test
  fun inASetTheHeaderShowsTheModeThePositionAndTheBeads() =
    screen.checkEach(ScreenVariant.MATRIX, feed(WatchPreviewData.set(current = 1))) { variant ->
      compose.onNode(hasTestTag(WatchTags.MODE_PICKER) and hasContentDescription("What the feed shows: Mix · Today's set")).assertExists()
      compose.onNodeWithTag(WatchTags.POSITION).assertTextContains("2 of 3")
      if (variant.fontScale < LARGE_TEXT) compose.onNodeWithTag(WatchTags.BEADS).assertExists()
      compose.onNodeWithTag(WatchTags.PREVIOUS).assertExists()
      compose.onNodeWithTag(WatchTags.NEXT).assertExists()
      compose.onNodeWithTag(WatchTags.SAVED_TAG, useUnmergedTree = true).assertExists()
    }

  @Test
  fun aShortSetSaysSoOnItsFirstShort() {
    screen.setContent(content = feed(WatchPreviewData.set(current = 0)))
    compose.onNodeWithText("Only 3 shorts today: add a clip for more.").assertExists()
  }

  @Test
  fun inOrderTheHeaderCountsShortsAndTheFirstCannotGoBack() {
    screen.setContent(content = feed(WatchPreviewData.ready()))
    compose.onNodeWithTag(WatchTags.POSITION).assertTextContains("1 / 1")
    compose.onNodeWithTag(WatchTags.PREVIOUS).assertIsNotEnabled()
  }

  @Test
  fun theNextShortButtonMovesOneShort() {
    val settled = mutableListOf<Int>()
    screen.setContent(content = feed(WatchPreviewData.set(current = 0), WatchActions(onPageSettled = { settled += it })))
    compose.onNodeWithTag(WatchTags.NEXT).performClick()
    compose.waitForIdle()
    assertThat(settled).containsExactly(0, 1).inOrder()
    compose.onNodeWithTag(WatchTags.PREVIOUS).performClick()
    compose.waitForIdle()
    assertThat(settled.last()).isEqualTo(0)
  }

  @Test
  fun withReducedMotionTheMoveIsInstant() {
    // F-06-AC9: the change is instant with no sliding animation; with motion it slides over 280 ms.
    val settled = mutableListOf<Int>()
    screen.setContent(ScreenVariant(reducedMotion = true), feed(WatchPreviewData.set(current = 0), WatchActions(onPageSettled = { settled += it })))
    compose.waitForIdle()
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag(WatchTags.NEXT).performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    assertThat(settled.last()).isEqualTo(1)
  }

  @Test
  fun withMotionTheMoveSlides() {
    val settled = mutableListOf<Int>()
    screen.setContent(ScreenVariant(reducedMotion = false), feed(WatchPreviewData.set(current = 0), WatchActions(onPageSettled = { settled += it })))
    compose.waitForIdle()
    compose.mainClock.autoAdvance = false
    compose.onNodeWithTag(WatchTags.NEXT).performClick()
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    assertThat(settled.last()).isEqualTo(0)
    compose.mainClock.advanceTimeBy(1_000)
    assertThat(settled.last()).isEqualTo(1)
  }

  @Test
  fun aSetQuestionShowsTheQuizCardAndItsPosition() {
    val answers = mutableListOf<String>()
    screen.checkEach(
      ScreenVariant.REQUIRED,
      feed(WatchPreviewData.set(current = 3), WatchActions(question = QuestionCallbacks(onAnswer = { _, choice -> answers += choice }))),
    ) {
      compose.onNodeWithTag(WatchTags.POSITION).assertTextContains("Question 1 of 2")
      compose.onNodeWithText("How was “water” really said?").assertExists()
    }
    compose.onNodeWithTag(QuestionTags.option("a")).performScrollTo().performClick()
    assertThat(answers).containsExactly("a")
  }

  @Test
  fun anAnsweredQuestionShowsItsFeedbackAndTheNextQuestion() {
    val saves = mutableListOf<String>()
    screen.checkEachScrolling(
      ScreenVariant.REQUIRED,
      feed(WatchPreviewData.set(current = 4), WatchActions(question = QuestionCallbacks(onSave = { saves += "save" }))),
    ) {
      compose.onNodeWithTag(WatchTags.NEXT_QUESTION).assertExists()
      compose.onNodeWithTag(QuestionTags.SAVE).assertExists()
    }
    // F-08-AC6: "Save" is offered and saves only when tapped.
    assertThat(saves).isEmpty()
    compose.onNodeWithTag(QuestionTags.SAVE).performScrollTo().performClick()
    assertThat(saves).containsExactly("save")
  }

  @Test
  fun theSetSummaryTalliesTheSetAndOffersHomeAnotherSetAndPractice() {
    // F-08-AC5: "4 of 5 right" and nothing past the card; F-08 item 6.
    val calls = mutableListOf<String>()
    val actions = WatchActions(
      end = EndCallbacks(onHome = { calls += "home" }, onAnotherSet = { calls += "another" }, onPractice = { calls += "practice:$it" }),
    )
    screen.checkEach(ScreenVariant.MATRIX, feed(WatchPreviewData.set(current = 5).copy(clipsOnTheWay = 3), actions)) {
      compose.onNodeWithText("Done for today").assertExists()
      compose.onNodeWithTag(WatchTags.END_TALLY).assertTextContains("8 shorts · 5 questions · 4 of 5 right")
      compose.onNodeWithTag(WatchTags.met("flapping")).assertExists()
      compose.onNodeWithText("Nothing plays after this card. A new set is ready after midnight.").assertExists()
      compose.onNodeWithText("3 more clips on the way").assertExists()
      compose.onNodeWithTag(WatchTags.POSITION).assertTextContains("Done")
      compose.onNodeWithTag(WatchTags.NEXT).assertIsNotEnabled()
    }
    compose.onNodeWithTag(WatchTags.END_HOME).performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.END_ANOTHER).performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.END_PRACTICE).performScrollTo().performClick()
    assertThat(calls).containsExactly("home", "another", "practice:null").inOrder()
  }

  @Test
  fun aSetWithoutQuestionsSaysThereWereNotEnoughClips() {
    val state = WatchPreviewData.set(current = 5)
    screen.setContent(content = feed(state.copy(summary = state.summary?.copy(questions = 0, right = 0, noQuestions = true))))
    compose.onNodeWithText("Not enough clips to build questions yet.").assertExists()
    compose.onNodeWithTag(WatchTags.END_TALLY).assertTextContains("8 shorts")
  }

  @Test
  fun theEndOfAClipOffersItAgainTheNextClipPracticeAndHome() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(end = EndCallbacks(onNextClip = { calls += "next:${it.value}" }, onWatchAgain = { calls += "again" }))
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready().copy(current = 1), actions)) {
      compose.onNodeWithText("End of Two friends at the lake").assertExists()
      compose.onNodeWithText("You saw 15 shorts with 14 kinds of change.").assertExists()
      compose.onNodeWithText("Next clip: At the station").assertExists()
    }
    compose.onNodeWithTag(WatchTags.END_NEXT_CLIP).performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.END_AGAIN).performScrollTo().performClick()
    assertThat(calls).containsExactly("next:${WatchPreviewData.audioJob.value}", "again").inOrder()
  }

  @Test
  fun theEndOfAChangeOffersItsLessonAndItsPractice() {
    val calls = mutableListOf<String>()
    val state = WatchPreviewData.ready().copy(
      mode = ModeName.Change(ChangeLabel("flapping"), "flapping", Family.TD),
      pages = listOf(WatchPreviewData.shortPage(), FeedPage.End(EndKind.CHANGE_END)),
      current = 1,
    )
    val actions = WatchActions(end = EndCallbacks(onLearn = { calls += "learn:${it.value}" }, onPractice = { calls += "practice:${it?.value}" }))
    screen.checkEach(ScreenVariant.REQUIRED, feed(state, actions)) {
      compose.onNodeWithText("That's every “flapping” in your clips.").assertExists()
    }
    compose.onNodeWithTag(WatchTags.END_LEARN).performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.END_PRACTICE).performScrollTo().performClick()
    assertThat(calls).containsExactly("learn:flapping", "practice:flapping").inOrder()
  }

  @Test
  fun theEndOfSavedAndTheMixWithNothingLeftSayWhatIsNext() {
    screen.checkEach(
      ScreenVariant.REQUIRED,
      feed(WatchUiState(loading = false, mode = ModeName.Mix(true), pages = listOf(FeedPage.End(EndKind.NOTHING_LEFT)))),
    ) {
      compose.onNodeWithText("You've seen every short. New ones appear when your computer finishes a clip.").assertExists()
      compose.onNodeWithTag(WatchTags.END_REPLAY_MIX).assertExists()
      compose.onNodeWithTag(WatchTags.END_ADD_CLIP).assertExists()
    }
  }

  @Test
  fun theEndOfSavedOffersPractice() {
    screen.setContent(content = feed(WatchUiState(loading = false, mode = ModeName.Saved, pages = listOf(FeedPage.End(EndKind.SAVED_END)))))
    compose.onNodeWithText("That's all your saved shorts.").assertExists()
    screen.audit()
  }

  @Test
  fun theFirstRunInvitesToConnectAddAClipOrLearn() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(empty = EmptyCallbacks(onConnect = { calls += "connect" }, onAddClip = { calls += "add" }, onLearn = { calls += "learn" }))
    screen.checkEach(ScreenVariant.MATRIX, feed(WatchUiState(loading = false, mode = ModeName.Mix(true), empty = FeedEmpty.FirstRun), actions)) {
      compose.onNodeWithText("Your feed fills up with your clips").assertExists()
      compose.onNodeWithText("Meanwhile, explore the changes in Learn").assertExists()
    }
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.0").performScrollTo().performClick()
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.1").performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.EMPTY_LEARN).performScrollTo().performClick()
    assertThat(calls).containsExactly("connect", "add", "learn").inOrder()
  }

  @Test
  fun clipsOnTheirWayShowTheProgress() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchUiState(loading = false, mode = ModeName.Mix(true), empty = FeedEmpty.ClipsComing(42)))) {
      compose.onNodeWithText("Your clips are still coming to this phone.").assertExists()
      compose.onNodeWithText("Bringing your clips: 42 %").assertExists()
    }

  @Test
  fun anEmptyChangeAndAnEmptySavedOfferTheMix() {
    val calls = mutableListOf<String>()
    screen.checkEach(
      ScreenVariant.REQUIRED,
      feed(
        WatchUiState(loading = false, mode = ModeName.Change(ChangeLabel("nt_reduction"), "nt → n", Family.TD), empty = FeedEmpty.NoShortsWithChange("nt → n")),
        WatchActions(empty = EmptyCallbacks(onWatchMix = { calls += "mix" })),
      ),
    ) {
      compose.onNodeWithText("No shorts with nt → n on this phone yet.").assertExists()
    }
    compose.onNodeWithText("Watch the Mix").performScrollTo().performClick()
    assertThat(calls).containsExactly("mix")
  }

  @Test
  fun nothingSavedSaysHowToSave() {
    screen.setContent(content = feed(WatchUiState(loading = false, mode = ModeName.Saved, empty = FeedEmpty.NothingSaved)))
    compose.onNodeWithText("Tap “Save” on a short or a word you want to hear again.").assertExists()
    screen.audit()
  }

  @Test
  fun theFeedSaysItIsLoadingOnlyAfterADelay() {
    screen.setContent(content = feed(WatchUiState()))
    compose.onNodeWithTag(WatchTags.LOADING).assertExists()
  }

  @Test
  fun thePickerShowsEachChangeWithItsShortCountAndAZeroOpensLearn() {
    // Spec D-48 / design 9.12: "Flapping · 34 shorts"; a zero reads "… · 0 · Learn" and opens Learn.
    val picks = mutableListOf<FeedStart>()
    val learns = mutableListOf<ChangeLabel>()
    val actions = WatchActions(onPick = { picks += it }, onLearnChange = { learns += it })
    screen.checkEachScrolling(
      ScreenVariant.REQUIRED,
      feed(WatchPreviewData.ready().copy(picker = WatchPreviewData.picker), actions, sheet = WatchSheet.PICKER),
    ) {
      compose.onNodeWithText("Flapping · 34 shorts").assertExists()
      compose.onNodeWithText("Nt → n · 0 · Learn").assertExists()
      compose.onNodeWithText("3 of 8 done").assertExists()
      compose.onNodeWithText("2 saved shorts").assertExists()
      compose.onNodeWithText("left at short 4").assertExists()
    }
    compose.onNodeWithTag(WatchTags.pickerChange("flapping")).performScrollTo().performClick()
    assertThat(picks).containsExactly(FeedStart.ChangeMode(ChangeLabel("flapping"), onlyClip = null))
  }

  @Test
  fun thePickerZeroChipRoutesToLearn() {
    val learns = mutableListOf<ChangeLabel>()
    screen.setContent(
      content = feed(WatchPreviewData.ready().copy(picker = WatchPreviewData.picker), WatchActions(onLearnChange = { learns += it }), WatchSheet.PICKER),
    )
    compose.onNodeWithTag(WatchTags.pickerChange("nt_reduction")).performScrollTo().performClick()
    assertThat(learns).containsExactly(ChangeLabel("nt_reduction"))
  }

  @Test
  fun theMoreSheetHasTheTrackTheCaptionsListenFirstAndTheLegend() {
    screen.checkEachScrolling(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready(), sheet = WatchSheet.MORE)) {
      compose.onNodeWithTag(WatchTags.MORE_TRACK).assertExists()
      compose.onNodeWithTag(WatchTags.MORE_CAPTIONS).assertExists()
      compose.onNodeWithTag(WatchTags.MORE_LISTEN_FIRST).assertExists()
      compose.onNodeWithTag(WatchTags.MORE_CLIP_PAGE).assertExists()
      compose.onNodeWithTag(WatchTags.MORE_LEAVE_OUT).assertExists()
      compose.onNodeWithTag(WatchTags.MORE_MARKS).assertExists()
      // v1-core: no "Record yourself", no "Pitch, stress and IPA of this phrase" (work-plan 4.4), the video is here.
      assertThat(compose.onAllNodesWithTagCount(WatchTags.MORE_RECORD)).isEqualTo(0)
      assertThat(compose.onAllNodesWithTagCount(WatchTags.MORE_PHRASE_DETAILS)).isEqualTo(0)
      assertThat(compose.onAllNodesWithTagCount(WatchTags.MORE_DOWNLOAD_VIDEO)).isEqualTo(0)
    }
    compose.onNodeWithTag(WatchTags.MORE_MARKS).performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(WatchTags.LEGEND_SHEET).assertExists()
  }

  @Test
  fun theMoreSheetOffersTheVideoWhenItIsNotOnThePhone() {
    val state = WatchPreviewData.ready(WatchPreviewData.shortPage(video = VideoAvailability.NotOnThisPhone))
    screen.setContent(content = feed(state, sheet = WatchSheet.MORE))
    compose.onNodeWithTag(WatchTags.MORE_DOWNLOAD_VIDEO).assertExists()
  }

  @Test
  fun theMoreSheetChangesTheCaptionsAndLeavesTheClipOut() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(onCaptions = { calls += "captions:$it" }, onExcluded = { calls += "out:$it" }, onListenFirst = { calls += "first:$it" })
    screen.setContent(content = feed(WatchPreviewData.ready(), actions, WatchSheet.MORE))
    compose.onNodeWithText("IPA").performClick()
    compose.onNodeWithTag(WatchTags.MORE_LISTEN_FIRST).performScrollTo().performClick()
    compose.onNodeWithTag(WatchTags.MORE_LEAVE_OUT).performScrollTo().performClick()
    assertThat(calls).containsExactly("captions:IPA", "first:true", "out:true").inOrder()
  }

  @Test
  fun theShadowSheetStartsShadowingTheCurrentPhrase() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(onShadowStart = { calls += "start:${it.slowSpeed}" })
    screen.setContent(content = feed(WatchPreviewData.ready(), actions, WatchSheet.SHADOW))
    compose.onNodeWithTag(com.mapineda48.phonotrainer.core.ui.component.ShadowingTags.START).performClick()
    assertThat(calls).containsExactly("start:0.75")
    WatchAudit.assertClean(compose, compose.density)
  }

  @Test
  fun theClipTitleOpensTheClipPage() {
    val calls = mutableListOf<String>()
    screen.setContent(content = feed(WatchPreviewData.ready(), WatchActions(card = CardCallbacks(onOpenClip = { calls += "clip" }))))
    compose.onNode(hasContentDescription("Open the clip page: Two friends at the lake")).assertIsDisplayed().performClick()
    assertThat(calls).containsExactly("clip")
  }
}
