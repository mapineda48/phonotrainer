package com.mapineda48.phonotrainer.core.requests.rules

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import org.junit.Test

/**
 * The state machine of architecture 8.4, row by row, over the pure transition function. Each test names the row it
 * proves; a transition the table does not have returns null and changes nothing.
 */
class RequestMachineTest {
  private val now = 1_000_000L

  private fun on(row: RequestEntity, event: QueueEvent, context: MachineContext = MachineContext(now)) =
    RequestMachine.on(row, event, context)

  private fun state(step: Step?) = step?.row?.state

  // (new file) insert → PREPARING and (new link) insert → WAITING are the repository's inserts (RepositoryTest).

  @Test
  fun preparingCopyCompleteIsWaiting() {
    val step = on(file(RequestState.PREPARING), QueueEvent.CopyComplete("requests/r/source.mp4", SHA, 42, false, false))!!

    assertThat(state(step)).isEqualTo("WAITING")
    assertThat(step.row!!.copyComplete).isTrue()
    assertThat(step.row!!.fileBytes).isEqualTo(42)
    assertThat(step.row!!.copyFile).isEqualTo("requests/r/source.mp4")
    assertThat(step.effects).containsExactly(Effect.ReleaseSource, Effect.Trigger)
  }

  @Test
  fun preparingFailsForInterruptionSpaceOrUnreadable() {
    val noSpace = on(file(RequestState.PREPARING), QueueEvent.CopyFailed(CopyCodes.NO_SPACE, 5_000))!!
    assertThat(state(noSpace)).isEqualTo("COPY_FAILED")
    assertThat(noSpace.row!!.stateReason).isEqualTo(CopyCodes.NO_SPACE)
    assertThat(noSpace.row!!.neededBytes).isEqualTo(5_000)
    // No space keeps a picked file's permission ("Try audio only" may read it again); the others release it.
    assertThat(noSpace.effects).containsExactly(Effect.DeleteFiles)

    listOf(CopyCodes.INTERRUPTED, CopyCodes.UNREADABLE).forEach { code ->
      val step = on(file(RequestState.PREPARING), QueueEvent.CopyFailed(code))!!
      assertThat(step.row!!.stateReason).isEqualTo(code)
      assertThat(step.effects).containsExactly(Effect.DeleteFiles, Effect.ReleaseSource)
    }
  }

  @Test
  fun copyFailedNoSpaceVideoReadableTryAudioOnlyIsPreparingTheAudio() {
    val failed = file(RequestState.COPY_FAILED).copy(stateReason = CopyCodes.NO_SPACE, neededBytes = 9)
    val readable = MachineContext(now, isVideo = true, readable = true)

    val step = on(failed, QueueEvent.TryAudioOnly, readable)!!

    assertThat(state(step)).isEqualTo("PREPARING")
    assertThat(step.row!!.audioOnly).isTrue()
    assertThat(step.row!!.id).isEqualTo(failed.id)
    assertThat(step.effects).containsExactly(Effect.StartIntake(fromKeptCopy = false))
    // Not offered once the source cannot be read again, nor for an audio file (D-49).
    assertThat(on(failed, QueueEvent.TryAudioOnly, readable.copy(readable = false))).isNull()
    assertThat(on(failed, QueueEvent.TryAudioOnly, readable.copy(isVideo = false))).isNull()
    assertThat(on(failed.copy(audioOnly = true), QueueEvent.TryAudioOnly, readable)).isNull()
  }

  @Test
  fun waitingCopyMissingAtStartIsCopyMissing() {
    val step = on(file(RequestState.WAITING).copy(copyComplete = true), QueueEvent.CopyMissing)!!

    assertThat(state(step)).isEqualTo("COPY_MISSING")
    assertThat(step.row!!.stateReason).isEqualTo(CopyCodes.MISSING)
    assertThat(on(link(RequestState.WAITING), QueueEvent.CopyMissing)).isNull()
  }

  @Test
  fun waitingSendNowClearsTheHoldAndIsDueNow() {
    val step = on(link(RequestState.WAITING).copy(held = true, nextAttemptAt = now + 60_000), QueueEvent.SendNow)!!

    assertThat(state(step)).isEqualTo("WAITING")
    assertThat(step.row!!.held).isFalse()
    assertThat(step.row!!.nextAttemptAt).isNull()
    assertThat(step.effects).containsExactly(Effect.Trigger)
  }

