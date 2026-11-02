package com.mapineda48.phonotrainer.core.requests.store

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.remote.MediaKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteAnalysisInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteMedia
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteSource
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteSummary
import com.mapineda48.phonotrainer.core.domain.model.remote.SeparationNote
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant
import org.junit.Test

/**
 * What a request knows about its clip (CR-WP22-1, CR-WP22-3): its length from the job, the catalogue or the phone's own
 * copy, and whether "Separate the dialogue" ran, with a finished job's analysis as the authority (server-contract 6.3).
 */
class ClipFactsTest {
  private fun job(
    status: RemoteStatus = RemoteStatus.RUNNING,
    separateDialogue: Boolean = true,
    separation: SeparationNote? = null,
    applied: Boolean? = null,
    summaryS: Double? = null,
    mediaS: Double? = null,
  ) = RemoteJob(
    id = JobId("20261010-091503-4be1"),
    revision = "5c0e8a1f9b2d7e64",
    status = status,
    stage = null,
    percent = 0,
    segment = null,
    segments = null,
    downloadPercent = null,
    separation = separation,
    queuePosition = null,
    title = null,
    source = RemoteSource(SourceKind.LINK, "https://youtu.be/abc123def45", null, null),
    audioOnly = false,
    separateDialogue = separateDialogue,
    deviceId = null,
    own = true,
    clientRequestId = null,
    rerunOf = null,
    createdAt = Instant.EPOCH,
    startedAt = null,
    finishedAt = null,
    error = null,
    summary = summaryS?.let { RemoteSummary(it, 1, 1, emptyMap()) },
    analysis = applied?.let { RemoteAnalysisInfo(3, "timit61", it) },
    media = mediaS?.let { RemoteMedia(MediaKind.VIDEO, "h264", 1280, 720, 0, 0, it) },
    artifacts = null,
  )

  @Test
  fun theLengthIsTheJobsThenTheCataloguesThenThePhonesOwnRead() {
    assertThat(ClipFacts.durationS(job(summaryS = 260.5, mediaS = 260.6), 1_000, 2_000)).isEqualTo(260.5)
    assertThat(ClipFacts.durationS(job(mediaS = 260.6), 1_000, 2_000)).isEqualTo(260.6)
    assertThat(ClipFacts.durationS(job(), 250_500, 2_000)).isEqualTo(250.5)
    assertThat(ClipFacts.durationS(null, null, 42_000)).isEqualTo(42.0)
    assertThat(ClipFacts.durationS(job(summaryS = 0.0), null, null)).isNull()
    assertThat(ClipFacts.durationS(null, 0, -1)).isNull()
  }

  @Test
  fun aSeparationIsOffSkippedOrUnknownAsTheJobSaysAndAFinishedAnalysisDecides() {
    assertThat(ClipFacts.separation(true, job(separateDialogue = false, separation = SeparationNote.OFF), null, null))
      .isEqualTo(SeparationNote.OFF)
    assertThat(ClipFacts.separation(true, job(separation = SeparationNote.SKIPPED), null, null)).isEqualTo(SeparationNote.SKIPPED)
    assertThat(ClipFacts.separation(true, job(separation = SeparationNote.UNKNOWN), null, null)).isEqualTo(SeparationNote.UNKNOWN)
    assertThat(ClipFacts.separation(true, job(), null, null)).isNull()
    // A finished job: `analysis.dialogue_separation` is authoritative (server-contract 6.3).
    assertThat(ClipFacts.separation(true, job(RemoteStatus.DONE, applied = false), null, null)).isEqualTo(SeparationNote.SKIPPED)
    assertThat(ClipFacts.separation(true, job(RemoteStatus.DONE, separation = SeparationNote.SKIPPED, applied = true), null, null))
      .isNull()
    assertThat(ClipFacts.separation(true, job(RemoteStatus.DONE, separateDialogue = false, applied = false), null, null))
      .isEqualTo(SeparationNote.OFF)
  }

  @Test
  fun withoutTheJobTheRequestsChoiceAndTheCataloguesFinishedAnalysisSayIt() {
    assertThat(ClipFacts.separation(false, null, null, null)).isEqualTo(SeparationNote.OFF)
    assertThat(ClipFacts.separation(true, null, "DONE", false)).isEqualTo(SeparationNote.SKIPPED)
    assertThat(ClipFacts.separation(true, null, "DONE", true)).isNull()
    // A job not finished yet says nothing about it in the catalogue: nothing is claimed.
    assertThat(ClipFacts.separation(true, null, "RUNNING", false)).isNull()
    assertThat(ClipFacts.separation(true, null, null, null)).isNull()
  }
}
