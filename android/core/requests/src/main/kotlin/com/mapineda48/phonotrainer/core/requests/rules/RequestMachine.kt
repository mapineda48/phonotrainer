package com.mapineda48.phonotrainer.core.requests.rules

import com.mapineda48.phonotrainer.core.common.retry.RetrySchedule
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus

/** What happens to a request (architecture 8.4): the learner, the intake, the sender and the computer's answers. */
internal sealed interface QueueEvent {
  /** The copy (or the extracted audio) is complete and hashed. [copyFile] is relative to `filesDir`. */
  data class CopyComplete(
    val copyFile: String,
    val sha256: String,
    val bytes: Long,
    val audioExtracted: Boolean,
    val wholeFileFallback: Boolean,
  ) : QueueEvent

  /** A copy code of architecture 8.3: no space (with the space needed), unreadable, interrupted. */
  data class CopyFailed(val code: String, val neededBytes: Long? = null) : QueueEvent

  /** The start-up check found the kept copy gone (F-20-AC9). */
  data object CopyMissing : QueueEvent

  /** "Stop adding this video?" → "Stop" (spec 7.7): the record and the partial copy go. */
  data object CancelPreparing : QueueEvent

  data object SendNow : QueueEvent

  /** The sender picks a waiting request, re-reading it in the same transaction (QT-6). */
  data object Pick : QueueEvent

  /** The computer answered with a job (201, or 200 for a known key). */
  data class Accepted(val jobId: String, val serverId: String?) : QueueEvent

  /** No answer, a timeout, a reset, a 5xx, a busy computer: the attempt counts. */
  data class Transient(val code: String?, val message: String?) : QueueEvent

  /** Refused with a reason (server-contract 8.1 "permanent"): "Needs your attention". */
  data class Refused(val code: String, val message: String?) : QueueEvent

  /** A queue-wide pause stopped the attempt (credential rejected, too old…): nothing is counted or failed (QT-8). */
  data object Blocked : QueueEvent

  /** "Stop sending" (D-46): back to waiting, held until "Send now". */
  data object StopSending : QueueEvent

  /** The job as a listing, the channel or an answer reports it. */
  data class Job(val status: RemoteStatus, val errorCode: String?, val errorMessage: String?, val title: String?) :
    QueueEvent

  /** The study pack started to come to this phone. */
  data object DownloadStarted : QueueEvent

  /** The study pack is verified on this phone. */
  data object OnPhone : QueueEvent

  data class Cancel(val connected: Boolean) : QueueEvent

  data object UndoCancel : QueueEvent

  /** The computer answered `already_finished` to a cancel (F-20-AC5). */
  data object TooLateToCancel : QueueEvent

  /** A cancel the computer refused as a defect: the request goes back to what it was, without a message. */
  data object CancelLost : QueueEvent

  data object TryAgain : QueueEvent

  data object TryAudioOnly : QueueEvent

  data object Remove : QueueEvent

  /** A re-run was accepted: the request keeps one row, with the new job (architecture 8.6). */
  data class RerunAccepted(val jobId: String, val audioOnly: Boolean?) : QueueEvent

  /** A re-run was refused with a reason (`source_gone`, `refused_address`…). */
  data class RerunRefused(val code: String, val message: String?) : QueueEvent

  /** The job no longer exists on the computer (a `deleted` event, `unknown_job`). */
  data object JobGone : QueueEvent
}

/** Facts a transition needs that are not in the row. */
internal data class MachineContext(
  val now: Long,
  val isVideo: Boolean = false,
  val readable: Boolean = false,
  val rerunPending: Boolean = false,
  val cancelPending: Boolean = false,
)

/** What a transition asks for besides the row itself. Database effects run in the same transaction. */
internal sealed interface Effect {
  /** A new outbox operation for this request's job (a new UUID; for a re-run, its idempotency key). */
  data class Enqueue(val kind: OpKind, val jobId: String, val audioOnly: Boolean? = null) : Effect

  /** The request's pending operations of [kinds] are dropped. */
  data class DropOps(val kinds: Set<OpKind>) : Effect

  /** The request's directory (`requests/<id>/`: the copy and any part) is deleted. */
  data object DeleteFiles : Effect

  /** A kept lasting read permission is released (architecture 8.3). */
  data object ReleaseSource : Effect

  /** The intake work of this request stops. */
  data object StopIntake : Effect

  /** A new intake: of the source again, or of the copy kept on the phone (architecture 8.4, "Try audio only"). */
  data class StartIntake(val fromKeptCopy: Boolean) : Effect

