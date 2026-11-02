package com.mapineda48.phonotrainer.e2e.library

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.testing.computer.Reachability
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.support.TestTime
import com.mapineda48.phonotrainer.feature.library.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * "Last synced" through the real stack (spec F-21-AC8; CR-WP08-1): the sync pass that brought a clip is the paired
 * computer's last sync, never older than the pairing, and when the computer is out of reach the Library's offline
 * banner says when that pass was. The library is seeded through [TestSeeder] (pairing, a sync pass, the indexer).
 */
@HiltAndroidTest
class LibraryLastSyncTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  /** A stuck test gives the one managed device of this computer back (work-plan 1.2). */
  @get:Rule(order = 2)
  val timeout: Timeout =
    Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var status: ComputerStatusRepository

  @Inject lateinit var time: TestTime

  @Inject lateinit var clock: Clock

  private val phono = PhonoRobot(compose)
  private val library = LibraryRobot(compose, phono)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun theSyncPassIsTheComputersLastSyncAndTheOfflineLibrarySaysWhenItRan() {
    assertThat(status.paired.value).isNull()
    seeder.computer.clips = FixtureClips.synthetic(words = WORDS, phrases = PHRASES)
    runBlocking { seeder.seedLibrary(TITLE, video = false) }
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { status.paired.value?.lastSync != null }
    val paired = checkNotNull(status.paired.value)
    val lastSync = checkNotNull(paired.lastSync)
    assertThat(lastSync).isAtLeast(paired.pairedAt)

    seeder.computer.reachability = Reachability.Unreachable
    runBlocking { status.checkNow() }
    compose.waitUntil(OFFLINE_TIMEOUT_MS) { status.status.value !is ComputerStatus.Connected }
    // Later the same day: the banner gives the pass's time of day, not "just now".
    time.advanceBy(LATER)
    val zone = clock.zone()
    assertWithMessage("the test's clock stays on the day of the pass")
      .that(time.now.atZone(zone).toLocalDate()).isEqualTo(lastSync.atZone(zone).toLocalDate())

    phono.launch()
    phono.assertShown(HomeKey)
    library.open()
    val at = library.words(R.string.library_time_today, CLOCK.format(lastSync.atZone(zone)))
    assertThat(library.offlineBanner()).containsExactly(library.words(R.string.library_offline, at))
  }

  private companion object {
    const val TITLE = "Ordering coffee"
    const val WORDS = 300
    const val PHRASES = 60
    const val OFFLINE_TIMEOUT_MS = 20_000L
    const val LIMIT_S = 120L
    val LATER: Duration = Duration.ofHours(2)

    /** The time of day as the Library writes it ("today at 14:05"). */
    val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
  }
}