  @Test
  fun waitingNotHeldAndDueIsPickedForSending() {
    assertThat(state(on(link(RequestState.WAITING), QueueEvent.Pick))).isEqualTo("SENDING")
    assertThat(on(link(RequestState.WAITING).copy(held = true), QueueEvent.Pick)).isNull()
    assertThat(on(link(RequestState.WAITING).copy(nextAttemptAt = now + 1), QueueEvent.Pick)).isNull()
    assertThat(on(file(RequestState.WAITING).copy(copyComplete = false), QueueEvent.Pick)).isNull()
    // Removed or cancelled a moment earlier: nothing to pick (QT-6).
    assertThat(on(link(RequestState.SENT), QueueEvent.Pick)).isNull()
  }

  @Test
  fun sendingAnsweredWithAJobIsSentAndTheCopyGoes() {
    val step = on(file(RequestState.SENDING).copy(copyComplete = true, copyFile = "requests/r/source.mp4", attempts = 2), QueueEvent.Accepted(JOB, "srv"))!!

    assertThat(state(step)).isEqualTo("SENT")
    assertThat(step.row!!.jobId).isEqualTo(JOB)
    assertThat(step.row!!.sentAt).isEqualTo(now)
    assertThat(step.row!!.targetServerId).isEqualTo("srv")
    assertThat(step.row!!.attempts).isEqualTo(0)
    assertThat(step.row!!.copyFile).isNull()
    assertThat(step.effects).containsExactly(Effect.DeleteFiles, Effect.ReleaseSource)
  }

  @Test
  fun sendingTransientFailureWaitsWithTheNextAttemptFromTheSchedule() {
    val step = on(link(RequestState.SENDING).copy(attempts = 1), QueueEvent.Transient("cannot_check_link", "busy"))!!

    assertThat(state(step)).isEqualTo("WAITING")
    assertThat(step.row!!.attempts).isEqualTo(2)
    assertThat(step.row!!.nextAttemptAt).isEqualTo(now + 60_000)
    assertThat(step.row!!.lastErrorCode).isEqualTo("cannot_check_link")
  }

  @Test
  fun sendingRefusedNeedsAttention() {
    listOf(Codes.REFUSED_ADDRESS, Codes.TOO_LARGE, Codes.UNSUPPORTED_TYPE, Codes.INVALID_URL).forEach { code ->
      val step = on(link(RequestState.SENDING), QueueEvent.Refused(code, "why"))!!
      assertThat(state(step)).isEqualTo("NEEDS_ATTENTION")
      assertThat(step.row!!.lastErrorCode).isEqualTo(code)
      assertThat(step.row!!.jobId).isNull()
      assertThat(step.effects).containsExactly(Effect.NotifyAttention)
    }
  }

  @Test
  fun aQueueWidePauseLeavesTheRequestWaitingAndCountsNothing() {
    val step = on(link(RequestState.SENDING).copy(attempts = 1), QueueEvent.Blocked)!!

    assertThat(state(step)).isEqualTo("WAITING")
    assertThat(step.row!!.attempts).isEqualTo(1)
    assertThat(step.row!!.lastErrorCode).isNull()
  }

  @Test
  fun tooLargeVideoWithItsCopyTryAudioOnlyExtractsFromTheCopyAndSendsNoRerun() {
    val refused = file(RequestState.NEEDS_ATTENTION).copy(lastErrorCode = Codes.TOO_LARGE, copyComplete = true, copyFile = "requests/r/source.mp4")

    val step = on(refused, QueueEvent.TryAudioOnly, MachineContext(now, isVideo = true))!!

    assertThat(state(step)).isEqualTo("PREPARING")
    assertThat(step.row!!.audioOnly).isTrue()
    assertThat(step.row!!.copyFile).isEqualTo("requests/r/source.mp4")
    assertThat(step.effects).containsExactly(Effect.StartIntake(fromKeptCopy = true))
    assertThat(step.effects.filterIsInstance<Effect.Enqueue>()).isEmpty()
  }