  /** The upload in progress stops. */
  data object AbortUpload : Effect

  /** The upload session on the computer is abandoned (best effort; it expires there anyway). */
  data object ForgetUploadSession : Effect

  /** A one-shot message with the request's title (D-47, F-20-AC5). */
  data class Message(val kind: UserMessageKind) : Effect

  /** "Needs attention", once per failure (F-23). */
  data object NotifyAttention : Effect

  /** "Ready to watch", once (F-23). */
  data object NotifyReady : Effect

  /** Something waits to be sent: ask for a pass. */
  data object Trigger : Effect
}

/** A transition's result: the new row (null when the row is deleted) and its effects. */
internal data class Step(val row: RequestEntity?, val effects: List<Effect> = emptyList())

/**
 * The state machine of architecture 8.4 as one pure function. It returns null when [QueueEvent] does not apply to the
 * row's state (a learner's tap on a stale row, an answer for a request that moved on), so callers never write a
 * transition the table does not have. Every result changes `updated_at`.
 */
internal object RequestMachine {
  private val ACCEPTED = setOf(
    RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING, RequestState.READY_ON_COMPUTER,
    RequestState.GETTING_ON_PHONE, RequestState.ON_PHONE, RequestState.NEEDS_ATTENTION, RequestState.CANCEL_WAITING,
    RequestState.CANCELLED,
  )
  private val ON_COMPUTER = setOf(RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING)
  private val LOCAL = setOf(
    RequestState.PREPARING, RequestState.WAITING, RequestState.COPY_FAILED, RequestState.COPY_MISSING,
  )
  private val READY = setOf(RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE, RequestState.ON_PHONE)

  /** Link codes whose job failure may pass as audio only (spec 8.6, D-49). */
  private val AUDIO_ONLY_RERUN = setOf(Codes.DOWNLOAD_FORBIDDEN, Codes.NO_SPACE)

