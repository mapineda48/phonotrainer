package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the catalogue shows (architecture 3.4; spec 7.11, F-04, F-05, F-21 "Loading"). */
@RunWith(RobolectricTestRunner::class)
class ClipRepositoryTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  @Test
  fun aClipSummarySaysWhereItCameFromWhatItHoldsAndHowMuchWasSeen() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()
    h.db.openHelper.writableDatabase.execSQL(
      "INSERT INTO short (job_id, first_segment, rule_version, ordinal, last_segment, start_ms, end_ms, play_start_ms, " +
        "play_end_ms, marked_words) VALUES (?, 0, 1, 1, 0, 0, 1, 0, 1, 0), (?, 1, 1, 2, 1, 1, 2, 1, 2, 0)",
      arrayOf(id.value, id.value),
    )
    h.progress.seenState.value = setOf(ShortId(id, 0))

    val clip = h.clipRepository.clips(ClipQuery()).first().single()

    assertThat(clip.title).isEqualTo("Ordering coffee")
    assertThat(clip.source.kind).isEqualTo(SourceKind.LINK)
    assertThat(clip.source.site).isEqualTo(Site.YOUTUBE)
    assertThat(clip.availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(clip.shortsTotal).isEqualTo(2)
    assertThat(clip.shortsSeen).isEqualTo(1)
    assertThat(clip.words).isGreaterThan(0)
    assertThat(clip.durationS).isGreaterThan(0.0)
    assertThat(clip.olderRules).isFalse()
  }

  @Test
  fun theClipPageHasItsCountsItsFilesAndTheirStates() = sync { h ->
    h.settings.state.value = h.settings.state.value.copy(downloadVideos = false)
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.computer.truncate(Names.MIX, atBytes = 1_000)
    h.downloader.run {}

    val detail = h.clipRepository.clip(id).first()!!

    assertThat(detail.summary.availability).isInstanceOf(ClipAvailability.Bringing::class.java)
    assertThat(detail.hasDialogueTrack).isTrue()
    assertThat(detail.changeCounts.keys.map(ChangeLabel::value)).isNotEmpty()
    val byRole = detail.artifacts.associateBy { it.role }
    assertThat(byRole.getValue(ArtifactRole.CLIP_DATA).state).isEqualTo(ArtifactLocalState.READY)
    assertThat(byRole.getValue(ArtifactRole.CLIP_DATA).percent).isEqualTo(100)
    assertThat(byRole.getValue(ArtifactRole.AUDIO_MIX).state).isEqualTo(ArtifactLocalState.QUEUED)
    assertThat(byRole.getValue(ArtifactRole.AUDIO_MIX).percent).isGreaterThan(0)
    assertThat(detail.studyPackBytes).isGreaterThan(0L)
    assertThat(detail.videoBytes).isNull()
  }

  @Test
  fun clipsAnalysedWithOlderRulesThanTheNewestOfTheCatalogueSaySo() = sync { h ->
    val old = h.computer.addFinishedClip("Old")
    h.computer.addFinishedClip("New")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.clips.update(h.clip(old).copy(rulesVersion = 1))

    val byId = h.clipRepository.clips(ClipQuery()).first().associateBy { it.id }

    assertThat(byId.getValue(old).olderRules).isTrue()
    assertThat(byId.values.count { it.olderRules }).isEqualTo(1)
  }

  @Test
  fun searchFilterAndSortFollowTheLibrarysRules() = sync { h ->
    val coffee = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.computer.clock.advanceSeconds(10)
    val station = h.computer.addFinishedClip("At the station", video = false)
    h.syncAndDownload()
    h.clipRepository.removeFromPhone(station)
    h.clipRepository.setKept(coffee, kept = true)

    assertThat(h.clipRepository.clips(ClipQuery(search = "COFFEE")).first().map { it.id }).containsExactly(coffee)
    assertThat(h.clipRepository.clips(ClipQuery(filter = ClipFilter.ON_THIS_PHONE)).first().map { it.id }).containsExactly(coffee)
    assertThat(h.clipRepository.clips(ClipQuery(filter = ClipFilter.NOT_ON_THIS_PHONE)).first().map { it.id }).containsExactly(station)
    assertThat(h.clipRepository.clips(ClipQuery(filter = ClipFilter.KEPT)).first().map { it.id }).containsExactly(coffee)
    assertThat(h.clipRepository.clips(ClipQuery(sort = ClipSort.NEWEST)).first().map { it.id }).containsExactly(station, coffee).inOrder()
    assertThat(h.clipRepository.clips(ClipQuery(sort = ClipSort.TITLE)).first().map { it.id }).containsExactly(station, coffee).inOrder()
    assertThat(h.clipRepository.clips(ClipQuery(sort = ClipSort.OLDEST)).first().map { it.id }).containsExactly(coffee, station).inOrder()
  }

  @Test
  fun newOnThisPhoneHoldsClipsThatArrivedRecentlyAndWereNotWatched() = sync { h ->
    val watched = h.computer.addFinishedClip("Watched", video = false)
    val fresh = h.computer.addFinishedClip("Fresh", video = false)
    h.syncAndDownload()
    h.protection.watched = mapOf(watched to Instant.now())

    assertThat(h.clipRepository.newOnThisPhone().first().map { it.id }).containsExactly(fresh)

    h.clock.advanceBy(Duration.ofDays(4))
    assertThat(h.clipRepository.newOnThisPhone().first()).isEmpty()
  }

  @Test
  fun aClipStillBeingPreparedOnTheComputerIsGettingReady() = sync { h ->
    val id = h.computer.addJob(JobSeed(title = "Checksums pending"))
    h.computer.setArtifactStatus(id, Names.ANALYSIS, com.mapineda48.phonotrainer.core.testing.computer.ArtifactStatus.PREPARING)

    h.syncAndDownload()

    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.GettingReadyOnComputer)
  }
}