  @Test
  fun sendingStopSendingIsHeldWaiting() {
    val step = on(file(RequestState.SENDING), QueueEvent.StopSending)!!

    assertThat(state(step)).isEqualTo("WAITING")
    assertThat(step.row!!.held).isTrue()
    assertThat(step.effects).containsExactly(Effect.AbortUpload)
  }

  @Test
  fun theListingMovesAcceptedRequestsThroughLineAnalyzingAndReady() {
    val sent = link(RequestState.SENT).copy(jobId = JOB)
    val inLine = on(sent, job(RemoteStatus.QUEUED))!!.row!!
    assertThat(inLine.state).isEqualTo("IN_LINE")
    assertThat(inLine.inLineAt).isEqualTo(now)
    val analyzing = on(inLine, job(RemoteStatus.RUNNING))!!.row!!
    assertThat(analyzing.state).isEqualTo("ANALYZING")
    val ready = on(analyzing, job(RemoteStatus.DONE, title = "A walk in the park"))!!.row!!
    assertThat(ready.state).isEqualTo("READY_ON_COMPUTER")
    assertThat(ready.titleHint).isEqualTo("A walk in the park")
    val getting = on(ready, QueueEvent.DownloadStarted)!!.row!!
    assertThat(getting.state).isEqualTo("GETTING_ON_PHONE")
    val onPhone = on(getting, QueueEvent.OnPhone)!!
    assertThat(state(onPhone)).isEqualTo("ON_PHONE")
    assertThat(onPhone.effects).containsExactly(Effect.NotifyReady)
    // "Ready to watch" once.
    assertThat(on(onPhone.row!!, QueueEvent.OnPhone)).isNull()
  }

  @Test
  fun anInterruptedJobIsRerunOnceAutomatically() {
    val analyzing = link(RequestState.ANALYZING).copy(jobId = JOB)

    val first = on(analyzing, job(RemoteStatus.ERROR, code = Codes.INTERRUPTED))!!
    assertThat(state(first)).isEqualTo("ANALYZING")
    assertThat(first.row!!.autoRerunUsed).isTrue()
    assertThat(first.effects).containsExactly(Effect.Enqueue(OpKind.RERUN, JOB), Effect.Trigger)

    val second = on(first.row!!, job(RemoteStatus.ERROR, code = Codes.INTERRUPTED))!!
    assertThat(state(second)).isEqualTo("NEEDS_ATTENTION")
    assertThat(second.row!!.lastErrorCode).isEqualTo(Codes.INTERRUPTED)
    assertThat(second.effects).containsExactly(Effect.NotifyAttention)
  }

  @Test
  fun anyOtherJobErrorNeedsAttentionAndNotifiesOnce() {
    val step = on(link(RequestState.IN_LINE).copy(jobId = JOB), job(RemoteStatus.ERROR, code = Codes.TOO_LONG, message = "too long"))!!

    assertThat(state(step)).isEqualTo("NEEDS_ATTENTION")
    assertThat(step.row!!.lastErrorCode).isEqualTo(Codes.TOO_LONG)
    assertThat(step.effects).containsExactly(Effect.NotifyAttention)
    assertThat(on(step.row!!, job(RemoteStatus.ERROR, code = Codes.TOO_LONG, message = "too long"))).isNull()
  }

  @Test
  fun aCancelledJobIsCancelled() {
    val step = on(link(RequestState.ANALYZING).copy(jobId = JOB), job(RemoteStatus.CANCELLED))!!

    assertThat(state(step)).isEqualTo("CANCELLED")
    assertThat(step.effects).containsExactly(Effect.DropOps(setOf(OpKind.CANCEL)))
  }

  @Test
  fun cancelWhileConnectedSendsAtOnceAndWhileNotConnectedWaits() {
    val analyzing = link(RequestState.ANALYZING).copy(jobId = JOB)

    val connected = on(analyzing, QueueEvent.Cancel(connected = true))!!
    assertThat(state(connected)).isEqualTo("ANALYZING")
    assertThat(connected.effects).containsExactly(Effect.Enqueue(OpKind.CANCEL, JOB), Effect.Trigger)

    val offline = on(analyzing, QueueEvent.Cancel(connected = false))!!
    assertThat(state(offline)).isEqualTo("CANCEL_WAITING")
    assertThat(offline.effects).containsExactly(Effect.Enqueue(OpKind.CANCEL, JOB))
    // Not twice.
    assertThat(on(analyzing, QueueEvent.Cancel(connected = true), MachineContext(now, cancelPending = true))).isNull()
  }

