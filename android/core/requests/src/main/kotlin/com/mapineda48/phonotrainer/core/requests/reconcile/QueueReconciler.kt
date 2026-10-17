package com.mapineda48.phonotrainer.core.requests.reconcile

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.rules.OpKind
import com.mapineda48.phonotrainer.core.requests.rules.OpState
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.stored
import com.mapineda48.phonotrainer.core.requests.rules.storedState
import com.mapineda48.phonotrainer.core.requests.send.asEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RequestReconciler] (architecture 7.1 step 4, 7.6, 8.4, 8.6): the computer's view of this phone's own jobs moves
 * their requests on. Jobs of the browser, the command line and other phones (`own: false`) never touch the queue
 * (8.7). A listing of another computer is ignored entirely (QT-11). Automatic re-runs after `interrupted` are queued
 * in the order the computer accepted the jobs (QT-5, QT-12), once per request.
 */
@Singleton
internal class QueueReconciler @Inject constructor(
  private val core: QueueCore,
  private val live: QueueLive,
  private val pairing: PairingStore,
  private val log: PhonoLog,
) : RequestReconciler {
  private val lock = Mutex()

  override suspend fun reconcile(library: RemoteLibrary) = lock.withLock {
    val paired = runCatching { pairing.current() }.getOrNull()
    if (paired != null && paired.serverId != library.serverId) {
      log.w("requests.listing_other_computer")
      return@withLock
    }
    val own = library.jobs.filter { it.own }
    live.listing(own)
    // In the order the computer accepted this phone's requests, so automatic re-runs keep it (QT-5, QT-12).
    own.mapNotNull { job -> rowOf(job)?.let { job to it } }
      .sortedWith(compareBy({ it.second.acceptedAt ?: Long.MAX_VALUE }, { it.second.seq }))
      .forEach { (job, _) -> apply(job, library.serverId.value) }
    // A finished clip whose study pack started to come: "Getting it on this phone".
    core.requests.inStates(listOf(RequestState.READY_ON_COMPUTER.stored())).forEach { row ->
      val job = row.jobId ?: return@forEach
      if (core.requests.studyPackTransfers(job) > 0) core.apply(RequestId(row.id), QueueEvent.DownloadStarted)
    }
  }

  override suspend fun onJob(job: RemoteJob) = lock.withLock {
    if (!job.own) return@withLock
    live.job(job)
    apply(job, runCatching { pairing.current()?.serverId?.value }.getOrNull())
  }

  override suspend fun onJobDeleted(id: JobId) = lock.withLock {
    live.jobGone(id.value)
    val row = core.requests.byJob(id.value) ?: return@withLock
    // A re-run replaces its old job: the old job's `deleted` event arrives with the new job (architecture 8.6).
    if (core.ops.pendingFor(row.id).any { it.kind == OpKind.RERUN.name && it.jobId == id.value }) return@withLock
    core.apply(RequestId(row.id), QueueEvent.JobGone)
  }

  override suspend fun onClipOnPhone(id: JobId) = lock.withLock {
    val row = core.requests.byJob(id.value) ?: return@withLock
    core.apply(RequestId(row.id), QueueEvent.OnPhone)
    Unit
  }

  private suspend fun apply(job: RemoteJob, serverId: String?) {
    val row = rowOf(job) ?: return
    val id = RequestId(row.id)
    if (row.jobId != job.id.value) {
      when {
        // The computer has the job of this request's key, but its answer was lost (QT-3): the phone shows that job.
        job.clientRequestId?.value == row.id &&
          storedState(row.state).let { it == RequestState.WAITING || it == RequestState.SENDING } ->
          core.apply(id, QueueEvent.Accepted(job.id.value, serverId))
        // A re-run whose answer was lost: its key is the operation's id (architecture 8.6).
        job.clientRequestId != null -> {
          val op = core.ops.rerunWithKey(job.clientRequestId!!.value)?.takeIf { it.requestId == row.id } ?: return
          if (op.state == OpState.PENDING.name) core.finish(op, OpState.DONE)
          core.apply(id, QueueEvent.RerunAccepted(job.id.value, op.audioOnly)) ?: return
        }
        else -> return
      }
    }
    core.apply(id, job.asEvent())
  }

  /** The request a job belongs to: by its job id, its idempotency key, or the key of one of its re-runs. */
  private suspend fun rowOf(job: RemoteJob): RequestEntity? {
    core.requests.byJob(job.id.value)?.let { return it }
    val key = job.clientRequestId?.value ?: return null
    core.requests.get(key)?.let { return it }
    val op = core.ops.rerunWithKey(key) ?: return null
    return op.requestId?.let { core.requests.get(it) }
  }
}
