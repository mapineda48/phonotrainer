package com.mapineda48.phonotrainer.core.requests.send

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.common.retry.RetrySchedule
import com.mapineda48.phonotrainer.core.database.library.entity.RequestOpEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.SendReport
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.requests.store.AppVisibility
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.rules.Codes
import com.mapineda48.phonotrainer.core.requests.rules.OpKind
import com.mapineda48.phonotrainer.core.requests.rules.OpState
import com.mapineda48.phonotrainer.core.requests.rules.Outcome
import com.mapineda48.phonotrainer.core.requests.rules.Outcomes
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.stored
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * [RequestSender] (architecture 7.1 steps 2, 3 and 7; 8.5, 8.6): the outbox first, oldest first, then the waiting
 * links in creation order, each with its idempotency key, so a lost answer never creates a second analysis (QT-3);
 * uploads run in their own work. A pass that meets a queue-wide pause stops and reports it (`blocked`), marking
 * nothing failed (QT-8); an unreachable computer ends the pass with the attempt counted.
 */
@Singleton
internal class QueueSender @Inject constructor(
  private val core: QueueCore,
  private val live: QueueLive,
  private val client: ComputerClient,
  private val status: ComputerStatusRepository,
  private val pairing: PairingStore,
  private val trigger: SyncTrigger,
  private val work: RequestWork,
  private val visibility: AppVisibility,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) : RequestSender {
  override suspend fun sendOperations(): SendReport = core.sendLock.withLock {
    if (!connected()) return@withLock idle()
    val report = Tally()
    for (op in core.ops.pending()) {
      val now = core.now()
      if ((op.nextAttemptAt ?: 0) > now) continue
      // "Undo" of a cancel runs under the same lock: an operation still pending here is still wanted.
      val current = core.ops.get(op.id)?.takeIf { it.state == OpState.PENDING.name } ?: continue
      val stop = when (OpKind.entries.firstOrNull { it.name == current.kind }) {
        OpKind.CANCEL -> cancel(current, report)
        OpKind.REMOVE_JOB -> removeJob(current, report)
        OpKind.RERUN -> rerun(current, report)
        null -> {
          core.finish(current, OpState.FAILED, "unknown_kind")
          false
        }
      }
      if (stop) break
    }
    report.build()
  }

  override suspend fun sendLinks(): SendReport = core.sendLock.withLock {
    if (!connected()) return@withLock idle()
    val report = Tally()
    for (row in core.requests.dueWaiting(RequestKind.LINK.stored(), core.now())) {
      val id = RequestId(row.id)
      // Re-read and marked SENDING in one transaction: a request removed a moment ago is never sent (QT-6).
      val picked = core.apply(id, QueueEvent.Pick)?.after ?: continue
      val link = picked.linkSent ?: picked.linkShared
      if (link == null) {
        core.apply(id, QueueEvent.Refused(Codes.INVALID_URL, null))
        report.refused++
        continue
      }
      val result = client.createLink(LinkCreate(id, link, picked.audioOnly, separateDialogue = null))
      if (result is ApiResult.Ok) {
        accepted(id, result.value.value)
        report.sent++
        continue
      }
      if (failed(id, Outcomes.of(result), report)) break
    }
    report.build()
  }

  override fun scheduleUploads() {
    scope.launch {
      val waiting = runCatching { core.requests.dueWaiting(RequestKind.FILE.stored(), Long.MAX_VALUE) }.getOrDefault(emptyList())
      if (waiting.isNotEmpty()) work.scheduleUploads(userVisible = visibility.visible())
    }
  }

  /** A request or an operation was stored: a pass sends it (at once when connected in the foreground, 7.2). */
  fun requestCreated() = trigger.request(SyncReason.REQUEST_CREATED)

  /** The computer accepted request [id] as [job]: one row, `SENT`, then the job's own state (architecture 8.4). */
  suspend fun accepted(id: RequestId, job: RemoteJob) {
    live.job(job)
    val server = runCatching { pairing.current()?.serverId?.value }.getOrNull()
    core.apply(id, QueueEvent.Accepted(job.id.value, server))
    core.apply(id, job.asEvent())
  }

  /**
   * Applies a failed attempt of request [id]; true when the pass must stop (the computer is unreachable, or the queue
   * is paused).
   */
  private suspend fun failed(id: RequestId, outcome: Outcome, report: Tally): Boolean = when (outcome) {
    Outcome.Unreachable -> {
      core.apply(id, QueueEvent.Transient(null, null))
      report.transient++
      true
    }
    is Outcome.Transient -> {
      core.apply(id, QueueEvent.Transient(outcome.code, outcome.message))
      report.transient++
      false
    }
    is Outcome.Defect -> {
      log.w("requests.send_defect") { outcome.code }
      core.apply(id, QueueEvent.Transient(outcome.code, outcome.message))
      report.transient++
      false
    }
    is Outcome.Blocking -> {
      core.apply(id, QueueEvent.Blocked)
      report.blocked = true
      true
    }
    Outcome.UploadsBlocked -> {
      core.apply(id, QueueEvent.Transient(Codes.NO_SPACE, null))
      report.transient++
      false
    }
    is Outcome.Permanent -> {
      core.apply(id, QueueEvent.Refused(outcome.code, outcome.message))
      report.refused++
      false
    }
  }

  private suspend fun cancel(op: RequestOpEntity, report: Tally): Boolean {
    val job = op.jobId ?: return finished(op, OpState.FAILED, "no_job")
    val request = op.requestId?.let(::RequestId)
    return when (val result = client.cancel(JobId(job))) {
      is ApiResult.Ok -> {
        core.finish(op, OpState.DONE)
        report.sent++
        live.job(result.value)
        if (request != null) {
          // The cancel reached the computer: it no longer waits on the phone; the job's state follows.
          core.apply(request, result.value.asEvent())
          core.apply(request, QueueEvent.CancelLost)
        }
        false
      }
      is ApiResult.Refused -> when (result.code) {
        Codes.ALREADY_FINISHED -> {
          core.finish(op, OpState.DONE, result.code)
          report.sent++
          if (request != null) {
            result.job?.let { live.job(it) }
            core.apply(request, QueueEvent.TooLateToCancel)
          }
          false
        }
        Codes.UNKNOWN_JOB -> {
          core.finish(op, OpState.DONE, result.code)
          request?.let { core.apply(it, QueueEvent.JobGone) }
          false
        }
        else -> opFailed(op, Outcomes.of(result), report) { request?.let { core.apply(it, QueueEvent.CancelLost) } }
      }
      is ApiResult.Failed -> opFailed(op, Outcomes.of(result), report) { request?.let { core.apply(it, QueueEvent.CancelLost) } }
    }
  }

  private suspend fun removeJob(op: RequestOpEntity, report: Tally): Boolean {
    val job = op.jobId ?: return finished(op, OpState.FAILED, "no_job")
    return when (val result = client.deleteJob(JobId(job))) {
      is ApiResult.Ok -> {
        core.finish(op, OpState.DONE)
        live.jobGone(job)
        report.sent++
        false
      }
      is ApiResult.Refused -> if (result.code == Codes.UNKNOWN_JOB) {
        core.finish(op, OpState.DONE, result.code)
        live.jobGone(job)
        false
      } else {
        opFailed(op, Outcomes.of(result), report) { }
      }
      is ApiResult.Failed -> opFailed(op, Outcomes.of(result), report) { }
    }
  }

  private suspend fun rerun(op: RequestOpEntity, report: Tally): Boolean {
    val job = op.jobId ?: return finished(op, OpState.FAILED, "no_job")
    val request = op.requestId?.let(::RequestId)
    return when (val result = client.rerun(JobId(job), RerunBody(RequestId(op.id), op.audioOnly))) {
      is ApiResult.Ok -> {
        val created = result.value.value
        core.finish(op, OpState.DONE)
        live.jobGone(job)
        live.job(created)
        report.sent++
        if (request != null) {
          core.apply(request, QueueEvent.RerunAccepted(created.id.value, op.audioOnly))
          core.apply(request, created.asEvent())
        }
        false
      }
      is ApiResult.Refused -> when (val outcome = Outcomes.of(result)) {
        is Outcome.Permanent -> {
          core.finish(op, OpState.DONE, outcome.code)
          request?.let { core.apply(it, QueueEvent.RerunRefused(outcome.code, outcome.message)) }
          report.refused++
          false
        }
        else -> if (result.code == Codes.UNKNOWN_JOB) {
          core.finish(op, OpState.DONE, result.code)
          request?.let { core.apply(it, QueueEvent.JobGone) }
          false
        } else {
          opFailed(op, outcome, report) { }
        }
      }
      is ApiResult.Failed -> opFailed(op, Outcomes.of(result), report) { }
    }
  }

  /**
   * An operation that did not go through: retried on the schedule while transient; given up (logged) for a defect,
   * where [onGivenUp] puts its request back as it was.
   */
  private suspend fun opFailed(op: RequestOpEntity, outcome: Outcome, report: Tally, onGivenUp: suspend () -> Unit): Boolean {
    fun retry(code: String?) = op.copy(
      attempts = op.attempts + 1,
      nextAttemptAt = core.now() + RetrySchedule.delayAfter(op.attempts + 1).toMillis(),
      lastErrorCode = code,
    )
    return when (outcome) {
      Outcome.Unreachable -> {
        core.ops.update(retry(null))
        report.transient++
        true
      }
      is Outcome.Transient -> {
        core.ops.update(retry(outcome.code))
        report.transient++
        false
      }
      Outcome.UploadsBlocked -> {
        core.ops.update(retry(Codes.NO_SPACE))
        report.transient++
        false
      }
      is Outcome.Blocking -> {
        report.blocked = true
        true
      }
      is Outcome.Permanent, is Outcome.Defect -> {
        val code = (outcome as? Outcome.Permanent)?.code ?: (outcome as Outcome.Defect).code
        log.w("requests.operation_refused") { "${op.kind}: $code" }
        core.finish(op, OpState.FAILED, code)
        onGivenUp()
        report.refused++
        false
      }
    }
  }

  private suspend fun finished(op: RequestOpEntity, state: OpState, code: String?): Boolean {
    core.finish(op, state, code)
    return false
  }

  private fun connected(): Boolean = status.status.value is ComputerStatus.Connected

  private fun idle(): SendReport =
    SendReport(sent = 0, refused = 0, transient = 0, blocked = status.status.value.let { it.isPause() })

  private fun ComputerStatus.isPause(): Boolean = when (this) {
    ComputerStatus.ConnectAgain, ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld,
    is ComputerStatus.DifferentComputer -> true
    else -> false
  }

  private class Tally {
    var sent = 0
    var refused = 0
    var transient = 0
    var blocked = false

    fun build() = SendReport(sent = sent, refused = refused, transient = transient, blocked = blocked)
  }
}

/** A job as the queue's event. */
internal fun RemoteJob.asEvent(): QueueEvent.Job =
  QueueEvent.Job(status = status, errorCode = error?.code, errorMessage = error?.message, title = title)
