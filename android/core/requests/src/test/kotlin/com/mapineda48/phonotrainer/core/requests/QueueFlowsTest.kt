package com.mapineda48.phonotrainer.core.requests

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStage
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import com.mapineda48.phonotrainer.core.testing.computer.LinkHostAnswer
import com.mapineda48.phonotrainer.core.testing.computer.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The device queue end to end on the in-memory fake computer (architecture 8; QT-1 to QT-8, QT-11, QT-12, SH-AC2 to
 * SH-AC8, F-20-AC1, AC3, AC5, AC8, AC12, F-23-AC2 to AC4, F-03-AC4, AC9): links stored first and sent once with their
 * keys, the outbox before new requests, re-runs keeping one row, and the computer's view moving own requests only.
 */
@RunWith(RobolectricTestRunner::class)
class QueueFlowsTest {
  private fun queueTest(block: suspend TestScope.(QueueHarness) -> Unit) = runTest {
    QueueHarness(backgroundScope).use { block(it) }
  }

  private suspend fun QueueHarness.add(text: String, subject: String? = null, audioOnly: Boolean = false, force: Boolean = false): RequestId =
    (repository.addLink(LinkInput(text, subject, audioOnly, force)) as AddResult.Added).id

  private suspend fun QueueHarness.listing(): RemoteLibrary = (fakeClient.library() as ApiResult.Ok).value

  private suspend fun QueueHarness.sync() {
    sender.sendOperations()
    sender.sendLinks()
    reconciler.reconcile(listing())
  }

