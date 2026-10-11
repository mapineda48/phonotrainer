package com.mapineda48.phonotrainer.core.requests.send

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.requests.store.ActiveUploads
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestTitles
import com.mapineda48.phonotrainer.core.requests.store.StoppedByLearner
import com.mapineda48.phonotrainer.core.requests.rules.BackgroundWindow
import com.mapineda48.phonotrainer.core.requests.rules.Codes
import com.mapineda48.phonotrainer.core.requests.rules.MediaTypes
import com.mapineda48.phonotrainer.core.requests.rules.Outcome
import com.mapineda48.phonotrainer.core.requests.rules.Outcomes
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.stored
import java.io.File
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Waits between two tries of a busy upload; tests move the fake computer's clock instead of sleeping. */
internal fun interface UploadPause {
  suspend fun pause(duration: Duration)

  companion object {
    val DELAY: UploadPause = UploadPause { delay(it.toMillis()) }
  }
}

/** The foreground side of an upload run (architecture 7.2): the "Transfers" notification of a visible transfer. */
internal interface UploadForeground {
  /** Whether the app is in the foreground now. */
  fun appVisible(): Boolean

  /** Runs the work as a visible transfer; false when Android does not allow it now (the app left the foreground). */
  suspend fun promote(progress: TransferProgress): Boolean

  /** New progress for the visible transfer (at most about once a second). */
  suspend fun progress(progress: TransferProgress)
}

/** How a run of the uploader ended. */
internal enum class UploadRun {
  /** Nothing more can be sent now (nothing waits, or what waits needs the app open). */
  IDLE,

  /** The computer is not reachable, the queue is paused, or uploads are paused (low space): try at the next pass. */
  STOPPED,
}

/**
 * Sends waiting files (architecture 8.5, v1-core): one at a time in creation order, each declared with its name, size,
 * SHA-256 and options (`POST /api/phone/uploads`), then sent whole (`PUT`) under its idempotency key; an interrupted
 * upload restarts from zero with the same key and the copy stays until the computer accepted it. In the background a
 * file starts only when it fits one background run ([BackgroundWindow]); a larger file waits for the app, never
 * failed, and the files and links behind it go on. `409 upload_busy` is waited out (the computer lets a new body
 * replace a silent one after 10 s, "newest wins").
 */
