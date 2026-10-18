package com.mapineda48.phonotrainer.e2e.learn

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.learn.LearnTags
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
 * The Learn area on the managed device (work-plan WP-20 Proof, 2.4): Learn, the phenomenon pages, the IPA chart and
 * "Words we use" open through the real shell and Back leaves them in the spec's order; with a clip put on the phone
 * through the real corpus ([LearnSeeder]) the Flapping card and page show the index's two units (D-48), an example
 * plays on the real span player and opens its lesson as a full page, the IPA sheet of [ɾ] lists the clip's words, a
 * recreated activity keeps the chart's selection, and "Words we use" searches; v1-core shows no Insights entry.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class LearnAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  /** A stuck test gives the one managed device of this computer back (work-plan 1.2). */
  @get:Rule(order = 2)
  val timeout: Timeout = Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: LearnSeeder

  @Inject lateinit var occurrences: OccurrenceRepository

  private val phono = PhonoRobot(compose)
  private val learn = LearnRobot(compose, phono)
  private val flapping = ChangeLabel("flapping")

  private fun start(seed: Boolean): JobId? {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    val id = if (seed) runBlocking { seeder.clip("${LearnSeeder.PREFIX}${System.nanoTime()}") } else null
    phono.launch()
    return id
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
    if (::seeder.isInitialized) seeder.clear()
  }

  @Test
  fun learnsPagesOpenAndBackLeavesThemInOrder() {
    start(seed = false)
    learn.openLearn()
    phono.assertShown(LearnKey)
    // v1-core: nothing binds InsightsAvailability, so there is no Insights entry (work-plan 4.4).
    assertThat(learn.exists(LearnTags.LINK_INSIGHTS)).isFalse()
    learn.tap(LearnTags.LINK_IPA)
    phono.assertShown(IpaChartKey())
    phono.back()
    phono.assertShown(LearnKey)
    learn.tap(LearnTags.LINK_GLOSSARY)
    phono.assertShown(GlossaryIndexKey)
    phono.back()
    phono.assertShown(LearnKey)
    learn.scrollTo(LearnTags.LIST, LearnTags.card(flapping))
    learn.tap(LearnTags.card(flapping))
    phono.assertShown(PhenomenonKey(flapping))
    phono.back()
    phono.assertShown(LearnKey)
    phono.assertNotShown(PhenomenonKey(flapping))
    phono.back()
    phono.assertShown(HomeKey)
  }

  @Test
  fun withNoClipEveryPageStillTeaches() {
    // Spec F-15 and F-16 "First run": all pages open with their text before pairing, and say there are no examples yet.
    start(seed = false)
    phono.open(PhenomenonKey(flapping))
    learn.waitForTag(LearnTags.PHENOMENON_HOW)
    learn.awaitText(LearnTags.PHENOMENON_LIST, LearnTags.PHENOMENON_TIMES, "0 times in your clips")
    learn.scrollTo(LearnTags.PHENOMENON_LIST, LearnTags.PHENOMENON_NO_EXAMPLES)
    phono.open(IpaChartKey("ɾ"))
    learn.waitForTag(LearnTags.IPA_SHEET_NO_EXAMPLES)
  }

  @Test
  fun aClipOnThePhoneGivesTheFlappingCardAndPageTheirTwoUnitsAndAPlayableExample() {
    val job = checkNotNull(start(seed = true))
    val count = runBlocking { occurrences.changeCounts().first { flapping in it }.getValue(flapping) }
    // D-48: the card counts shorts, the page occurrences; both are the index's own numbers.
    learn.openLearn()
    val shorts = learn.awaitCardCount(flapping)
    assertThat(shorts).isEqualTo(if (count.shorts == 1) "1 short in your clips" else "${count.shorts} shorts in your clips")
    learn.tap(LearnTags.card(flapping))
    phono.assertShown(PhenomenonKey(flapping))
    val times = if (count.occurrences == 1) "1 time in your clips" else "${count.occurrences} times in your clips"
    learn.awaitText(LearnTags.PHENOMENON_LIST, LearnTags.PHENOMENON_TIMES, times)
    // Clearest first: "water" (the rules-3 clip's flapping with the lowest cost).
    learn.awaitExample(LearnTags.PHENOMENON_LIST, 0, "water")
    learn.playExample(0)
    learn.awaitPlayLabel(0, "Stop “water”")
    learn.openExampleLesson(0)
    val lesson = LessonKey(job, 0, 4, LessonOrigin.LEARN, LessonPresentation.FULL)
    phono.assertShown(lesson)
    phono.back()
    phono.assertShown(PhenomenonKey(flapping))
    phono.assertNotShown(lesson)
  }

  @Test
  fun watchExamplesOpensTheChangesFeed() {
    // F-15-AC2 (the feed's mode and end card are the Watch area's).
    start(seed = true)
    phono.open(PhenomenonKey(flapping))
    learn.scrollTo(LearnTags.PHENOMENON_LIST, LearnTags.PHENOMENON_EXAMPLES)
    learn.tap(LearnTags.PHENOMENON_EXAMPLES)
    phono.assertShown(WatchKey(FeedStart.ChangeMode(flapping, onlyClip = null)))
  }

  @Test
  fun theTapsSheetListsTheClipsWordsAndARecreateKeepsIt() {
    start(seed = true)
    learn.openLearn()
    learn.tap(LearnTags.LINK_IPA)
    learn.scrollTo(LearnTags.IPA_LIST, LearnTags.symbol("ɾ"))
    learn.tap(LearnTags.symbol("ɾ"))
    learn.waitForTag(LearnTags.IPA_SHEET)
    assertThat(learn.text(LearnTags.IPA_SHEET_NAME)).isEqualTo("Alveolar tap")
    learn.waitForTag(LearnTags.example(0))
    // A recreated activity (rotation, a configuration change): the chart comes back with its sheet open.
    phono.scenario.recreate()
    learn.waitForTag(LearnTags.IPA_SHEET)
    assertThat(learn.text(LearnTags.IPA_SHEET_NAME)).isEqualTo("Alveolar tap")
    learn.tap(LearnTags.IPA_SHEET_CLOSE)
    learn.waitForNoTag(LearnTags.IPA_SHEET)
    // Nothing selected again: "Choose a symbol" is back at the top of the chart.
    learn.scrollTo(LearnTags.IPA_LIST, LearnTags.IPA_CHOOSE)
    learn.waitForTag(LearnTags.IPA_CHOOSE)
  }

  @Test
  fun wordsWeUseFindsSchwaAndSaysWhenNothingMatches() {
    // F-14-AC3, AC4.
    start(seed = false)
    phono.open(GlossaryIndexKey)
    learn.waitForTag(LearnTags.GLOSSARY_COUNT)
    val all = checkNotNull(learn.text(LearnTags.GLOSSARY_COUNT))
    learn.search("xyzzy")
    learn.waitForTag(LearnTags.GLOSSARY_NO_MATCH)
    assertThat(learn.text(LearnTags.GLOSSARY_COUNT)).startsWith("0 of ")
    learn.tap(LearnTags.GLOSSARY_CLEAR)
    learn.waitForNoTag(LearnTags.GLOSSARY_NO_MATCH)
    assertThat(learn.text(LearnTags.GLOSSARY_COUNT)).isEqualTo(all)
    learn.search("schwa")
    learn.waitForTag(LearnTags.glossaryRow("schwa"))
  }

  private companion object {
    const val LIMIT_S = 120L
  }
}
