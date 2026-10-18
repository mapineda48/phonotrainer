package com.mapineda48.phonotrainer.e2e.practice

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.ui.component.QuestionTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.practice.PracticeTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The Practice area on the managed device (work-plan WP-21, 2.4 and section 6 "GMD area tests"), on the global test set
 * with the real repositories: Practice opens on its tab and Saved over it, Back keeps the spec's order, the states the
 * baseline's repositories produce render (nothing to practice, nothing saved), and the happy path on a clip brought to
 * the phone through the real stack ([TestSeeder]: pairing, a sync pass, the indexer): a session of the real quiz,
 * "Save" reaching Saved, an activity recreated mid-session coming back on the same question (the process-death half is
 * the SavedStateHandle test of :feature:practice), "Continue", and a whole session to its results and accuracy.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class PracticeAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  /** A stuck test gives the one managed device of this computer back (work-plan 1.2). */
  @get:Rule(order = 2)
  val timeout: Timeout = Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var saved: SavedRepository

  private val phono = PhonoRobot(compose)
  private val practice = PracticeRobot(compose, phono)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  /** A clip with enough changes for a whole session (WP-12's synthetic analysis), on the phone through the real stack. */
  private fun seedAClip() {
    seeder.computer.clips = FixtureClips.synthetic(words = WORDS, phrases = PHRASES)
    runBlocking { seeder.seedLibrary("A walk in the park", video = false) }
  }

  @Test
  fun practiceOpensOnItsTabWithNothingToPracticeAndBackGoesHome() {
    phono.launch()
    practice.openTab()
    practice.waitForTag(PracticeTags.EMPTY)
    assertThat(practice.text(PracticeTags.EMPTY)).contains("Nothing to practice yet")
    phono.back()
    phono.assertShown(HomeKey)
    phono.assertNotShown(PracticeKey())
  }

  @Test
  fun savedOpensOverPracticeEmptyAndBackReturnsToPractice() {
    phono.launch()
    practice.openTab()
    phono.open(SavedKey)
    phono.assertShown(SavedKey)
    practice.waitForTag(PracticeTags.SAVED_EMPTY)
    assertThat(practice.text(PracticeTags.SAVED_EMPTY)).contains("Nothing saved yet")
    phono.back()
    phono.assertNotShown(SavedKey)
    phono.assertShown(PracticeKey())

    // "Go to Watch" from an empty Saved.
    phono.open(SavedKey)
    practice.waitForTag(PracticeTags.SAVED_EMPTY)
    compose.onNode(hasText("Go to Watch") and hasClickAction()).performClick()
    phono.assertShown(WatchKey())
  }

  @Test
  fun aSessionOnARealClipSavesAWordAndComesBackOnItsQuestionAfterARecreate() {
    seedAClip()
    phono.launch()
    practice.openTab()
    practice.start()
    assertThat(practice.position()).isEqualTo("Question 1 of 10")

    // Answer, then "Save": the word reaches Saved (F-17-AC4: "Save" is offered after the answer).
    practice.answer()
    practice.tap(QuestionTags.SAVE)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { practice.text(QuestionTags.SAVE).contains("Saved") }
    assertThat(runBlocking { saved.saved().first() }).hasSize(1)
    practice.next()
    assertThat(practice.position()).isEqualTo("Question 2 of 10")
    val question = practice.text(QuestionTags.PROMPT)

    // The activity is recreated: the same question, unanswered.
    phono.scenario.recreate()
    practice.waitForTag(QuestionTags.PROMPT)
    assertThat(practice.position()).isEqualTo("Question 2 of 10")
    assertThat(practice.text(QuestionTags.PROMPT)).isEqualTo(question)
    assertThat(practice.has(QuestionTags.LIVE)).isFalse()

    // Answered, then left: the start screen offers to continue at question 3.
    practice.answer()
    practice.tap(PracticeTags.LEAVE)
    practice.waitForTag(PracticeTags.RESUME)
    assertThat(practice.text(PracticeTags.START_CARD)).contains("Your last session stopped at question 3 of 10.")

    // The Saved tile leads to the saved word; Back returns to Practice.
    practice.tap(PracticeTags.LINK_SAVED)
    phono.assertShown(SavedKey)
    practice.waitForTag(PracticeTags.SAVED_WATCH)
    phono.back()
    phono.assertShown(PracticeKey())

    practice.tap(PracticeTags.RESUME)
    practice.waitForTag(PracticeTags.SESSION)
    assertThat(practice.position()).isEqualTo("Question 3 of 10")
  }

  @Test
  fun aWholeSessionEndsWithTheResultsAndTheAccuracyPerChange() {
    seedAClip()
    phono.launch()
    practice.openTab()
    practice.start()
    practice.finishSession()
    assertThat(practice.text(PracticeTags.SCORE)).matches("\\d+ of 10 right")
    practice.tap(PracticeTags.BACK_TO_START)
    practice.waitForTag(PracticeTags.PROGRESS)
    // Ten answers are counted per change, kept on this phone (spec F-17 item 5).
    assertThat(practice.text(PracticeTags.PROGRESS)).contains("Kept only on this phone.")
    assertThat(practice.has(PracticeTags.PROGRESS_NONE)).isFalse()
    assertThat(practice.has(PracticeTags.RESET)).isTrue()
  }

  private companion object {
    const val LIMIT_S = 180L
    const val WORDS = 300
    const val PHRASES = 60
  }
}
