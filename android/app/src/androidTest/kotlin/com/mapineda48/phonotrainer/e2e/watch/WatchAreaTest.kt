package com.mapineda48.phonotrainer.e2e.watch

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.watch.WatchTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The Watch area on the managed device (work-plan WP-17 Proof, 2.4): the destination opens through the real shell
 * with the baseline's repositories, Back goes Home, and on WP-12's synthetic clips brought to the phone through the
 * real stack ([TestSeeder]: pairing, a sync pass, the indexer) the real player pool plays them: swipe, the buttons and
 * TalkBack's actions move one short; reduced motion makes the move instant (F-06-AC9); a word pauses the short and
 * opens the lesson, and closing it comes back paused at the same time (F-07-AC6); the end card stops the feed; with
 * every short seen, the Mix's "Replay the mix" plays a set of the seen shorts (F-06 item 9); a tab switch keeps the
 * position (F-06-AC12); a dialogue track arriving during the feed (the computer finishes it, a sync pass brings it)
 * plays from the next short, with the line gone (F-06-AC13).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class WatchAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @get:Rule(order = 2)
  val guard = HangGuard { if (::log.isInitialized) log else PhonoLog.NONE }

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var log: PhonoLog

  @Inject lateinit var shorts: ShortRepository

  @Inject lateinit var progress: ProgressRepository

  private val phono = PhonoRobot(compose)
  private val watch = WatchRobot(compose, phono)

  /** Launches the app once the Welcome is done; [before] runs first, on the injected repositories, before any screen. */
  private fun start(before: suspend () -> Unit = {}, change: (UserSettings) -> UserSettings = { it }) {
    hilt.inject()
    runBlocking {
      before()
      settings.update { change(it.copy(welcomeDone = true)) }
    }
    phono.launch()
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  /**
   * WP-12's synthetic clip of [phrases] phrases (five words each, dialogue separated) with a silent mix as long as the
   * clip and no dialogue track yet, on the computer and then on this phone; its id.
   */
  private suspend fun seed(name: String, phrases: Int = PHRASES): JobId {
    val clipData = FixtureClips.synthetic(words = phrases * WORDS_PER_PHRASE, phrases = phrases)
    return seeder.seedAudioClip("Synthetic clip $name", clipData)
  }

  @Test
  fun theWatchTabOpensItsFeedAndBackGoesHome() {
    start()
    phono.tab(Tab.WATCH)
    phono.assertShown(WatchKey())
    // Nothing on the phone and never paired: the first-run state with its ways out (F-06 States).
    watch.waitForTag(WatchTags.EMPTY)
    watch.waitForText("Your feed fills up with your clips")
    phono.back()
    phono.assertShown(HomeKey)
  }

  @Test
  fun swipeTheButtonsAndTheCardActionsMoveOneShortOnTheRealPlayer() {
    start()
    val id = runBlocking { seed("moves") }
    watch.openInOrder(id)
    assertThat(watch.total()).isGreaterThan(3)
    // The pool plays the short when it settles (F-07 item 10).
    watch.waitForPlaying(true)
    watch.swipeToNext()
    watch.tapNext()
    watch.cardAction("Previous short", -1)
    watch.cardAction("Next short", +1)
    watch.tapPrevious()
    assertThat(watch.current()).isEqualTo(2)
    watch.waitForPlaying(true)
  }

  @Test
  fun withReducedMotionTheMoveIsInstant() {
    start { it.copy(motion = MotionChoice.REDUCE) }
    val id = runBlocking { seed("motion") }
    watch.openInOrder(id)
    compose.waitForIdle()
    compose.mainClock.autoAdvance = false
    try {
      compose.onNodeWithTag(WatchTags.NEXT).performClick()
      // F-06-AC9: a handful of frames (no 280 ms slide) and the next short is the current one.
      repeat(INSTANT_FRAMES) { compose.mainClock.advanceTimeByFrame() }
      assertThat(watch.position()).startsWith("2 /")
    } finally {
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun aWordPausesTheShortOpensTheLessonAndClosingComesBackPausedAtTheSameTime() {
    start()
    val id = runBlocking { seed("lesson") }
    watch.openInOrder(id)
    watch.waitForPlaying(true)
    watch.dismissCoachMarks()
    // While it plays: the spotlight's word (the slip stays put; caption words come and go with their phrase).
    watch.tapSlip()
    val anyLesson = LessonKey(id, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET)
    phono.assertShown(anyLesson)
    watch.waitForPlaying(false)
    val at = progressWords()
    phono.back()
    phono.assertNotShown(anyLesson)
    // F-07-AC6: the same short, paused, at the same time.
    watch.waitForPlaying(false)
    assertThat(watch.current()).isEqualTo(1)
    assertThat(progressWords()).isEqualTo(at)
    // A caption word opens its own lesson, and closing it again leaves the short paused where it was.
    watch.tapCurrentWord()
    phono.assertShown(anyLesson)
    phono.back()
    phono.assertNotShown(anyLesson)
    watch.waitForPlaying(false)
    assertThat(progressWords()).isEqualTo(at)
  }

  @Test
  fun theEndCardStopsTheFeed() {
    start()
    val id = runBlocking { seed("end", phrases = SHORT_CLIP_PHRASES) }
    watch.openInOrder(id)
    val total = watch.total()
    repeat(total - 1) { watch.tapNext() }
    compose.onNodeWithTag(WatchTags.NEXT).performClick()
    watch.waitForTag(WatchTags.END)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { watch.position() == "Done" }
    // Nothing loads past an end card (F-06 item 9): no next, and a swipe stays on it.
    compose.onNodeWithTag(WatchTags.NEXT).assertIsNotEnabled()
    compose.onNodeWithTag(WatchTags.PAGER).performTouchInput { swipeUp() }
    compose.waitForIdle()
    assertThat(watch.position()).isEqualTo("Done")
    watch.waitForTag(WatchTags.END)
  }

  /**
   * F-06 item 9 and F-08 item 7 (CR-WP14-1): with every short seen today the Mix has no set to build and shows the
   * "Mix with nothing left" end card, and its "Replay the mix" plays a set of the seen shorts.
   */
  @Test
  fun withEveryShortSeenReplayTheMixPlaysASetOfTheSeenShorts() {
    var withSpotlight = 0
    start(before = {
      val id = seed("replay", phrases = SHORT_CLIP_PHRASES)
      val all = shorts.shorts(id).first()
      all.forEach { short ->
        val length = (short.playEndMs - short.playStartMs).coerceAtLeast(1)
        progress.recordPlayed(short.id, playedMs = length, lengthMs = length)
      }
      withSpotlight = all.count { it.spotlight != null }
    })
    assertThat(withSpotlight).isGreaterThan(0)
    phono.open(WatchKey(FeedStart.TodaySet))
    watch.waitForTag(WatchTags.END_REPLAY_MIX)

    compose.onNodeWithTag(WatchTags.END_REPLAY_MIX).performScrollTo().performClick()

    // A set of seen shorts (8 when there are that many), from its first short, with the end card gone.
    assertThat(watch.waitForSetPosition(1)).isEqualTo(minOf(SET_SHORTS, withSpotlight))
    watch.waitForNoTag(WatchTags.END_REPLAY_MIX)
  }

  @Test
  fun aTabSwitchAndReturnKeepsTheFeedPosition() {
    start()
    val id = runBlocking { seed("tabs") }
    watch.openInOrder(id)
    watch.tapNext()
    watch.tapNext()
    phono.tab(Tab.LEARN)
    phono.assertShown(LearnKey)
    phono.tab(Tab.WATCH)
    // The Watch ViewModel outlives the tab switch (architecture 16.2): same mode, same short (F-06-AC12).
    watch.waitForPosition(3)
  }

  @Test
  fun aDialogueTrackArrivingDuringTheFeedPlaysFromTheNextShortWithTheLineGone() {
    start { it.copy(track = Track.DIALOGUE) }
    val id = runBlocking { seed("dialogue") }
    watch.openInOrder(id)
    watch.waitForTag(WatchTags.TRACK_LINE)
    watch.waitForText("Playing the original mix: the dialogue track isn't on this phone yet.")
    runBlocking { seeder.addDialogue(id) }
    // The feed checks its neighbours' media about once a second; give it two checks before the swipe.
    val until = SystemClock.uptimeMillis() + REFRESH_WAIT_MS
    compose.waitUntil(REFRESH_WAIT_MS + PhonoRobot.TIMEOUT_MS) { SystemClock.uptimeMillis() >= until }
    watch.tapNext()
    watch.waitForPlaying(true)
    // F-06-AC13: the next short of that clip plays the dialogue, and the line is gone.
    watch.waitForNoTag(WatchTags.TRACK_LINE)
    assertThat(compose.onAllNodes(hasText("dialogue track", substring = true), useUnmergedTree = true).fetchSemanticsNodes())
      .isEmpty()
  }

  /**
   * The progress bar's words of the short on screen ("Position 0:03 of 0:09"). The pager may keep the next page
   * composed (prefetched) with its own progress bar, so the lookup stays inside the current page, as WatchRobot's do.
   */
  private fun progressWords(): String? =
    compose.onNode(
      hasTestTag(WatchTags.PROGRESS) and hasAnyAncestor(hasTestTag(WatchTags.page(watch.current() - 1))),
      useUnmergedTree = true,
    ).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.StateDescription)

  private companion object {
    /** The synthetic clips' size: 60 phrases of five words, about two and a half minutes. */
    const val PHRASES = 60
    const val WORDS_PER_PHRASE = 5

    /** A shorter clip, for tests that walk every short of it. */
    const val SHORT_CLIP_PHRASES = 24

    const val INSTANT_FRAMES = 4
    const val REFRESH_WAIT_MS = 2_500L

    /** Shorts in a set (spec F-08 item 1). */
    const val SET_SHORTS = 8
  }
}