  fun on(row: RequestEntity, event: QueueEvent, context: MachineContext): Step? {
    val state = storedState(row.state)
    val now = context.now
    val step = when (event) {
      is QueueEvent.CopyComplete -> copyComplete(row, state, event)
      is QueueEvent.CopyFailed -> copyFailed(row, state, event)
      QueueEvent.CopyMissing ->
        if (row.kind == RequestKind.FILE.stored() && (state == RequestState.WAITING || state == RequestState.SENDING)) {
          Step(
            row.with(RequestState.COPY_MISSING).copy(stateReason = CopyCodes.MISSING, copyComplete = false),
            listOf(Effect.DeleteFiles),
          )
        } else {
          null
        }
      QueueEvent.CancelPreparing ->
        if (state == RequestState.PREPARING) Step(null, listOf(Effect.StopIntake, Effect.DeleteFiles, Effect.ReleaseSource)) else null
      QueueEvent.SendNow ->
        if (state == RequestState.WAITING) Step(row.copy(held = false, nextAttemptAt = null), listOf(Effect.Trigger)) else null
      QueueEvent.Pick -> pick(row, state, now)
      is QueueEvent.Accepted ->
        if (state == RequestState.SENDING || state == RequestState.WAITING) accepted(row, event, now) else null
      is QueueEvent.Transient -> if (state == RequestState.SENDING) {
        val attempts = row.attempts + 1
        Step(
          row.with(RequestState.WAITING).copy(
            attempts = attempts,
            nextAttemptAt = now + RetrySchedule.delayAfter(attempts).toMillis(),
            lastErrorCode = event.code,
            lastErrorMessage = event.message,
          ),
        )
      } else {
        null
      }
      is QueueEvent.Refused -> if (state == RequestState.SENDING) {
        Step(
          row.with(RequestState.NEEDS_ATTENTION).copy(lastErrorCode = event.code, lastErrorMessage = event.message),
          listOf(Effect.NotifyAttention),
        )
      } else {
        null
      }
      QueueEvent.Blocked -> if (state == RequestState.SENDING) Step(row.with(RequestState.WAITING)) else null
      QueueEvent.StopSending ->
        if (state == RequestState.SENDING) Step(row.with(RequestState.WAITING).copy(held = true), listOf(Effect.AbortUpload)) else null
      is QueueEvent.Job -> if (state in ACCEPTED) job(row, state, event, now) else null
      QueueEvent.DownloadStarted ->
        if (state == RequestState.READY_ON_COMPUTER) Step(row.with(RequestState.GETTING_ON_PHONE)) else null
      QueueEvent.OnPhone -> if (state in ON_COMPUTER || state == RequestState.READY_ON_COMPUTER ||
        state == RequestState.GETTING_ON_PHONE
      ) {
        val notify = !row.notifiedReady
        Step(
          row.with(RequestState.ON_PHONE).copy(onPhoneAt = now, readyAt = row.readyAt ?: now, notifiedReady = true),
          if (notify) listOf(Effect.NotifyReady) else emptyList(),
        )
      } else {
        null
      }
      is QueueEvent.Cancel -> cancel(row, state, event, context)
      QueueEvent.UndoCancel -> if (state == RequestState.CANCEL_WAITING) {
        Step(
          row.with(stateBeforeCancel(row)),
          listOf(Effect.DropOps(setOf(OpKind.CANCEL)), Effect.Message(UserMessageKind.CANCEL_UNDONE)),
        )
      } else {
        null
      }
      QueueEvent.TooLateToCancel -> when (state) {
        RequestState.CANCEL_WAITING, in ON_COMPUTER -> Step(
          row.with(RequestState.READY_ON_COMPUTER).copy(readyAt = row.readyAt ?: now),
          listOf(Effect.Message(UserMessageKind.TOO_LATE_TO_CANCEL)),
        )
        in READY -> Step(row.copy(updatedAt = now), listOf(Effect.Message(UserMessageKind.TOO_LATE_TO_CANCEL)))
        else -> null
      }
      QueueEvent.CancelLost -> if (state == RequestState.CANCEL_WAITING) Step(row.with(stateBeforeCancel(row))) else null
      QueueEvent.TryAgain -> tryAgain(row, state)
      QueueEvent.TryAudioOnly -> tryAudioOnly(row, state, context)
      QueueEvent.Remove -> remove(row, state, context)
      is QueueEvent.RerunAccepted -> if (state in ACCEPTED) {
        Step(
          row.with(RequestState.SENT).copy(
            jobId = event.jobId,
            sentAt = now,
            inLineAt = null,
            analyzingAt = null,
            readyAt = null,
            onPhoneAt = null,
            rerunCount = row.rerunCount + 1,
            audioOnly = event.audioOnly ?: row.audioOnly,
            lastErrorCode = null,
            lastErrorMessage = null,
            notifiedAttention = false,
            notifiedReady = false,
          ),
        )
      } else {
        null
      }
      is QueueEvent.RerunRefused -> if (state in ACCEPTED) {
        Step(
          row.with(RequestState.NEEDS_ATTENTION).copy(
            lastErrorCode = event.code,
            lastErrorMessage = event.message,
            notifiedAttention = true,
          ),
          if (row.notifiedAttention) emptyList() else listOf(Effect.NotifyAttention),
        )
      } else {
        null
      }
      QueueEvent.JobGone -> if (row.jobId != null) {
        Step(null, listOf(Effect.DropOps(OpKind.entries.toSet()), Effect.DeleteFiles, Effect.ReleaseSource))
      } else {
        null
      }
    }
    return step?.let { if (it.row == null) it else it.copy(row = it.row.copy(updatedAt = now)) }
  }

  /** The state a cancel waiting for the computer returns to on "Undo" (D-47): the furthest milestone reached. */
  fun stateBeforeCancel(row: RequestEntity): RequestState = when {
    row.analyzingAt != null -> RequestState.ANALYZING
    row.inLineAt != null -> RequestState.IN_LINE
    else -> RequestState.SENT
  }

  private fun copyComplete(row: RequestEntity, state: RequestState, event: QueueEvent.CopyComplete): Step? {
    if (state != RequestState.PREPARING) return null
    return Step(
      row.with(RequestState.WAITING).copy(
        stateReason = null,
        copyFile = event.copyFile,
        fileSha256 = event.sha256,
        copyBytes = event.bytes,
        fileBytes = event.bytes,
        copyComplete = true,
        audioExtracted = event.audioExtracted,
        wholeFileFallback = event.wholeFileFallback,
        neededBytes = null,
        lastErrorCode = null,
        lastErrorMessage = null,
        attempts = 0,
        nextAttemptAt = null,
      ),
      listOf(Effect.ReleaseSource, Effect.Trigger),
    )
  }

