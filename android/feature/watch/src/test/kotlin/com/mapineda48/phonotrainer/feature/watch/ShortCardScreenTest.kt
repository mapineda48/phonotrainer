package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen tests of every card state (work-plan WP-17 Proof; spec F-06 and F-07; design 9.3 to 9.8) on the design's
 * 412 × 915 dp phone, each through the shared harness with its accessibility audit (font scale 2.0, light and dark,
 * and the full matrix where the layout changes with the font scale).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ShortCardScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose

  private fun feed(
    state: WatchUiState,
    position: Long = 44_900,
    muted: Boolean = false,
    actions: WatchActions = WatchActions(),
    surface: (Int) -> Unit = {},
  ): @androidx.compose.runtime.Composable () -> Unit = {
    WatchScreen(
      state = state,
      positionMs = { position },
      volumeMuted = muted,
      actions = actions,
      surface = { index, _, _ -> surface(index) },
    )
  }

  @Test
  fun theCardShowsTheSpotlightTheCaptionsAndTheClipLineAtEveryVariant() =
    screen.checkEach(ScreenVariant.MATRIX, feed(WatchPreviewData.ready())) { variant ->
      compose.onNodeWithTag(WatchTags.SLIP).assertIsDisplayed()
      compose.onNodeWithText("“water”", useUnmergedTree = true).assertExists()
      // The slip is one node for TalkBack: its words carry the IPA line ("to" for the arrow, design 9.3).
      compose.onNodeWithTag(WatchTags.SLIP_IPA, useUnmergedTree = true).assertExists()
      compose.onNode(hasTestTag(WatchTags.SLIP) and hasContentDescription("/ˈwɔtɚ/ to [ˈwɔɾɚ]", substring = true)).assertExists()
      compose.onNodeWithTag(WatchTags.word(4, 4)).assertExists()
      compose.onNodeWithText("Short 4 of 15 · 0:42–0:56").assertExists()
      if (variant.fontScale >= LARGE_TEXT) {
        // Design 9.6: the rail becomes the two-row bar, and the slip keeps its word and IPA only.
        compose.onNodeWithTag(WatchTags.ACTION_BAR).assertExists()
        assertThat(compose.onAllNodesWithTagCount(WatchTags.SLIP_CUE)).isEqualTo(0)
      } else {
        compose.onNodeWithTag(WatchTags.RAIL).assertExists()
        compose.onNodeWithTag(WatchTags.SLIP_CUE, useUnmergedTree = true).assertExists()
        compose.onNode(
          hasTestTag(WatchTags.SLIP) and
            hasContentDescription("A t or d between vowels becomes one very quick tap of the tongue.", substring = true),
        ).assertExists()
        compose.onNodeWithTag(WatchTags.PLAY_WORD).assertExists()
        compose.onNodeWithTag(WatchTags.SLOWLY).assertExists()
      }
      listOf(WatchTags.REPLAY, WatchTags.SPEED, WatchTags.LOOP, WatchTags.SHADOW, WatchTags.SAVE, WatchTags.MORE)
        .forEach { compose.onNodeWithTag(it).assertExists() }
    }

  @Test
  fun onTheDesignPhoneTheWholeSlipAndTheCaptionsAreInView() {
    screen.setContent(content = feed(WatchPreviewData.ready()))
    compose.onNodeWithTag(WatchTags.word(4, 0)).assertIsDisplayed()
    compose.onNodeWithTag(WatchTags.SLIP_CUE, useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithTag(WatchTags.PLAY_WORD).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w412dp-h760dp-xxhdpi")
  fun onAShorterScreenTheSlipCompactsSoTheCaptionsStayInView() {
    // A 915 dp phone under the shell's bars, with the first coach mark up: the whole slip would leave the captions no
    // room, so it keeps its chips, word and IPA (design 9.3), and the hint line waits under the phrase, in the
    // captions' own area, instead of pushing it out (F-07 item 4: the phrase is what the short is for).
    screen.setContent(content = feed(WatchPreviewData.ready().copy(coachMark = WatchCoachMark.TAP_WORD)))
    compose.onNodeWithTag(WatchTags.word(4, 0)).assertIsDisplayed()
    compose.onNodeWithTag(WatchTags.SLIP).assertIsDisplayed()
    compose.onNodeWithTag(WatchTags.SLIP_IPA, useUnmergedTree = true).assertIsDisplayed()
    assertThat(compose.onAllNodesWithTagCount(WatchTags.SLIP_CUE)).isEqualTo(0)
    assertThat(compose.onAllNodesWithTagCount(WatchTags.PLAY_WORD)).isEqualTo(0)
    // The hint is one scroll away, still dismissable.
    compose.onNodeWithTag(WatchTags.COACH).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(com.mapineda48.phonotrainer.core.ui.UiTags.COACH_GOT_IT).assertExists()
  }

  @Test
  fun listenFirstHidesTheWordsBehindOneLineThatRevealsNoCount() {
    // F-07-AC1: the words are hidden behind one line, the spotlight is visible.
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready().copy(settings = UserSettings(listenFirst = true)))) {
      compose.onNodeWithTag(WatchTags.LISTEN_FIRST, useUnmergedTree = true).assertExists()
      compose.onNodeWithText("Listen first: the words appear after it plays", useUnmergedTree = true).assertExists()
      compose.onNodeWithTag(WatchTags.SLIP).assertExists()
      assertThat(compose.onAllNodesWithTagCount(WatchTags.word(4, 0))).isEqualTo(0)
      assertThat(compose.onAllNodesWithTagCount(WatchTags.CAPTION_CURRENT)).isEqualTo(0)
    }
  }

  @Test
  fun aRevealedPhraseAndTheDefaultShowTheWordsFromTheStart() {
    screen.setContent(content = feed(WatchPreviewData.ready().copy(settings = UserSettings(listenFirst = true), revealed = setOf(4))))
    compose.onNodeWithTag(WatchTags.word(4, 4)).assertExists()
    assertThat(compose.onAllNodesWithTagCount(WatchTags.LISTEN_FIRST)).isEqualTo(0)
  }

  @Test
  fun aShortWithoutASpotlightSaysListenToTheRhythm() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready(content = WatchPreviewData.content(slip = null)))) {
      compose.onNodeWithText("No labelled change here: listen to the rhythm.").assertExists()
      assertThat(compose.onAllNodesWithTagCount(WatchTags.SLIP)).isEqualTo(0)
    }

  @Test
  fun aContractionShowsItsReducedAndFullForm() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready(), position = 47_800)) {
      compose.onNodeWithText("gonna ← going to", useUnmergedTree = true).assertExists()
    }

  @Test
  fun aCrossingSpotlightNamesBothWords() =
    screen.checkEach(
      ScreenVariant.REQUIRED,
      feed(WatchPreviewData.ready(content = WatchPreviewData.content(slip = WatchPreviewData.crossingSlip()))),
    ) {
      compose.onNodeWithText("“did” + “you”", useUnmergedTree = true).assertExists()
    }

  @Test
  fun offlineTheHeaderSaysOnThisPhone() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready().copy(offline = true))) {
      compose.onNodeWithTag(WatchTags.ON_THIS_PHONE).assertIsDisplayed()
      compose.onNodeWithText("On this phone").assertExists()
    }

  @Test
  fun aDecodeFailurePlaysTheAudioAndSaysSo() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready().copy(playback = IdlePlayback.copy(error = AppError.Media.VideoDecode)))) {
      compose.onNodeWithText("This video can't play on this phone. Listening still works.", useUnmergedTree = true).assertExists()
    }

  @Test
  fun aCorruptOrMissingFileSaysTheShortCouldNotBePlayed() {
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready().copy(failed = setOf(WatchPreviewData.job)))) {
      compose.onNodeWithText("This short couldn't be played.", useUnmergedTree = true).assertExists()
    }
  }

  @Test
  fun missingAudioFromThePlayerSaysTheSame() {
    screen.setContent(content = feed(WatchPreviewData.ready().copy(playback = IdlePlayback.copy(error = AppError.Media.AudioMissing))))
    compose.onNodeWithTag(WatchTags.ERROR, useUnmergedTree = true).assertExists()
  }

  @Test
  fun volumeAtZeroAsksToTurnItUp() =
    screen.checkEach(ScreenVariant.REQUIRED, feed(WatchPreviewData.ready(), muted = true)) {
      compose.onNodeWithText("Turn the volume up: this is all about sound.").assertExists()
    }

  @Test
  fun eachTrackFallbackHasItsLineAndNoneHasNone() {
    // Spec D-47, design 9.8.
    val words = mapOf(
      TrackFallback.DIALOGUE_NOT_ON_PHONE to "Playing the original mix: the dialogue track isn't on this phone yet.",
      TrackFallback.MIX_NOT_ON_PHONE to "Playing the dialogue only: the original mix isn't on this phone yet.",
      TrackFallback.NO_DIALOGUE_TRACK to "Playing the original mix: this clip has no dialogue-only track.",
    )
    val fallback = androidx.compose.runtime.mutableStateOf<TrackFallback?>(null)
    screen.setContent(ScreenVariant(fontScale = 2f)) {
      WatchScreen(
        state = WatchPreviewData.ready().copy(playback = IdlePlayback.copy(trackFallback = fallback.value)),
        positionMs = { 44_900 },
        volumeMuted = false,
        actions = WatchActions(),
      )
    }
    assertThat(compose.onAllNodesWithTagCount(WatchTags.TRACK_LINE)).isEqualTo(0)
    for ((value, line) in words) {
      fallback.value = value
      compose.waitForIdle()
      compose.onNodeWithTag(WatchTags.TRACK_LINE, useUnmergedTree = true).assertExists()
      compose.onNodeWithText(line, useUnmergedTree = true).assertExists()
      screen.audit()
    }
  }

  @Test
  fun anAudioClipShowsTheDrumAndNoVideoFrame() {
    // F-06-AC6: "Audio only" is visible and no empty video frame is shown.
    val surfaces = mutableListOf<Int>()
    val state = WatchPreviewData.ready(WatchPreviewData.shortPage(jobId = WatchPreviewData.audioJob, video = VideoAvailability.AudioOnlyClip))
    screen.checkEach(ScreenVariant.REQUIRED, feed(state, surface = { surfaces += it })) {
      compose.onNodeWithTag(WatchTags.AUDIO_ONLY, useUnmergedTree = true).assertIsDisplayed()
      compose.onNodeWithText("Audio only", useUnmergedTree = true).assertExists()
      compose.onNodeWithText("Pitch of this phrase", useUnmergedTree = true).assertExists()
    }
    assertThat(surfaces).isEmpty()
  }

  @Test
  fun aPhraseWithoutPitchSaysSoOnTheDrum() {
    val page = WatchPreviewData.shortPage(jobId = WatchPreviewData.audioJob, video = VideoAvailability.AudioOnlyClip)
    screen.setContent(content = feed(WatchPreviewData.ready(page, WatchPreviewData.content(WatchPreviewData.audioJob, measurablePitch = false))))
    compose.onNodeWithText("No measurable pitch here", useUnmergedTree = true).assertExists()
  }

  @Test
  fun aVideoNotOnThePhonePlaysTheAudioUnderItsPoster() {
    // F-06-AC7.
    val state = WatchPreviewData.ready(WatchPreviewData.shortPage(video = VideoAvailability.NotOnThisPhone))
    screen.checkEach(ScreenVariant.REQUIRED, feed(state)) {
      compose.onNodeWithText("Video not on this phone · playing the audio", useUnmergedTree = true).assertExists()
    }
  }

  @Test
  fun aVideoTheComputerCannotProvideSaysSo() {
    val state = WatchPreviewData.ready(WatchPreviewData.shortPage(video = VideoAvailability.NotAvailableFromComputer))
    screen.setContent(content = feed(state))
    compose.onNodeWithText("Video not available from your computer", useUnmergedTree = true).assertExists()
  }

  @Test
  fun aVideoShortDrawsTheSurfaceOfItsPageAndThePosterUntilTheFirstFrame() {
    val surfaces = mutableListOf<Int>()
    screen.setContent(content = feed(WatchPreviewData.ready(), surface = { surfaces += it }))
    assertThat(surfaces).contains(0)
  }

  @Test
  fun aPortraitVideoKeepsTheCaptionsOnTheirSlip() =
    screen.checkEach(ScreenVariant.MATRIX, feed(WatchPreviewData.ready(WatchPreviewData.shortPage(width = 720, height = 1280)))) {
      compose.onNodeWithTag(WatchTags.SLIP).assertExists()
      compose.onNodeWithTag(WatchTags.word(4, 4)).assertExists()
    }

  @Test
  fun withTalkBackTheCardIsOneSummaryWithTheCustomActions() {
    // F-07-AC11 (design 9.8): the summary and Play, Next short, Previous short, Replay phrase, Slower, Open lesson, Save.
    val summary = "Short 4 of 15, Two friends at the lake. Listen for flapping in “water”. 14 seconds."
    screen.checkEach(ScreenVariant.REQUIRED.map { it.copy(talkBackOn = true) }, feed(WatchPreviewData.ready())) {
      val node = compose.onNode(hasContentDescription(summary)).fetchSemanticsNode()
      val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
      assertThat(actions).containsExactly("Play", "Next short", "Previous short", "Replay phrase", "Slower", "Open lesson", "Save")
        .inOrder()
    }
  }

  @Test
  fun theCustomActionsReachTheFeed() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(
      card = CardCallbacks(
        onTogglePlay = { calls += "play" },
        onReplay = { calls += "replay" },
        onSlower = { calls += "slower" },
        onWord = { calls += "lesson:${it.segment}:${it.word}" },
        onSave = { calls += "save" },
      ),
    )
    screen.setContent(content = feed(WatchPreviewData.ready(), actions = actions))
    val node = compose.onNode(hasContentDescription("Short 4 of 15", substring = true)).fetchSemanticsNode()
    node.config[SemanticsActions.CustomActions].forEach { action ->
      if (action.label !in setOf("Next short", "Previous short")) {
        compose.runOnIdle { action.action() }
      }
    }
    assertThat(calls).containsExactly("play", "replay", "slower", "lesson:4:4", "save").inOrder()
  }

  @Test
  fun aSavedShortReadsSavedAndOffersToRemoveIt() {
    val page = WatchPreviewData.shortPage()
    screen.setContent(ScreenVariant(talkBackOn = true), feed(WatchPreviewData.ready(page).copy(saved = setOf(page.id))))
    val node = compose.onNode(hasContentDescription("Short 4 of 15", substring = true)).fetchSemanticsNode()
    assertThat(node.config[SemanticsActions.CustomActions].map { it.label }).contains("Remove from saved")
    compose.onNodeWithTag(WatchTags.SAVE).assertExists()
    assertThat(compose.onNodeWithTag(WatchTags.SAVE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
      .isEqualTo("Saved")
  }

  @Test
  fun theRailAndTheProgressAskTheFeed() {
    val calls = mutableListOf<String>()
    val actions = WatchActions(
      card = CardCallbacks(
        onTogglePlay = { calls += "toggle" },
        onReplay = { calls += "replay" },
        onSpeed = { calls += "speed" },
        onLoop = { calls += "loop" },
        onSave = { calls += "save" },
      ),
    )
    screen.setContent(content = feed(WatchPreviewData.ready(), actions = actions))
    listOf(WatchTags.PLAY_PAUSE, WatchTags.REPLAY, WatchTags.SPEED, WatchTags.LOOP, WatchTags.SAVE).forEach {
      compose.onNodeWithTag(it).performClick()
    }
    assertThat(calls).containsExactly("toggle", "replay", "speed", "loop", "save").inOrder()
  }

  @Test
  fun theSpeedAndLoopReadTheirValue() {
    // F-07-AC3: the control reads "0.5×".
    screen.setContent(
      content = feed(WatchPreviewData.ready().copy(playback = IdlePlayback.copy(speed = 0.5f, loop = com.mapineda48.phonotrainer.core.domain.model.LoopMode.PHRASE))),
    )
    compose.onNode(hasTestTag(WatchTags.SPEED) and hasContentDescription("Speed: 0.5×")).assertExists()
    compose.onNode(hasTestTag(WatchTags.LOOP) and hasContentDescription("Loop: Phrase")).assertExists()
  }

  @Test
  fun tappingAWordOpensItsLessonAndALongPressLoopsItsPhrase() {
    // F-07-AC6 (screen side): a word asks for its lesson; the long press loops the phrase (F-07 gestures).
    val words = mutableListOf<WordRef>()
    val loops = mutableListOf<Int>()
    val actions = WatchActions(card = CardCallbacks(onWord = { words += it }, onLoopPhrase = { loops += it }))
    screen.setContent(content = feed(WatchPreviewData.ready(), actions = actions))
    compose.onNodeWithTag(WatchTags.word(4, 4)).performClick()
    compose.onNodeWithTag(WatchTags.word(4, 2)).performTouchInput { longClick() }
    assertThat(words).containsExactly(WordRef(WatchPreviewData.job, 4, 4))
    assertThat(loops).containsExactly(4)
  }

  @Test
  fun aWordReadsItsChangesAdviceAndRole() {
    screen.setContent(content = feed(WatchPreviewData.ready()))
    compose.onNode(hasTestTag(WatchTags.word(4, 4)) and hasContentDescription("water, flapping, safe to produce, listen for, stressed"))
      .assertExists()
    compose.onNode(hasTestTag(WatchTags.word(4, 3)) and hasContentDescription("the, low confidence")).assertExists()
  }

  @Test
  fun tappingTheMediaPlaysAndPressAndHoldSlowsDown() {
    // F-07-AC4 (screen side): the hold asks for 0.5× while held and releases it.
    val calls = mutableListOf<String>()
    val actions = WatchActions(card = CardCallbacks(onTogglePlay = { calls += "toggle" }, onHold = { calls += "hold:$it" }))
    screen.setContent(content = feed(WatchPreviewData.ready(), actions = actions))
    compose.onNodeWithTag(WatchTags.MEDIA).performClick()
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(400)
    compose.onNodeWithTag(WatchTags.MEDIA).performTouchInput { longClick(durationMillis = 1_000) }
    compose.waitForIdle()
    assertThat(calls).containsExactly("toggle", "hold:true", "hold:false").inOrder()
  }

  @Test
  fun holdingShowsTheSlowChip() {
    screen.setContent(content = feed(WatchPreviewData.ready().copy(playback = IdlePlayback.copy(holdSlow = true))))
    compose.onNodeWithTag(WatchTags.HOLD_CHIP, useUnmergedTree = true).assertExists()
  }

  @Test
  fun theCoachMarkSitsUnderTheCaptionsAndGoesWithGotIt() {
    val dismissed = mutableListOf<WatchCoachMark>()
    screen.setContent(
      content = feed(
        WatchPreviewData.ready().copy(coachMark = WatchCoachMark.TAP_WORD),
        actions = WatchActions(card = CardCallbacks(onDismissCoach = { dismissed += it })),
      ),
    )
    compose.onNodeWithText("Tap any underlined word to see how it was really said.").assertExists()
    compose.onNodeWithTag(com.mapineda48.phonotrainer.core.ui.UiTags.COACH_GOT_IT).performClick()
    assertThat(dismissed).containsExactly(WatchCoachMark.TAP_WORD)
    screen.audit()
  }

  @Test
  fun captionsOffCollapseToOneLineWithShow() {
    val modes = mutableListOf<String>()
    screen.checkEach(
      ScreenVariant.REQUIRED,
      feed(
        WatchPreviewData.ready().copy(settings = UserSettings(captions = CaptionMode.OFF)),
        actions = WatchActions(card = CardCallbacks(onShowCaptions = { modes += "show" })),
      ),
    ) {
      compose.onNodeWithText("Captions are off").assertExists()
      assertThat(compose.onAllNodesWithTagCount(WatchTags.word(4, 4))).isEqualTo(0)
    }
    compose.onNodeWithTag(WatchTags.CAPTIONS_SHOW).performClick()
    assertThat(modes).containsExactly("show")
  }

  @Test
  fun ipaModeShowsWhatWasSaidUnderEachWordAndTheDictionaryFormOfMarkedOnes() {
    screen.setContent(content = feed(WatchPreviewData.ready().copy(settings = UserSettings(captions = CaptionMode.IPA))))
    compose.onNodeWithText("[ˈwɔɾɚ]", useUnmergedTree = true).assertExists()
    compose.onNodeWithText("/ˈwɔtɚ/", useUnmergedTree = true).assertExists()
  }

  @Test
  fun aLongPhraseInIpaAtTheLargestFontKeepsTheWordBeingSaidInView() {
    // F-07-AC9: a 12 s phrase at font scale 2.0 in IPA mode: the word being said is always visible.
    val long = longPhraseContent(words = 36)
    val page = WatchPreviewData.shortPage()
    val state = WatchPreviewData.ready(page, long).copy(settings = UserSettings(captions = CaptionMode.IPA))
    val position = androidx.compose.runtime.mutableLongStateOf(long.phrases[0].words[2].startMs)
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    screen.setContent(ScreenVariant(fontScale = 2f)) {
      WatchScreen(state = state.copy(seekTick = tick.intValue), positionMs = { position.longValue }, volumeMuted = false, actions = WatchActions())
    }
    for (word in listOf(2, 14, 26, 35)) {
      position.longValue = long.phrases[0].words[word].startMs + 10
      tick.intValue++
      compose.waitForIdle()
      compose.onNodeWithTag(WatchTags.word(4, word)).assertIsDisplayed()
    }
    WatchAudit.assertClean(compose, compose.density)
  }

  private fun longPhraseContent(words: Int): ShortContent {
    val base = WatchPreviewData.content()
    val start = 42_250L
    val step = 330L
    val list = (0 until words).map { i ->
      val family = listOf(Family.TD, null, Family.REDUCTION, null, Family.BOUNDARY)[i % 5]
      CaptionWord(
        ref = WordRef(WatchPreviewData.job, 4, i),
        text = listOf("water", "and", "the", "little", "boat")[i % 5],
        startMs = start + i * step,
        endMs = start + i * step + 280,
        family = family,
        changes = if (family == null) emptyList() else listOf("flapping"),
        advice = null,
        lowConfidence = false,
        stressed = false,
        linksToNext = false,
        dictIpa = "ˈwɔtɚ",
        realizedIpa = "ˈwɔɾɚ",
        contraction = null,
        spotlit = false,
      )
    }
    val phrase = CaptionPhrase(4, start, start + words * step, list, badge = null)
    return base.copy(phrases = listOf(phrase), spotlight = null, ticks = emptyList(), pin = null)
  }
}

/** How many nodes carry [tag] in the unmerged tree. */
internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
  onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
