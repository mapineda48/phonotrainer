package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.MassDeletionPrompt
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.sync.catalogue.ListingOutcome
import com.mapineda48.phonotrainer.core.sync.catalogue.PairedSource
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The catalogue reconcile (architecture 7.3, 7.7; QT-11, QT-14; spec 7.9). */
@RunWith(RobolectricTestRunner::class)
class CatalogueTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  private fun SyncHarness.source() = PairedSource(computer.serverId.value, "Desk")

  @Test
  fun everyJobOfTheListingIsMirroredWithItsArtifacts() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    val clip = h.clip(id)
    assertThat(clip.title).isEqualTo("Ordering coffee")
    assertThat(clip.origin).isEqualTo(ClipOrigin.ACTIVE.name)
    assertThat(clip.serverId).isEqualTo(h.computer.serverId.value)
    assertThat(clip.libraryId).isEqualTo(h.computer.libraryId.value)
    assertThat(clip.firstSeenAt).isEqualTo(h.clock.now().toEpochMilli())
    val names = h.artifacts.forJob(id.value).map { it.name }
    assertThat(names).containsExactly(Names.ANALYSIS, Names.MIX, Names.DIALOGUE, Names.ORIGINAL)
    val mix = h.artifact(id, Names.MIX)
    assertThat(mix.remoteSha256).isNotNull()
    assertThat(mix.remoteBytes).isGreaterThan(0L)
    assertThat(mix.localState).isEqualTo("QUEUED")
  }

  @Test
  fun aNewRevisionOfAFileMarksANewDownloadAndKeepsTheVerifiedCopyInUseUntilTheSwitch() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee", video = false)
    h.syncAndDownload()
    val before = h.artifact(id, Names.ANALYSIS)
    val firstRevision = h.clip(id).revision
    assertThat(before.localState).isEqualTo("READY")

    // The computer serves another analysis for the same job: its artifact revision changes.
    h.events.clear()
    h.computer.finishJob(id, content = FixtureClips.content(AnalysisFixtures.mkAnalysis(source = "Ordering coffee, again"), false))
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    val waiting = h.artifact(id, Names.ANALYSIS)
    assertThat(h.clip(id).revision).isNotEqualTo(firstRevision)
    assertThat(waiting.remoteSha256).isNotEqualTo(before.localSha256)
    assertThat(waiting.localFile).isEqualTo(before.localFile)
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.events).contains("downloads")

    h.downloader.run {}

    val switched = h.artifact(id, Names.ANALYSIS)
    assertThat(switched.localSha256).isEqualTo(waiting.remoteSha256)
    assertThat(switched.localFile).isNotEqualTo(before.localFile)
    assertThat(h.files.resolve(before.localFile)!!.exists()).isFalse()
    assertThat(h.files.resolve(switched.localFile)!!.exists()).isTrue()
  }

  @Test
  fun aListingFromAnotherServerIdIsIgnoredEntirely() = sync { h ->
    // QT-11: nothing is deleted, nothing is added.
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val listing = h.library().copy(serverId = ServerId("srv_other"), jobs = emptyList())

    repeat(3) { assertThat(h.catalogue.reconcile(listing, h.source())).isEqualTo(ListingOutcome.OtherComputer) }

    assertThat(h.clips.get(id.value)!!.missingListings).isEqualTo(0)
    assertThat(h.clips.count()).isEqualTo(1)
  }

  @Test
  fun aNewLibraryIdTagsTheEarlierClipsAndDeletesNothing() = sync { h ->
    // QT-14.
    val first = h.computer.addFinishedClip("Ordering coffee")
    val second = h.computer.addFinishedClip("At the station")
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    h.computer.changeLibraryId()
    val third = h.computer.addFinishedClip("In the kitchen")
    repeat(3) { h.orchestrator.pass(SyncReason.SYNC_NOW) }

    assertThat(h.clip(first).origin).isEqualTo(ClipOrigin.OTHER_LIBRARY.name)
    assertThat(h.clip(second).origin).isEqualTo(ClipOrigin.OTHER_LIBRARY.name)
    assertThat(h.clip(third).origin).isEqualTo(ClipOrigin.ACTIVE.name)
    assertThat(h.messages.posted.map { it.kind }).containsExactly(UserMessageKind.LIBRARY_CHANGED)
    assertThat(h.pairing.current()!!.libraryId).isEqualTo(h.computer.libraryId)
    assertThat(h.hooks.removed).isEmpty()
  }

  @Test
  fun clipsOfAnotherLibraryComeBackWhenTheComputerUsesThatLibraryAgainButForgottenOnesNever() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val listing = h.library()
    h.computer.changeLibraryId()
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    assertThat(h.clip(id).origin).isEqualTo(ClipOrigin.OTHER_LIBRARY.name)

    h.catalogue.reconcile(listing, h.source())
    assertThat(h.clip(id).origin).isEqualTo(ClipOrigin.ACTIVE.name)

    assertThat(h.clips.tagForgotten(h.computer.serverId.value)).isEqualTo(1)
    h.catalogue.reconcile(listing, h.source())
    assertThat(h.clip(id).origin).isEqualTo(ClipOrigin.FORGOTTEN_COMPUTER.name)
  }

  @Test
  fun jobsThisPhoneDidNotSubmitAppearOnlyInStartedOnYourComputerUntilTheyFinish() = sync { h ->
    val browser = h.computer.addJob(JobSeed(status = JobStatus.RUNNING, title = "From the browser"))
    val failed = h.computer.addJob(JobSeed(status = JobStatus.ERROR, errorCode = "download_forbidden", title = null))
    val own = h.computer.addJob(JobSeed(status = JobStatus.RUNNING, deviceId = h.phone.deviceId, title = "Mine"))
    val done = h.computer.addFinishedClip("Finished in the browser")

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    val jobs = h.clipRepository.browserJobs().first()
    assertThat(jobs.map { it.id }).containsExactly(browser, failed)
    assertThat(jobs.single { it.id == failed }.error!!.code).isEqualTo("download_forbidden")
    assertThat(jobs.single { it.id == browser }.status).isEqualTo(RemoteStatus.RUNNING)
    assertThat(h.clipRepository.clips(ClipQuery()).first().map { it.id }).containsExactly(done)
    assertThat(h.clip(own).submittedByDevice).isEqualTo(h.phone.deviceId.value)

    h.clipRepository.hideBrowserJob(failed)
    assertThat(h.clipRepository.browserJobs().first().single { it.id == failed }.hidden).isTrue()
  }

  @Test
  fun aClipMissingFromOneListingStaysAndFromTwoConsecutiveListingsIsDeleted() = sync { h ->
    val gone = h.computer.addFinishedClip("Deleted in the browser")
    val kept = (1..3).map { h.computer.addFinishedClip("Clip $it") }
    h.syncAndDownload()
    h.computer.deleteJob(gone)

    h.orchestrator.pass(SyncReason.SYNC_NOW)
    assertThat(h.clips.get(gone.value)).isNotNull()
    assertThat(h.clip(gone).missingListings).isEqualTo(1)

    h.orchestrator.pass(SyncReason.SYNC_NOW)
    assertThat(h.clips.get(gone.value)).isNull()
    assertThat(h.artifacts.forJob(gone.value)).isEmpty()
    assertThat(h.files.clipDir(gone.value)!!.exists()).isFalse()
    kept.forEach { assertThat(h.clips.get(it.value)).isNotNull() }
    assertThat(h.hooks.removed).containsExactly(setOf(gone))
    assertThat(h.reconciler.deleted).containsExactly(gone)
    val notice = h.messages.posted.single { it.kind == UserMessageKind.CLIPS_REMOVED }
    assertThat(notice.args).containsExactly("1")
  }

  @Test
  fun aClipListedAgainBeforeTheSecondListingIsNeverDeleted() = sync { h ->
    val id = h.computer.addFinishedClip("Flaky")
    (1..2).forEach { h.computer.addFinishedClip("Clip $it") }
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val withIt = h.library()

    h.catalogue.reconcile(withIt.copy(jobs = withIt.jobs.filter { it.id != id }), h.source())
    h.catalogue.reconcile(withIt, h.source())
    h.catalogue.reconcile(withIt.copy(jobs = withIt.jobs.filter { it.id != id }), h.source())

    assertThat(h.clip(id).missingListings).isEqualTo(1)
  }

  @Test
  fun whenMoreThanHalfOfTheClipsWouldGoAtOnceTheLearnerIsAskedAndNothingIsRemoved() = sync { h ->
    val ids = (1..10).map { h.computer.addFinishedClip("Clip $it") }
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val listing = h.library()
    val nine = listing.copy(jobs = listing.jobs.filter { it.id == ids.first() })

    h.catalogue.reconcile(nine, h.source())
    val outcome = h.catalogue.reconcile(nine, h.source())

    assertThat(outcome).isEqualTo(ListingOutcome.Applied(libraryChanged = false, removed = emptySet(), askedLearner = true))
    assertThat(h.clips.count()).isEqualTo(10)
    assertThat(h.clipRepository.pendingMassDeletion.first()).isEqualTo(MassDeletionPrompt(count = 9, ofTotal = 10, serverName = "Desk"))

    h.clipRepository.resolveMassDeletion(remove = true)

    assertThat(h.clips.count()).isEqualTo(1)
    assertThat(h.clipRepository.pendingMassDeletion.first()).isNull()
    assertThat(h.messages.posted.single { it.kind == UserMessageKind.CLIPS_REMOVED }.args).containsExactly("9")
  }

  @Test
  fun clipsTheLearnerKeptAfterTheQuestionAreNotAskedAboutAgain() = sync { h ->
    val ids = (1..4).map { h.computer.addFinishedClip("Clip $it") }
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val listing = h.library()
    val one = listing.copy(jobs = listing.jobs.filter { it.id == ids.first() })
    h.catalogue.reconcile(one, h.source())
    h.catalogue.reconcile(one, h.source())

    h.clipRepository.resolveMassDeletion(remove = false)
    h.catalogue.reconcile(one, h.source())
    h.catalogue.reconcile(one, h.source())

    assertThat(h.clips.count()).isEqualTo(4)
    assertThat(h.clipRepository.pendingMassDeletion.first()).isNull()

    // Listed once more, then missing again: they are counted again like any clip.
    h.catalogue.reconcile(listing, h.source())
    assertThat(h.clip(ids.last()).missingListings).isEqualTo(0)
  }

  @Test
  fun aLeanChannelJobUpdatesTheStateAndAsksForAPassOnlyForANewRevisionOrANewJob() = sync { h ->
    val id = h.computer.addJob(JobSeed(status = JobStatus.RUNNING, title = "Analyzing"))
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    val listed = h.library().jobs.single()

    val samePercent = h.catalogue.applyLean(listed.copy(percent = 75, artifacts = null), h.source(), h.computer.libraryId.value)
    assertThat(samePercent).isFalse()
    assertThat(h.clip(id).percent).isEqualTo(75)
    assertThat(h.clip(id).revision).isEqualTo(listed.revision)

    val finished = h.catalogue.applyLean(
      listed.copy(status = RemoteStatus.DONE, revision = "0123456789abcdef", artifacts = null), h.source(), h.computer.libraryId.value,
    )
    assertThat(finished).isTrue()
    assertThat(h.clip(id).status).isEqualTo("DONE")
    assertThat(h.clip(id).revision).isEqualTo(listed.revision)

    val brandNew = h.catalogue.applyLean(listed.copy(id = JobId("20261010-120000-beef")), h.source(), h.computer.libraryId.value)
    assertThat(brandNew).isTrue()
    assertThat(h.clips.get("20261010-120000-beef")).isNotNull()
  }
}