  private fun copyFailed(row: RequestEntity, state: RequestState, event: QueueEvent.CopyFailed): Step? {
    if (state != RequestState.PREPARING) return null
    // A picked file keeps its permission while "Try audio only" may need it again (no space); after an unreadable or
    // interrupted copy it is released at once (architecture 8.3).
    val effects = buildList {
      add(Effect.DeleteFiles)
      if (event.code != CopyCodes.NO_SPACE) add(Effect.ReleaseSource)
    }
    return Step(
      row.with(RequestState.COPY_FAILED).copy(
        stateReason = event.code,
        neededBytes = event.neededBytes,
        copyComplete = false,
        copyFile = null,
        copyBytes = null,
      ),
      effects,
    )
  }

  private fun pick(row: RequestEntity, state: RequestState, now: Long): Step? {
    if (state != RequestState.WAITING || row.held) return null
    if (row.kind == RequestKind.FILE.stored() && !row.copyComplete) return null
    val due = row.nextAttemptAt?.let { it <= now } ?: true
    return if (due) Step(row.with(RequestState.SENDING)) else null
  }

  private fun accepted(row: RequestEntity, event: QueueEvent.Accepted, now: Long): Step = Step(
    row.with(RequestState.SENT).copy(
      jobId = event.jobId,
      acceptedAt = now,
      sentAt = now,
      targetServerId = event.serverId ?: row.targetServerId,
      held = false,
      attempts = 0,
      nextAttemptAt = null,
      lastErrorCode = null,
      lastErrorMessage = null,
      copyComplete = false,
      copyFile = null,
    ),
    // The copy is deleted as soon as the computer accepted the file (architecture 8.3).
    listOf(Effect.DeleteFiles, Effect.ReleaseSource),
  )

  private fun job(row: RequestEntity, state: RequestState, event: QueueEvent.Job, now: Long): Step? {
    val titled = event.title?.let(Titles::clean)?.takeIf { it.isNotEmpty() && it != row.titleHint }
      ?.let { row.copy(titleHint = it) } ?: row
    val changedTitle = titled !== row
    val result: Step? = when (event.status) {
      RemoteStatus.QUEUED -> when (state) {
        RequestState.SENT -> Step(titled.with(RequestState.IN_LINE).copy(inLineAt = titled.inLineAt ?: now))
        else -> null
      }
      RemoteStatus.RUNNING -> when (state) {
        RequestState.SENT, RequestState.IN_LINE ->
          Step(titled.with(RequestState.ANALYZING).copy(analyzingAt = titled.analyzingAt ?: now))
        else -> null
      }
      RemoteStatus.DONE -> when (state) {
        RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING, RequestState.CANCEL_WAITING ->
          Step(titled.with(RequestState.READY_ON_COMPUTER).copy(readyAt = titled.readyAt ?: now))
        else -> null
      }
      RemoteStatus.ERROR -> jobError(titled, state, event)
      RemoteStatus.CANCELLED -> when (state) {
        in ON_COMPUTER, RequestState.CANCEL_WAITING -> Step(
          titled.with(RequestState.CANCELLED).copy(lastErrorCode = Codes.CANCELLED, lastErrorMessage = event.errorMessage),
          listOf(Effect.DropOps(setOf(OpKind.CANCEL))),
        )
        else -> null
      }
      RemoteStatus.UNKNOWN -> null
    }
    return result ?: if (changedTitle) Step(titled) else null
  }

  private fun jobError(row: RequestEntity, state: RequestState, event: QueueEvent.Job): Step? {
    val code = event.errorCode?.takeIf { it.isNotBlank() } ?: Codes.UNKNOWN
    val job = row.jobId
    return when (state) {
      in ON_COMPUTER ->
        if (code == Codes.INTERRUPTED && !row.autoRerunUsed && job != null) {
          // One automatic re-run per request (QT-5): "Starting again on your computer".
          Step(row.copy(autoRerunUsed = true), listOf(Effect.Enqueue(OpKind.RERUN, job), Effect.Trigger))
        } else {
          attention(row, code, event.errorMessage)
        }
      // The learner was cancelling it: it failed anyway; no notification, and the cancel has nothing left to do.
      RequestState.CANCEL_WAITING -> Step(
        row.with(RequestState.NEEDS_ATTENTION).copy(lastErrorCode = code, lastErrorMessage = event.errorMessage),
        listOf(Effect.DropOps(setOf(OpKind.CANCEL))),
      )
      RequestState.NEEDS_ATTENTION ->
        if (row.lastErrorCode != code || row.lastErrorMessage != event.errorMessage) {
          Step(row.copy(lastErrorCode = code, lastErrorMessage = event.errorMessage))
        } else {
          null
        }
      else -> null
    }
  }