  @Test
  fun undoOfAWaitingCancelRestoresThePreviousStateAndSaysSoOnce() {
    listOf(
      link(RequestState.CANCEL_WAITING).copy(jobId = JOB, sentAt = 1) to "SENT",
      link(RequestState.CANCEL_WAITING).copy(jobId = JOB, sentAt = 1, inLineAt = 2) to "IN_LINE",
      link(RequestState.CANCEL_WAITING).copy(jobId = JOB, sentAt = 1, inLineAt = 2, analyzingAt = 3) to "ANALYZING",
    ).forEach { (row, previous) ->
      val step = on(row, QueueEvent.UndoCancel)!!
      assertThat(state(step)).isEqualTo(previous)
      assertThat(step.effects).containsExactly(
        Effect.DropOps(setOf(OpKind.CANCEL)),
        Effect.Message(UserMessageKind.CANCEL_UNDONE),
      )
    }
  }

  @Test
  fun aCancelThatArrivesTooLateKeepsTheClipAndSaysSo() {
    val step = on(link(RequestState.CANCEL_WAITING).copy(jobId = JOB), QueueEvent.TooLateToCancel)!!

    assertThat(state(step)).isEqualTo("READY_ON_COMPUTER")
    assertThat(step.effects).containsExactly(Effect.Message(UserMessageKind.TOO_LATE_TO_CANCEL))
  }

  @Test
  fun tryAgainAndTryAudioOnlyOfALinkQueueAReRun() {
    val failed = link(RequestState.NEEDS_ATTENTION).copy(jobId = JOB, lastErrorCode = Codes.DOWNLOAD_FORBIDDEN)

    assertThat(on(failed, QueueEvent.TryAgain)!!.effects).containsExactly(Effect.Enqueue(OpKind.RERUN, JOB), Effect.Trigger)
    assertThat(on(failed, QueueEvent.TryAudioOnly)!!.effects)
      .containsExactly(Effect.Enqueue(OpKind.RERUN, JOB, audioOnly = true), Effect.Trigger)
    val cancelled = link(RequestState.CANCELLED).copy(jobId = JOB, lastErrorCode = Codes.CANCELLED)
    assertThat(on(cancelled, QueueEvent.TryAgain)!!.effects).containsExactly(Effect.Enqueue(OpKind.RERUN, JOB), Effect.Trigger)
  }

  @Test
  fun aFileIsRerunWithItsOwnOptionsNeverAsAudioOnly() {
    val failed = file(RequestState.NEEDS_ATTENTION).copy(jobId = JOB, lastErrorCode = Codes.NO_SPACE)

    assertThat(on(failed, QueueEvent.TryAgain)!!.effects).containsExactly(Effect.Enqueue(OpKind.RERUN, JOB), Effect.Trigger)
    assertThat(on(failed, QueueEvent.TryAudioOnly, MachineContext(now, isVideo = true))).isNull()
  }

  @Test
  fun anAcceptedReRunIsSentWithTheNewJobInTheSameRow() {
    val failed = link(RequestState.NEEDS_ATTENTION).copy(jobId = JOB, lastErrorCode = Codes.DOWNLOAD_FORBIDDEN, notifiedAttention = true)

    val step = on(failed, QueueEvent.RerunAccepted(NEW_JOB, audioOnly = true))!!

    assertThat(state(step)).isEqualTo("SENT")
    assertThat(step.row!!.id).isEqualTo(failed.id)
    assertThat(step.row!!.jobId).isEqualTo(NEW_JOB)
    assertThat(step.row!!.audioOnly).isTrue()
    assertThat(step.row!!.rerunCount).isEqualTo(1)
    assertThat(step.row!!.notifiedAttention).isFalse()
  }

  @Test
  fun aReRunRefusedBecauseTheFileIsGoneNeedsAttention() {
    val step = on(file(RequestState.NEEDS_ATTENTION).copy(jobId = JOB, lastErrorCode = Codes.NO_SPACE), QueueEvent.RerunRefused(Codes.SOURCE_GONE, null))!!

    assertThat(state(step)).isEqualTo("NEEDS_ATTENTION")
    assertThat(step.row!!.lastErrorCode).isEqualTo(Codes.SOURCE_GONE)
  }