  @Test
  fun aSharedLinkIsStoredBeforeAnythingIsSentWithoutItsTrackingParameters() = queueTest { queue ->
    queue.status.statusState.value = ComputerStatus.NotReachable("Desk", null, false, com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome.NoAnswer(3))

    val id = queue.add("Great scene https://youtu.be/abc?si=xyz")

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.WAITING)
    assertThat(request.link).isEqualTo("https://youtu.be/abc")
    assertThat(request.site).isEqualTo(Site.YOUTUBE)
    assertThat(request.title).isEqualTo("Great scene")
    assertThat(request.actions).containsExactly(RequestAction.REMOVE)
    assertThat(queue.trigger.reasons).containsExactly(SyncReason.REQUEST_CREATED)
    assertThat(queue.computer.requests).isEmpty()
  }

  @Test
  fun twoRequestsWaitingOfflineAreCountedAsWaiting() = queueTest { queue ->
    queue.status.statusState.value = ComputerStatus.Offline
    queue.add("https://youtu.be/one")
    queue.add("https://x.com/someone/status/42")

    assertThat(queue.repository.counts().first()).isEqualTo(RequestCounts(waiting = 2, onComputer = 0, needsAttention = 0, readyNew = 0))
    assertThat(queue.repository.requests().first().map { it.title }).containsExactly("YOUTUBE link", "X link").inOrder()
  }

  @Test
  fun sharedTextWithoutALinkCreatesNothing() = queueTest { queue ->
    assertThat(queue.repository.addLink(LinkInput("Nice video!", null, audioOnly = false))).isEqualTo(AddResult.NoLink)
    assertThat(queue.repository.requests().first()).isEmpty()
  }

  @Test
  fun theSameLinkTwiceIsADuplicateOfTheQueueOrTheCatalogueUnlessForced() = queueTest { queue ->
    val first = queue.add("https://www.youtube.com/watch?v=abc123def45&t=30")

    val again = queue.repository.addLink(LinkInput("https://youtu.be/abc123def45?si=x", null, audioOnly = false))
    assertThat(again).isInstanceOf(AddResult.DuplicateFound::class.java)
    assertThat((again as AddResult.DuplicateFound).duplicate.requestId).isEqualTo(first)

    queue.catalogueClip(JobId("20261011-090000-abcd"), "From the browser", "https://x.com/a/status/77")
    val clip = queue.repository.findDuplicate(LinkInput("https://twitter.com/b/status/77?s=20", null, audioOnly = false))
    assertThat(clip!!.jobId).isEqualTo(JobId("20261011-090000-abcd"))
    assertThat(clip.title).isEqualTo("From the browser")

    val forced = queue.repository.addLink(LinkInput("https://youtu.be/abc123def45", null, audioOnly = false, force = true))
    assertThat(forced).isInstanceOf(AddResult.Added::class.java)
    assertThat(queue.repository.requests().first()).hasSize(2)
  }

  @Test
  fun waitingLinksAreSentInOrderAndTheComputerHasOneJobForEachKey() = queueTest { queue ->
    val ids = listOf("https://youtu.be/aaaaaaaaaaa", "https://youtu.be/bbbbbbbbbbb", "https://youtu.be/ccccccccccc").map { queue.add(it) }

    val report = queue.sender.sendLinks()

    assertThat(report.sent).isEqualTo(3)
    assertThat(queue.computer.requests.filter { it.path == "/api/phone/jobs/link" }).hasSize(3)
    ids.forEach { id ->
      assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
      val request = queue.request(id)
      assertThat(request.state).isEqualTo(RequestState.IN_LINE)
      assertThat(request.jobId).isEqualTo(queue.computer.jobsWithKey(id).single().id)
    }
    // The computer lines them up in arrival order: the first link sent is first in line.
    queue.reconciler.reconcile(queue.listing())
    assertThat(ids.map { queue.request(it).progress!!.queuePosition }).containsExactly(1, 2, 3).inOrder()
  }

  @Test
  fun aLostAnswerIsRetriedWithTheSameKeyAndNeverMakesASecondJob() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.computer.applyThenDrop("/api/phone/jobs/link")

    val first = queue.sender.sendLinks()
    assertThat(first.transient).isEqualTo(1)
    assertThat(queue.request(id).state).isEqualTo(RequestState.WAITING)
    assertThat(queue.request(id).waiting!!.attempts).isEqualTo(1)

    queue.clock.advanceBy(java.time.Duration.ofSeconds(30))
    queue.sender.sendLinks()

    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
    assertThat(queue.request(id).jobId).isEqualTo(queue.computer.jobsWithKey(id).single().id)
  }

  @Test
  fun aLostAnswerIsAlsoRecognizedInTheListing() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.computer.applyThenDrop("/api/phone/jobs/link")
    queue.sender.sendLinks()

    queue.reconciler.reconcile(queue.listing())

    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
  }

  @Test
  fun aRequestRemovedWhileOfflineIsNeverSent() = queueTest { queue ->
    queue.status.statusState.value = ComputerStatus.Offline
    val id = queue.add("https://youtu.be/abc123def45")

    queue.repository.perform(id, RequestAction.REMOVE)
    queue.status.statusState.value = QueueHarness.CONNECTED
    queue.sync()

    assertThat(queue.repository.requests().first()).isEmpty()
    assertThat(queue.computer.requests.none { it.path == "/api/phone/jobs/link" }).isTrue()
  }

  @Test
  fun aCancelMadeOfflineReachesTheComputerBeforeAnyNewRequest() = queueTest { queue ->
    val accepted = queue.add("https://youtu.be/aaaaaaaaaaa")
    queue.sender.sendLinks()
    queue.status.statusState.value = ComputerStatus.Offline

    queue.repository.perform(accepted, RequestAction.CANCEL)
    assertThat(queue.request(accepted).state).isEqualTo(RequestState.CANCEL_WAITING)
    assertThat(queue.request(accepted).actions).containsExactly(RequestAction.UNDO_CANCEL)
    val later = queue.add("https://youtu.be/bbbbbbbbbbb")
    queue.status.statusState.value = QueueHarness.CONNECTED
    queue.sync()

    val calls = queue.computer.requests.map { it.method + " " + it.path }
    val cancel = calls.indexOfFirst { it.endsWith("/cancel") }
    val link = calls.indexOfLast { it == "POST /api/phone/jobs/link" }
    assertThat(cancel).isAtLeast(0)
    assertThat(cancel).isLessThan(link)
    assertThat(queue.request(accepted).state).isEqualTo(RequestState.CANCELLED)
    assertThat(queue.request(later).state).isEqualTo(RequestState.IN_LINE)
  }

  @Test
  fun aCancelThatArrivesAfterTheAnalysisFinishedKeepsTheClipAndSaysSo() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45", subject = "A walk in the park")
    queue.sender.sendLinks()
    queue.status.statusState.value = ComputerStatus.Offline
    queue.repository.perform(id, RequestAction.CANCEL)
    queue.computer.finishJob(queue.request(id).jobId!!, title = "A walk in the park")

    queue.status.statusState.value = QueueHarness.CONNECTED
    queue.sender.sendOperations()

    assertThat(queue.request(id).state).isEqualTo(RequestState.READY_ON_COMPUTER)
    assertThat(queue.messages.posted.map { it.kind to it.args }).containsExactly(UserMessageKind.TOO_LATE_TO_CANCEL to listOf("A walk in the park"))
  }

  @Test
  fun undoOfAWaitingCancelRestoresTheRequestOnceAndNoCancelReachesTheComputer() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45", subject = "Coffee shop")
    queue.sender.sendLinks()
    queue.status.statusState.value = ComputerStatus.Offline
    queue.repository.perform(id, RequestAction.CANCEL)

    queue.repository.perform(id, RequestAction.UNDO_CANCEL)
    queue.repository.perform(id, RequestAction.UNDO_CANCEL)
    queue.status.statusState.value = QueueHarness.CONNECTED
    queue.sync()

    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
    assertThat(queue.messages.posted.map { it.kind to it.args }).containsExactly(UserMessageKind.CANCEL_UNDONE to listOf("Coffee shop"))
    assertThat(queue.computer.requests.none { it.path.endsWith("/cancel") }).isTrue()
  }

  @Test
  fun aRejectedCredentialPausesTheQueueAndMarksNothingFailed() = queueTest { queue ->
    val ids = listOf(queue.add("https://youtu.be/aaaaaaaaaaa"), queue.add("https://youtu.be/bbbbbbbbbbb"))
    queue.computer.revokeDevice()

    val report = queue.sender.sendLinks()

    assertThat(report.blocked).isTrue()
    ids.forEach { id ->
      val request = queue.request(id)
      assertThat(request.state).isEqualTo(RequestState.WAITING)
      assertThat(request.waiting!!.attempts).isEqualTo(0)
      assertThat(request.lastError).isNull()
    }
    assertThat(queue.notifier.attention).isEmpty()
    // The queue's pause is derived from the computer's status, never written into rows.
    queue.status.statusState.value = ComputerStatus.ConnectAgain
    assertThat(queue.request(ids[0]).actions).containsExactly(RequestAction.REMOVE, RequestAction.CONNECT_AGAIN)
  }

  @Test
  fun aLinkRefusedAtSendingNeedsAttentionAndNotifiesOnce() = queueTest { queue ->
    queue.computer.linkHost("printer.example", LinkHostAnswer.REFUSED)
    val id = queue.add("https://printer.example/video")

    queue.sync()
    queue.sync()

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.NEEDS_ATTENTION)
    assertThat(request.lastError!!.code).isEqualTo("refused_address")
    assertThat(request.lastError!!.atSending).isTrue()
    assertThat(request.actions).containsExactly(RequestAction.REMOVE)
    assertThat(queue.notifier.attention.map { it.first }).containsExactly(id)
  }

  @Test
  fun aDownloadFailureTriedAsAudioOnlyIsReRunAndKeepsOneRow() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.sync()
    val failedJob = queue.request(id).jobId!!
    queue.computer.failJob(failedJob, "download_forbidden")
    queue.sync()
    val failed = queue.request(id)
    assertThat(failed.state).isEqualTo(RequestState.NEEDS_ATTENTION)
    assertThat(failed.actions).containsExactly(
      RequestAction.TRY_AGAIN,
      RequestAction.TRY_AUDIO_ONLY,
      RequestAction.OPEN_LINK,
      RequestAction.REMOVE,
    )
    assertThat(failed.lastError!!.downloaderVersion).isEqualTo("2026.8.19")

    queue.repository.perform(id, RequestAction.TRY_AUDIO_ONLY)
    assertThat(queue.request(id).rerunPending).isTrue()
    queue.sync()

    val rerun = queue.request(id)
    assertThat(rerun.state).isEqualTo(RequestState.IN_LINE)
    assertThat(rerun.jobId).isNotEqualTo(failedJob)
    assertThat(queue.computer.job(rerun.jobId!!)!!.audioOnly).isTrue()
    assertThat(queue.computer.job(failedJob)).isNull()
    assertThat(queue.repository.requests().first()).hasSize(1)
  }

  @Test
  fun aComputerRestartReRunsEveryOwnJobOnceInAcceptanceOrder() = queueTest { queue ->
    val ids = listOf("https://youtu.be/aaaaaaaaaaa", "https://youtu.be/bbbbbbbbbbb", "https://youtu.be/ccccccccccc").map { queue.add(it) }
    queue.sync()
    queue.computer.progress(queue.request(ids[0]).jobId!!, JobStage.TRANSCRIBE, 40)
    queue.reconciler.reconcile(queue.listing())
    val oldJobs = ids.map { queue.request(it).jobId!! }
    queue.computer.restart()

    queue.reconciler.reconcile(queue.listing())
    ids.forEach { assertThat(queue.request(it).rerunPending).isTrue() }
    // "Starting again on your computer": it keeps its last known state until the re-run is accepted.
    assertThat(queue.request(ids[0]).state).isEqualTo(RequestState.ANALYZING)
    assertThat(queue.repository.counts().first().needsAttention).isEqualTo(0)
    queue.sender.sendOperations()

    val reruns = queue.computer.requests.filter { it.path.endsWith("/rerun") }.map { it.path }
    assertThat(reruns).containsExactlyElementsIn(oldJobs.map { "/api/phone/jobs/${it.value}/rerun" }).inOrder()
    ids.forEachIndexed { index, id ->
      val job = queue.computer.job(queue.request(id).jobId!!)!!
      assertThat(job.rerunOf).isEqualTo(oldJobs[index])
    }
    assertThat(queue.computer.jobs).hasSize(3)
    assertThat(queue.notifier.attention).isEmpty()

    // A second interruption needs the learner.
    queue.computer.restart()
    queue.reconciler.reconcile(queue.listing())
    ids.forEach { id ->
      val request = queue.request(id)
      assertThat(request.state).isEqualTo(RequestState.NEEDS_ATTENTION)
      assertThat(request.lastError!!.code).isEqualTo("interrupted")
    }
    assertThat(queue.notifier.attention).hasSize(3)
  }

  @Test
  fun aListingFromAnotherComputerChangesNothing() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.sync()
    val listing = queue.listing()
    queue.computer.failJob(queue.request(id).jobId!!, "download_forbidden")
    val failedListing = queue.listing()

    queue.reconciler.reconcile(failedListing.copy(serverId = com.mapineda48.phonotrainer.core.domain.model.ServerId("srv_other")))

    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
    assertThat(listing.jobs).hasSize(1)
  }

  @Test
  fun jobsOfTheBrowserNeverTouchTheQueueTheCountsOrTheNotifications() = queueTest { queue ->
    queue.computer.addJob(JobSeed(status = JobStatus.ERROR, kind = SourceKind.LINK, errorCode = "download_forbidden"))
    queue.computer.addJob(JobSeed(status = JobStatus.RUNNING))

    queue.reconciler.reconcile(queue.listing())

    assertThat(queue.repository.requests().first()).isEmpty()
    assertThat(queue.repository.counts().first()).isEqualTo(RequestCounts(0, 0, 0, 0))
    assertThat(queue.notifier.attention).isEmpty()
  }

  @Test
  fun aVideoTooLongForTheComputerNotifiesOnceWithItsCode() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45", subject = "Four hour stream")
    queue.sync()
    queue.computer.failJob(queue.request(id).jobId!!, "too_long")

    queue.sync()
    queue.sync()
    queue.reconciler.onJob((queue.fakeClient.job(queue.request(id).jobId!!) as ApiResult.Ok).value)

    assertThat(queue.notifier.attention.map { it.first to it.second.code }).containsExactly(id to "too_long")
    assertThat(queue.notifier.attentionTitles).containsExactly("Four hour stream")
    assertThat(queue.request(id).actions).containsExactly(RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.REMOVE)
    assertThat(queue.repository.counts().first().needsAttention).isEqualTo(1)
  }

  @Test
  fun aLiveStreamOffersTryAgainAndItsDetailsCarryNoTerminalCodes() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45", subject = "Morning live")
    queue.sync()
    queue.computer.failJob(queue.request(id).jobId!!, "live_stream", "\u001b[0;31mERROR:\u001b[0m This live event will begin soon.\r")

    queue.sync()

    val failed = queue.request(id)
    assertThat(failed.state).isEqualTo(RequestState.NEEDS_ATTENTION)
    assertThat(failed.lastError!!.code).isEqualTo("live_stream")
    assertThat(failed.lastError!!.message).isEqualTo("ERROR: This live event will begin soon.")
    assertThat(failed.actions).containsExactly(RequestAction.TRY_AGAIN, RequestAction.OPEN_LINK, RequestAction.REMOVE).inOrder()
    assertThat(queue.notifier.attention.single().second.message).isEqualTo("ERROR: This live event will begin soon.")
  }

  @Test
  fun theComputersProgressMovesTheRequestAndItsReadyClipSaysSoOnce() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.sync()
    val job = queue.request(id).jobId!!
    queue.computer.progress(job, JobStage.PHONES, 61, segment = 3, segments = 10)
    queue.reconciler.onJob((queue.fakeClient.job(job) as ApiResult.Ok).value)

    val analyzing = queue.request(id)
    assertThat(analyzing.state).isEqualTo(RequestState.ANALYZING)
    assertThat(analyzing.progress!!.percent).isEqualTo(61)
    assertThat(analyzing.progress!!.segment).isEqualTo(3)
    assertThat(analyzing.progress!!.segments).isEqualTo(10)
    assertThat(queue.repository.counts().first()).isEqualTo(RequestCounts(0, 1, 0, 0, analyzing = 1, inLine = 0))

    queue.computer.finishJob(job, title = "A walk in the park")
    queue.reconciler.reconcile(queue.listing())
    assertThat(queue.request(id).state).isEqualTo(RequestState.READY_ON_COMPUTER)
    assertThat(queue.request(id).title).isEqualTo("A walk in the park")
    queue.shorts.shortsById.value = mapOf(job to List(15) { ShortInfo(ShortId(job, it), it, 15, it, 0, 1, null, 0) })

    queue.reconciler.onClipOnPhone(job)
    queue.reconciler.onClipOnPhone(job)

    assertThat(queue.request(id).state).isEqualTo(RequestState.ON_PHONE)
    assertThat(queue.notifier.ready).containsExactly(Triple(job, "A walk in the park", 15))
  }

  @Test
  fun aJobDeletedOnTheComputerTakesItsRequestUnlessARerunReplacedIt() = queueTest { queue ->
    val id = queue.add("https://youtu.be/abc123def45")
    queue.sync()
    val job = queue.request(id).jobId!!

    queue.reconciler.onJobDeleted(job)

    assertThat(queue.repository.request(id).first()).isNull()
  }
}