  private fun attention(row: RequestEntity, code: String, message: String?): Step = Step(
    row.with(RequestState.NEEDS_ATTENTION).copy(lastErrorCode = code, lastErrorMessage = message, notifiedAttention = true),
    if (row.notifiedAttention) emptyList() else listOf(Effect.NotifyAttention),
  )

  private fun cancel(row: RequestEntity, state: RequestState, event: QueueEvent.Cancel, context: MachineContext): Step? {
    if (state !in ON_COMPUTER || context.cancelPending || context.rerunPending) return null
    val job = row.jobId ?: return null
    val enqueue = Effect.Enqueue(OpKind.CANCEL, job)
    return if (event.connected) {
      // Sent at once; the listing confirms "Cancelled" (architecture 8.4).
      Step(row, listOf(enqueue, Effect.Trigger))
    } else {
      Step(row.with(RequestState.CANCEL_WAITING), listOf(enqueue))
    }
  }

  private fun tryAgain(row: RequestEntity, state: RequestState): Step? {
    val job = row.jobId
    return when {
      (state == RequestState.NEEDS_ATTENTION || state == RequestState.CANCELLED) && job != null ->
        Step(row, listOf(Effect.Enqueue(OpKind.RERUN, job), Effect.Trigger))
      // Damaged on the way three times; the copy is still here: send it again from the start.
      state == RequestState.NEEDS_ATTENTION && job == null && row.lastErrorCode == Codes.CHECKSUM_MISMATCH &&
        row.copyComplete -> Step(
        row.with(RequestState.WAITING).copy(
          attempts = 0,
          nextAttemptAt = null,
          lastErrorCode = null,
          lastErrorMessage = null,
          notifiedAttention = false,
        ),
        listOf(Effect.Trigger),
      )
      else -> null
    }
  }

  private fun tryAudioOnly(row: RequestEntity, state: RequestState, context: MachineContext): Step? {
    val file = row.kind == RequestKind.FILE.stored()
    val job = row.jobId
    return when {
      state == RequestState.COPY_FAILED && row.stateReason == CopyCodes.NO_SPACE && file && context.isVideo &&
        context.readable && !row.audioOnly -> Step(
        row.with(RequestState.PREPARING).copy(audioOnly = true, stateReason = null, neededBytes = null),
        listOf(Effect.StartIntake(fromKeptCopy = false)),
      )
      // Refused at sending as too large, its copy still here: extract the audio from the copy; nothing is re-run on
      // the computer, because no job exists (architecture 8.4).
      state == RequestState.NEEDS_ATTENTION && job == null && row.lastErrorCode == Codes.TOO_LARGE && file &&
        context.isVideo && row.copyComplete && !row.audioOnly -> Step(
        row.with(RequestState.PREPARING).copy(
          audioOnly = true,
          copyComplete = false,
          lastErrorCode = null,
          lastErrorMessage = null,
          notifiedAttention = false,
          attempts = 0,
          nextAttemptAt = null,
        ),
        listOf(Effect.StartIntake(fromKeptCopy = true)),
      )
      state == RequestState.NEEDS_ATTENTION && job != null && !file && row.lastErrorCode in AUDIO_ONLY_RERUN ->
        Step(row, listOf(Effect.Enqueue(OpKind.RERUN, job, audioOnly = true), Effect.Trigger))
      else -> null
    }
  }

  private fun remove(row: RequestEntity, state: RequestState, context: MachineContext): Step? {
    val job = row.jobId
    return when {
      state in LOCAL -> Step(null, listOf(Effect.StopIntake, Effect.DeleteFiles, Effect.ReleaseSource))
      state == RequestState.SENDING -> Step(
        null,
        listOf(Effect.AbortUpload, Effect.ForgetUploadSession, Effect.DeleteFiles, Effect.ReleaseSource),
      )
      state == RequestState.NEEDS_ATTENTION || state == RequestState.CANCELLED ||
        (context.rerunPending && state in ON_COMPUTER) -> Step(
        null,
        buildList {
          add(Effect.DropOps(setOf(OpKind.RERUN, OpKind.CANCEL)))
          // The failed or cancelled job is removed from the computer too (F-20 item 4; queued if offline).
          if (job != null) {
            add(Effect.Enqueue(OpKind.REMOVE_JOB, job))
            add(Effect.Trigger)
          }
          add(Effect.DeleteFiles)
          add(Effect.ReleaseSource)
        },
      )
      else -> null
    }
  }

  private fun RequestEntity.with(state: RequestState): RequestEntity = copy(state = state.stored())
}
