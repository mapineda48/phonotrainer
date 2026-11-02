package com.mapineda48.phonotrainer.e2e.lesson

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.lesson.LessonStep
import com.mapineda48.phonotrainer.feature.lesson.LessonTags
import com.mapineda48.phonotrainer.feature.lesson.R
import com.mapineda48.phonotrainer.feature.watch.WatchTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The lesson area on the managed device (work-plan WP-18 Proof, 2.4): the lesson opens through the real shell as a
 * sheet over the paused feed (the feed stays composed beneath) and as a full page, Back closes each the spec's way,
 * and on WP-12's rules-3 clip ("Did you get the water?", "I'm gonna go at 9.30.", "% uh": palatalization across a
 * boundary, flapping, a lexical contraction, a low-confidence word, a numeral and a bare symbol) brought to the phone
 * through the real stack ([TestSeeder]: pairing, a sync pass, the indexer) the lesson shows the real Compare rows,
 * Compact guidance shows titles only (F-10-AC3), "Next change" moves on (F-10-AC5), a recreated activity keeps the word
 * and step 3 (F-10-AC6; the process-death half is the SavedStateHandle test of :feature:lesson), and shadowing runs on
 * the real runner and stops.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class LessonAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  /** A stuck test gives the one managed device of this computer back (work-plan 1.2). */
  @get:Rule(order = 2)
  val timeout: Timeout = Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var clips: ClipRepository

  private val phono = PhonoRobot(compose)
  private val lesson = LessonRobot(compose, phono)

  private fun start(change: (UserSettings) -> UserSettings = { it }): JobId {
    hilt.inject()
    runBlocking { settings.update { change(it.copy(welcomeDone = true)) } }
    val id = runBlocking { seeder.seedAudioClip(TITLE, RULES_3) }
    phono.launch()
    return id
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun theLessonOpensAsASheetOverTheFeedAndBackReturnsToTheFeed() {
    val job = start()
    val key = lesson.openOverFeed(job, segment = 0, word = 4)
    lesson.waitForWord("water")
    assertThat(lesson.text(LessonTags.META)).isEqualTo("Word 5 of 12 · phrase 1 · 0:00.8–0:01.0")
    // The paused feed stays composed beneath the overlay.
    assertThat(compose.onAllNodes(hasTestTag(WatchTags.POSITION), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    // The pill names the clip by the title the computer gave it, which the real ClipRepository reads.
    assertThat(runBlocking { clips.clip(job).first()?.summary?.title }).isEqualTo(TITLE)
    val pill = lesson.string(R.string.lesson_paused, TITLE)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { lesson.text(LessonTags.PAUSED) == pill }
    phono.back()
    phono.assertNotShown(key)
    phono.assertShown(WatchKey())
  }

  /**
   * The sheet the feed opens over a playing short settles at half height and one Back closes it, on a longer clip put
   * on the phone while the app runs (the Watch area's order). Before the lesson filled the sheet's height in every
   * state, a lesson that loaded after its sheet had opened grew the sheet, which then settled fully expanded, where
   * Material's sheet answers Back by going down to half height instead of closing: WP-17's Watch area test failed so
   * twice. That depends on timing (this test passed on that code too); the deterministic guard is LessonScreenTest's
   * `inTheSheetEveryStateFillsTheSheetsHeightSoItsSizeNeverChangesWhileItLoads`.
   */
  @Test
  fun theSheetTheFeedOpensSettlesAtHalfHeightAndOneBackClosesIt() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
    val job = runBlocking { seeder.seedAudioClip(TITLE, LONG) }
    val anyLesson = LessonKey(job, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET)
    lesson.openFromThePlayingFeed(job)
    phono.assertShown(anyLesson)
    assertThat(lesson.settledSheetTop()).isWithin(HALF_TOLERANCE).of(HALF)
    lesson.waitForFeedPlaying(false)
    phono.back()
    phono.assertNotShown(anyLesson)
    // Again on the paused short, once the lesson is on screen.
    lesson.tapSlip()
    phono.assertShown(anyLesson)
    lesson.waitForTag(LessonTags.WORD)
    assertThat(lesson.settledSheetTop()).isWithin(HALF_TOLERANCE).of(HALF)
    phono.back()
    phono.assertNotShown(anyLesson)
    phono.assertShown(WatchKey())
  }

  @Test
  fun closeLeavesTheSheetLikeBack() {
    val job = start()
    val key = lesson.openOverFeed(job, segment = 0, word = 0)
    lesson.waitForWord("Did")
    lesson.tap(LessonTags.CLOSE)
    phono.assertNotShown(key)
    phono.assertShown(WatchKey())
  }

  @Test
  fun theFullLessonIsAPageAndBackLeavesIt() {
    val job = start()
    val key = lesson.openFull(job, segment = 1, word = 1)
    lesson.waitForWord("gonna")
    lesson.waitForNoTag(LessonTags.PAUSED)
    phono.back()
    phono.assertNotShown(key)
    phono.assertShown(HomeKey)
  }

  @Test
  fun compareShowsTheRealRowsAcrossTheBoundaryAndNextChangeMovesOn() {
    val job = start()
    lesson.openFull(job, segment = 0, word = 0)
    lesson.waitForWord("Did")
    // WP-12's rows through WP-10's repository: "did" + "you" (palatalization crosses the boundary).
    lesson.waitForTag(LessonTags.sort(0))
    compose.onNode(hasTestTag(LessonTags.sort(2)) and hasContentDescription("d changed to dʒ")).assertExists()
    lesson.waitForTag(LessonTags.sortGroup(next = true))
    lesson.waitForTag(LessonTags.GAP)
    // F-10-AC5: the next word with a change.
    lesson.tapNextChange()
    lesson.waitForWord("you")
    lesson.tapNextChange()
    lesson.waitForWord("get")
    lesson.tapNextChange()
    lesson.waitForWord("water")
  }

  @Test
  fun compactGuidanceShowsTheTitlesOnlyAndEachOpensOnTap() {
    val job = start { it.copy(lessonGuidance = LessonGuidance.COMPACT) }
    lesson.openOverFeed(job, segment = 0, word = 4)
    lesson.waitForWord("water")
    // F-10-AC3.
    LessonStep.entries.forEach { assertThat(lesson.isOpen(it)).isFalse() }
    lesson.tapStep(LessonStep.COMPARE)
    lesson.waitForTag(LessonTags.sort(2))
    compose.onNode(hasTestTag(LessonTags.sort(2)) and hasContentDescription("t changed to ɾ")).assertExists()
  }

  @Test
  fun aRecreatedActivityKeepsTheLessonOnItsWordAtStepThree() {
    val job = start { it.copy(lessonGuidance = LessonGuidance.COMPACT) }
    val key = lesson.openOverFeed(job, segment = 0, word = 0)
    lesson.waitForWord("Did")
    lesson.tapNextChange()
    lesson.waitForWord("you")
    lesson.tapStep(LessonStep.WHY)
    phono.scenario.recreate()
    // F-10-AC6 (the recreate half): the same lesson, the same word, step 3 open and the others closed.
    phono.assertShown(key)
    lesson.waitForWord("you")
    lesson.waitForTag(LessonTags.stepBody(LessonStep.WHY))
    assertThat(lesson.isOpen(LessonStep.COMPARE)).isFalse()
    assertThat(lesson.isOpen(LessonStep.PRACTICE)).isFalse()
  }

  @Test
  fun shadowingRunsOnTheRealRunnerAndStops() {
    val job = start()
    lesson.openFull(job, segment = 0, word = 4)
    lesson.waitForWord("water")
    lesson.startShadowing()
    // The runner's live line: "Round 1 of 4: listen at 0.75×", then the rest of the 8 phases.
    assertThat(lesson.text(ShadowingTags.LINE)).matches("Round [1-4] of 4: .+")
    compose.onNodeWithTag(ShadowingTags.PHASES).assertExists()
    lesson.stopShadowing()
    lesson.waitForNoTag(ShadowingTags.RUNNING)
  }

  @Test
  fun aWordWhoseClipIsNotOnThePhoneSaysSoAndCloses() {
    start()
    val key = LessonKey(JobId("job_not_on_this_phone"), 0, 0, LessonOrigin.SAVED, LessonPresentation.FULL)
    phono.open(key)
    phono.assertShown(key)
    lesson.waitForTag(LessonTags.UNAVAILABLE)
    assertThat(compose.onAllNodes(hasTestTag(LessonTags.UNAVAILABLE)).fetchSemanticsNodes()).isNotEmpty()
    phono.back()
    phono.assertShown(HomeKey)
  }

  private companion object {
    const val LIMIT_S = 120L
    const val TITLE = "Two friends at the lake"

    /** WP-12's rules-3 clip data; a silent mix as long as the clip is its audio. */
    val RULES_3 = FixtureClips.of(AnalysisFixtures.Variant.RULES_3)

    /**
     * A longer clip (WP-12's synthetic analysis, 300 words over 60 phrases, with pitch every 10 ms: the Watch area's
     * clip, whose first short has a spotlight), so its lesson loads after the sheet has opened.
     */
    val LONG = FixtureClips.synthetic(words = 300, phrases = 60)

    /** Half height (design 9.9): the sheet's top at half the screen, give or take its handle and the status bar. */
    const val HALF = 0.5f
    const val HALF_TOLERANCE = 0.1f
  }
}