@Singleton
internal class Uploader @Inject constructor(
  private val core: QueueCore,
  private val live: QueueLive,
  private val sender: QueueSender,
  private val client: ComputerClient,
  private val status: ComputerStatusRepository,
  private val pairing: PairingStore,
  private val active: ActiveUploads,
  private val startup: StartupGate,
  private val pause: UploadPause,
  private val log: PhonoLog,
) {
  suspend fun run(foreground: UploadForeground): UploadRun {
    startup.awaitDone()
    val tried = HashSet<String>()
    while (true) {
      val computer = connectedComputer() ?: return UploadRun.STOPPED
      if (computer.lowOnSpace) return UploadRun.STOPPED
      val throughput = runCatching { pairing.current()?.uploadThroughput }.getOrNull()
      var chosen: RequestEntity? = null
      for (row in core.requests.dueWaiting(RequestKind.FILE.stored(), core.now())) {
        if (row.id in tried) continue
        val bytes = row.fileBytes ?: row.copyBytes ?: 0
        if (BackgroundWindow.fits(bytes, throughput) || (foreground.appVisible() && foreground.promote(progress(row, 0)))) {
          chosen = row
          break
        }
        // Too large for one background run: it waits for the app (needsAppOpen), and the next file may go.
      }
      val row = chosen ?: return UploadRun.IDLE
      tried += row.id
      when (upload(row, foreground)) {
        Next.CONTINUE -> Unit
        Next.STOP -> return UploadRun.STOPPED
      }
    }
  }

  private enum class Next { CONTINUE, STOP }

  private suspend fun upload(picked: RequestEntity, foreground: UploadForeground): Next {
    val id = RequestId(picked.id)
    val row = core.apply(id, QueueEvent.Pick)?.after ?: return Next.CONTINUE
    val copy = core.files.resolve(id, row.copyFile)?.takeIf { it.isFile }
    if (copy == null) {
      core.apply(id, QueueEvent.CopyMissing)
      return Next.CONTINUE
    }
    val bytes = copy.length()
    val limit = runCatching { pairing.current()?.capabilities?.maxUploadBytes }.getOrNull()?.takeIf { it > 0 }
    if (limit != null && bytes > limit) {
      // Refused early, before any byte is sent (spec 7.7): "Too large for your computer's limit".
      core.apply(id, QueueEvent.Refused(Codes.TOO_LARGE, null))
      return Next.CONTINUE
    }
    val sha256 = row.fileSha256 ?: return Next.CONTINUE.also { core.apply(id, QueueEvent.CopyMissing) }
    val result = try {
      coroutineScope {
        val sending = async { send(row, copy, bytes, sha256) }
        active.register(id, sending)
        // The visible transfer's line, at most about once a second (Android drops faster updates).
        val ticker = launch {
          while (isActive) {
            delay(PROGRESS_EVERY_MS)
            live.sent.value[id.value]?.let { moved -> runCatching { foreground.progress(progress(row, moved.bytes)) } }
          }
        }
        try {
          sending.await()
        } finally {
          ticker.cancel()
          active.unregister(id, sending)
        }
      }
    } catch (stopped: CancellationException) {
      live.sendingDone(id)
      if (stopped is StoppedByLearner || currentCoroutineContext().isActive) {
        // "Stop sending" or "Remove": the learner already changed the row.
        return Next.CONTINUE
      }
      // The worker itself was stopped: back to waiting, nothing counted; the next run restarts it from zero.
      withContext(NonCancellable) { core.apply(id, QueueEvent.Blocked) }
      throw stopped
    }
    live.sendingDone(id)
    return when (result) {
      is Sent.Accepted -> {
        sender.accepted(id, result.job)
        Next.CONTINUE
      }
      is Sent.Failed -> failed(id, row, result.outcome)
    }
  }

  private sealed interface Sent {
    data class Accepted(val job: RemoteJob) : Sent

    data class Failed(val outcome: Outcome) : Sent
  }

  private suspend fun send(row: RequestEntity, copy: File, bytes: Long, sha256: String): Sent {
    val id = RequestId(row.id)
    val extension = copy.name.substringAfterLast('.', "")
    val declaration = UploadDeclaration(
      clientRequestId = id,
      fileName = MediaTypes.uploadName(row.fileName, extension),
      bytes = bytes,
      sha256 = sha256,
      separateDialogue = row.separateDialogue,
    )
    var conflictCleared = false
    var declaredAgain = false
    var busy = 0
    declare@ while (true) {
      when (val declared = client.openUpload(declaration)) {
        is ApiResult.Ok -> when (val state = declared.value) {
          is UploadState.Done -> return Sent.Accepted(state.job)
          is UploadState.Open -> Unit
        }
        is ApiResult.Refused -> when {
          // A stale session of this key declared another file (its audio was extracted since): abandon it, once.
          declared.code == Codes.KEY_CONFLICT && !conflictCleared -> {
            conflictCleared = true
            client.deleteUpload(id)
            continue@declare
          }
          declared.code == Codes.UPLOAD_BUSY && busy++ < BUSY_TRIES -> {
            pause.pause(BUSY_WAIT)
            continue@declare
          }
          else -> return Sent.Failed(Outcomes.of(declared))
        }
        is ApiResult.Failed -> return Sent.Failed(Outcomes.of(declared))
      }
      while (true) {
        val started = System.nanoTime()
        live.sending(id, 0, bytes)
        val put = client.putUpload(id, copy) { sent -> live.sending(id, sent, bytes) }
        when {
          put is ApiResult.Ok -> {
            recordThroughput(bytes, System.nanoTime() - started)
            return Sent.Accepted(put.value.value)
          }
          put is ApiResult.Refused && put.code == Codes.UNKNOWN_UPLOAD && !declaredAgain -> {
            // The session expired on the computer (48 h): declare again with the same key.
            declaredAgain = true
            continue@declare
          }
          put is ApiResult.Refused && put.code == Codes.UPLOAD_BUSY && busy++ < BUSY_TRIES -> pause.pause(BUSY_WAIT)
          else -> return Sent.Failed(Outcomes.of(put))
        }
      }
    }
  }

  private suspend fun failed(id: RequestId, row: RequestEntity, outcome: Outcome): Next = when (outcome) {
    Outcome.Unreachable -> {
      core.apply(id, QueueEvent.Transient(null, null))
      Next.STOP
    }
    is Outcome.Transient ->
      if (outcome.code == Codes.CHECKSUM_MISMATCH && row.attempts + 1 >= DAMAGED_AFTER) {
        // Damaged on the way three times (server-contract 8.1): the learner decides.
        core.apply(id, QueueEvent.Refused(Codes.CHECKSUM_MISMATCH, outcome.message))
        Next.CONTINUE
      } else {
        core.apply(id, QueueEvent.Transient(outcome.code, outcome.message))
        Next.CONTINUE
      }
    is Outcome.Defect -> {
      log.w("requests.upload_defect") { outcome.code }
      core.apply(id, QueueEvent.Transient(outcome.code, outcome.message))
      Next.CONTINUE
    }
    is Outcome.Blocking, Outcome.UploadsBlocked -> {
      core.apply(id, QueueEvent.Blocked)
      Next.STOP
    }
    is Outcome.Permanent -> {
      core.apply(id, QueueEvent.Refused(outcome.code, outcome.message))
      Next.CONTINUE
    }
  }

  private suspend fun connectedComputer(): ComputerStatus.Connected? {
    (status.status.value as? ComputerStatus.Connected)?.let { return it }
    return runCatching { status.checkNow() }.getOrNull() as? ComputerStatus.Connected
  }

  private suspend fun recordThroughput(bytes: Long, nanos: Long) {
    if (bytes < MEASURE_MIN_BYTES || nanos < MEASURE_MIN_NANOS) return
    runCatching { pairing.recordUpload(bytes, Duration.ofNanos(nanos)) }
  }

  private fun progress(row: RequestEntity, sent: Long): TransferProgress = TransferProgress(
    title = RequestTitles.of(row, jobTitle = null, words = core.words),
    doneBytes = sent,
    totalBytes = row.fileBytes ?: row.copyBytes,
    items = 1,
  )

  private companion object {
    val BUSY_WAIT: Duration = Duration.ofSeconds(10)
    const val BUSY_TRIES = 6
    const val DAMAGED_AFTER = 3
    const val PROGRESS_EVERY_MS = 1_000L
    const val MEASURE_MIN_BYTES = 1_000_000L
    const val MEASURE_MIN_NANOS = 500_000_000L
  }
}
