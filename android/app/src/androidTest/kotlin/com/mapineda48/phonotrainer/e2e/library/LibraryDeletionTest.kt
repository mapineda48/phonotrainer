package com.mapineda48.phonotrainer.e2e.library

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.library.LibraryTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A clip deleted on the computer while its Clip page is open (spec F-05 States, 7.9, QT-10; CR-WP08-4), on the real
 * stack: two clips come to this phone through [TestSeeder] (the fake computer, pairing, a sync pass), the computer
 * deletes one while its page shows, and the page says so with its banner, keeps what can be read until the learner
 * leaves, and offers nothing that plays the clip or needs the computer. Back in the Library the clip is gone from this
 * phone with one notice, and the other clip stays.
 */
@HiltAndroidTest
class LibraryDeletionTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var clips: ClipRepository

  @Inject lateinit var sync: SyncController

  private val phono = PhonoRobot(compose)
  private val library = LibraryRobot(compose, phono)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun aClipDeletedOnTheComputerWhileItsPageIsOpenStaysUntilTheLearnerLeaves() {
    val (deleted, other) = runBlocking { seeder.seedLibrary(DELETED, OTHER, video = false) }
    library.open()
    library.awaitTotal(2)
    library.scrollTo(deleted)
    library.openClip(deleted)
    phono.assertShown(ClipKey(deleted))
    library.awaitClip(DELETED)
    assertThat(library.hasClipDeletedBanner()).isFalse()
    library.assertClipWatchable()
    library.clipTab(ClipTab.SUMMARY)
    assertThat(library.onPhoneOffers(LibraryTags.REMOVE_FROM_PHONE)).isTrue()

    seeder.computer.deleteJob(deleted)
    awaitMarkedDeleted(deleted)

    // F-05 States: the banner, there while the learner still reads "On this phone", and the clip stays until the
    // learner leaves its page (QT-10).
    library.awaitClipDeletedBanner()
    library.clipToTop()
    library.awaitClip(DELETED)
    // The feed has already left its shorts out (spec F-06 States), so nothing on the page opens it.
    assertThat(library.offersWatch()).isFalse()
    // Nothing that needs the computer: "Remove from this phone?" would say the clip stays there.
    assertThat(library.onPhoneOffers(LibraryTags.REMOVE_FROM_PHONE)).isFalse()
    assertThat(library.onPhoneOffers(LibraryTags.KEEP)).isFalse()
    assertThat(detail(deleted)?.deletedOnComputer).isTrue()

    // Leaving the page releases the clip: it is removed from this phone with one notice (spec 7.9).
    phono.back()
    phono.assertShown(LibraryKey)
    library.awaitClipsRemovedNotice(1)
    library.awaitTotal(1)
    assertThat(library.hasRow(deleted)).isFalse()
    library.scrollTo(other)
    assertThat(detail(deleted)).isNull()
    assertThat(detail(other)).isNotNull()
  }

  private fun detail(id: JobId): ClipDetail? = runBlocking { clips.clip(id).first() }

  /**
   * Waits until the sync engine has heard of the deletion: the job channel's `deleted` event, or two complete listings
   * without the clip (architecture 7.7), which the sync pass asked for at each poll brings when no channel is open. The
   * open page holds the clip, so it stays in the catalogue, marked.
   */
  private fun awaitMarkedDeleted(id: JobId) = runBlocking {
    try {
      withTimeout(SYNC_TIMEOUT) {
        while (true) {
          val detail = checkNotNull(clips.clip(id).first()) { "The clip left this phone while its page held it open" }
          if (detail.deletedOnComputer) break
          sync.syncNow(SyncReason.SYNC_NOW)
          seeder.workDriver.runEnqueued()
          delay(POLL)
        }
      }
    } catch (timedOut: TimeoutCancellationException) {
      throw AssertionError("The sync engine did not hear that ${id.value} was deleted on the computer", timedOut)
    }
  }

  private companion object {
    const val DELETED = "Ordering coffee"
    const val OTHER = "At the station"
    val SYNC_TIMEOUT = 30.seconds
    val POLL = 500.milliseconds
  }
}
