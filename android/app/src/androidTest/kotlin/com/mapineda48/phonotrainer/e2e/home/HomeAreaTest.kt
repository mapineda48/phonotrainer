package com.mapineda48.phonotrainer.e2e.home

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.watch.WatchRobot
import com.mapineda48.phonotrainer.feature.home.HomeTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The Home area on the managed device (work-plan WP-15 Proof, section 6 "GMD area tests"), on the global test set with
 * the repositories of this baseline: Home opens as the start destination, its controls reach their destinations and
 * Back follows architecture 16.2; a phone that never paired shows the first run; and on clips brought to the phone
 * through the real stack ([TestSeeder]: fake computer, pairing, a sync pass, the indexer) "Start" opens Watch at
 * today's set (F-03-AC1), Back from a shelf clip's feed finds Home at the same scroll position (F-03-AC8), and a chip
 * opens the change mode with as many shorts as it says (F-03-AC7).
 */
@HiltAndroidTest
class HomeAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var reference: ReferenceRepository

  private val phono = PhonoRobot(compose)
  private val home = HomeRobot(compose, phono)

  private fun start() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
    phono.assertShown(HomeKey)
    home.waitForHome()
  }

  /** Clips on the computer (the synthetic long analysis, so every change has shorts), brought to this phone. */
  private fun seeded(vararg titles: String) = runBlocking {
    seeder.computer.clips = FixtureClips.synthetic(words = WORDS, phrases = PHRASES)
    seeder.seedLibrary(*titles, video = false)
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun aPhoneThatNeverPairedShowsTheFirstRunAndItsWaysOut() {
    start()
    // F-03 States, first run: the status card, the teaching block and the set that starts with the first clip.
    home.waitForStatus("Not connected to a computer")
    home.waitForText("Swipe through it short by short.")
    home.waitForText("Today's set starts with your first clip.")

    home.tap(HomeTags.TEACH_CONNECT)
    phono.assertShown(ConnectKey(ConnectMode.FROM_HOME))
    phono.back()
    phono.assertShown(HomeKey)

    home.tap(HomeTags.LINK_LIBRARY)
    phono.assertShown(LibraryKey)
    phono.back()
    phono.assertShown(HomeKey)

    home.tap(HomeTags.SETTINGS)
    phono.assertShown(SettingsKey)
    phono.back()
    phono.assertShown(HomeKey)

    // From Home, Back leaves the app (architecture 16.2).
    phono.back()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !phono.isResumed() }
  }

  @Test
  fun startOpensWatchAtTodaysSetAndBackComesHome() {
    start()
    seeded("Ordering coffee")
    home.waitForStatus("Connected to")
    // F-03-AC1: with a clip on this phone, the Today's set card is the first study item and "Start" opens the Mix
    // at today's set.
    home.tap(HomeTags.TODAY_START)
    phono.assertShown(WatchKey())
    assertThat(home.top(Tab.WATCH)).isEqualTo(WatchKey(FeedStart.TodaySet))
    phono.back()
    phono.assertShown(HomeKey)
    home.waitForHome()
  }

  @Test
  fun backFromAShelfClipsFeedFindsHomeAtTheSameScrollPosition() {
    start()
    val id = seeded("At the station").single()
    // The shelf is below the fold: Home scrolls to it first.
    home.scrollToShelf(id)
    val before = home.scrollPosition()
    assertThat(before).isGreaterThan(0f)

    home.watchFromShelf(id)
    phono.assertShown(WatchKey())
    // F-03-AC8: the feed "In order" on the clip at its resume short.
    assertThat(home.top(Tab.WATCH)).isEqualTo(WatchKey(FeedStart.InOrder(id, firstSegment = null, fromStart = false)))
    phono.back()
    phono.assertShown(HomeKey)
    home.waitForHome()
    compose.waitForIdle()
    // Back finds Home where it was.
    assertThat(home.scrollPosition()).isEqualTo(before)
  }

  @Test
  fun aChipOpensTheChangeModeWithAsManyShortsAsItSays() {
    start()
    seeded("Ordering coffee")
    val labels = reference.reference.value.labels.keys.toList()
    val (label, count) = home.firstCountedChip(labels)
    home.tap(HomeTags.chip(label))
    phono.assertShown(WatchKey())
    assertThat(home.top(Tab.WATCH)).isEqualTo(WatchKey(FeedStart.ChangeMode(label, onlyClip = null)))
    // F-03-AC7: the feed plays exactly the shorts the chip counted (D-48).
    val watch = WatchRobot(compose, phono)
    watch.waitForPosition(1)
    assertThat(watch.total()).isEqualTo(count)
    phono.back()
    phono.assertShown(HomeKey)

    // A change with no shorts opens its Learn page instead.
    val none = labels.firstOrNull { home.chipCount(it) == 0 }
    if (none != null) {
      home.tap(HomeTags.chip(none))
      phono.assertShown(PhenomenonKey(none))
    }
  }

  private companion object {
    const val WORDS = 300
    const val PHRASES = 60
  }
}