  @Test
  fun removeBeforeSendingDeletesTheRowAndTheCopy() {
    listOf(RequestState.PREPARING, RequestState.WAITING, RequestState.COPY_FAILED, RequestState.COPY_MISSING).forEach { state ->
      val step = on(file(state), QueueEvent.Remove)!!
      assertThat(step.row).isNull()
      assertThat(step.effects).containsExactly(Effect.StopIntake, Effect.DeleteFiles, Effect.ReleaseSource)
    }
  }

  @Test
  fun removeWhileSendingAbortsTheUpload() {
    val step = on(file(RequestState.SENDING), QueueEvent.Remove)!!

    assertThat(step.row).isNull()
    assertThat(step.effects).containsExactly(
      Effect.AbortUpload,
      Effect.ForgetUploadSession,
      Effect.DeleteFiles,
      Effect.ReleaseSource,
    )
  }

  @Test
  fun removeOfAFailedOrCancelledJobKeepsARemoveJobOperation() {
    listOf(RequestState.NEEDS_ATTENTION, RequestState.CANCELLED).forEach { state ->
      val step = on(link(state).copy(jobId = JOB), QueueEvent.Remove)!!
      assertThat(step.row).isNull()
      assertThat(step.effects).contains(Effect.Enqueue(OpKind.REMOVE_JOB, JOB))
      assertThat(step.effects).contains(Effect.DropOps(setOf(OpKind.RERUN, OpKind.CANCEL)))
    }
    // Refused at sending: no job to remove on the computer.
    val atSending = on(link(RequestState.NEEDS_ATTENTION).copy(lastErrorCode = Codes.REFUSED_ADDRESS), QueueEvent.Remove)!!
    assertThat(atSending.effects.filterIsInstance<Effect.Enqueue>()).isEmpty()
  }

  @Test
  fun nothingElseCanBeRemovedOrCancelled() {
    assertThat(on(link(RequestState.ANALYZING).copy(jobId = JOB), QueueEvent.Remove)).isNull()
    assertThat(on(link(RequestState.ON_PHONE).copy(jobId = JOB), QueueEvent.Remove)).isNull()
    assertThat(on(link(RequestState.WAITING), QueueEvent.Cancel(connected = true))).isNull()
    assertThat(on(link(RequestState.READY_ON_COMPUTER).copy(jobId = JOB), QueueEvent.Cancel(connected = true))).isNull()
  }

  @Test
  fun aJobDeletedOnTheComputerTakesItsRequest() {
    val step = on(link(RequestState.ON_PHONE).copy(jobId = JOB), QueueEvent.JobGone)!!

    assertThat(step.row).isNull()
    assertThat(on(link(RequestState.WAITING), QueueEvent.JobGone)).isNull()
  }

  @Test
  fun everyTransitionStampsItsTime() {
    val step = on(link(RequestState.WAITING).copy(updatedAt = 1), QueueEvent.Pick)!!

    assertThat(step.row!!.updatedAt).isEqualTo(now)
  }

  private fun job(status: RemoteStatus, code: String? = null, message: String? = null, title: String? = null) =
    QueueEvent.Job(status, code, message, title)

  private fun link(state: RequestState) = RequestEntity(
    id = "00000000-0000-4000-8000-000000000001",
    seq = 1,
    kind = "LINK",
    state = state.name,
    createdAt = 1,
    updatedAt = 1,
    linkSent = "https://youtu.be/abc123def45",
  )

  private fun file(state: RequestState) = RequestEntity(
    id = "00000000-0000-4000-8000-000000000002",
    seq = 2,
    kind = "FILE",
    state = state.name,
    createdAt = 1,
    updatedAt = 1,
    fileName = "VID_0001.mp4",
    fileMime = "video/mp4",
  )

  private companion object {
    const val JOB = "20261011-101010-abcd"
    const val NEW_JOB = "20261011-111111-beef"
    const val SHA = "4f1d00000000000000000000000000000000000000000000000000000000abcd"
  }
}
