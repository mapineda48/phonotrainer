package com.mapineda48.phonotrainer.core.requests.store

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.id.UuidSource
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.dao.RequestDao
import com.mapineda48.phonotrainer.core.database.library.dao.RequestOpDao
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.database.library.entity.RequestOpEntity
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.requests.intake.IntakeHandles
import com.mapineda48.phonotrainer.core.requests.intake.SourceReader
import com.mapineda48.phonotrainer.core.requests.rules.CopyCodes
import com.mapineda48.phonotrainer.core.requests.rules.Effect
import com.mapineda48.phonotrainer.core.requests.rules.MachineContext
import com.mapineda48.phonotrainer.core.requests.rules.OpKind
import com.mapineda48.phonotrainer.core.requests.rules.OpState
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.RequestMachine
import com.mapineda48.phonotrainer.core.requests.rules.Step
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** The id source of the queue's rows and operations (random UUIDs; tests pass a predictable one). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class QueueIds

/** A transition that was written: the row before it and the step taken. */
internal data class Applied(val before: RequestEntity, val step: Step) {
  val after: RequestEntity? get() = step.row
}

/**
 * The one place that writes the device queue (architecture 8.4): every transition is [RequestMachine] applied to the
 * row read in the same Room transaction, its outbox changes written in that transaction, and its other effects (files,
 * permissions, work, messages, notifications) run once it committed. The sender re-reads a request inside the
 * transaction that marks it `SENDING`, so a request removed or cancelled a moment earlier is never sent (QT-6).
 */
@Singleton
internal class QueueCore @Inject constructor(
  private val database: LibraryDatabase,
  val requests: RequestDao,
  val ops: RequestOpDao,
  val files: QueueFiles,
  private val handles: IntakeHandles,
  private val reader: SourceReader,
  private val work: RequestWork,
  private val uploads: ActiveUploads,
  val words: RequestWords,
  private val notifier: Notifier,
  private val messages: UserMessages,
  private val trigger: SyncTrigger,
  private val shorts: ShortRepository,
  private val client: ComputerClient,
  val clock: Clock,
  @param:QueueIds val ids: UuidSource,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) {
  /** Serializes the outbox and link sending with "Undo" of a cancel, so an undone cancel is never on the wire. */
  val sendLock = Mutex()

  fun now(): Long = clock.now().toEpochMilli()

  suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)

  /** Applies [event] to request [id]; null when the request is gone or the event does not apply to its state. */
  suspend fun apply(id: RequestId, event: QueueEvent): Applied? {
    val applied = transaction { write(id, event) } ?: return null
    runEffects(id, applied)
    return applied
  }

  /** Inserts a new request with the next creation order; the caller's row carries everything else. */
  suspend fun insert(row: RequestEntity): RequestEntity = transaction {
    val stored = row.copy(seq = requests.nextSeq())
    requests.insert(stored)
    stored
  }

  /** Whether the request is a video file (for "Try audio only"). */
  fun isVideo(row: RequestEntity): Boolean = RequestTitles.isVideo(row)

  /** Whether the request's source can be read again now (architecture 8.3, D-49). */
  fun readable(row: RequestEntity): Boolean = row.sourcePersisted || handles.isReadable(RequestId(row.id))

  fun title(row: RequestEntity): String = RequestTitles.of(row, jobTitle = null, words = words)

  private suspend fun write(id: RequestId, event: QueueEvent): Applied? {
    val row = requests.get(id.value) ?: return null
    val pending = ops.pendingFor(id.value)
    val context = MachineContext(
      now = now(),
      isVideo = isVideo(row),
      readable = readable(row),
      rerunPending = pending.any { it.kind == OpKind.RERUN.name },
      cancelPending = pending.any { it.kind == OpKind.CANCEL.name },
    )
    val step = RequestMachine.on(row, event, context) ?: return null
    val after = step.row
    if (after == null) requests.delete(id.value) else requests.update(after)
    var order = 0
    step.effects.forEach { effect ->
      when (effect) {
        is Effect.Enqueue -> ops.insert(
          RequestOpEntity(
            id = ids.next(),
            kind = effect.kind.name,
            requestId = row.id,
            jobId = effect.jobId,
            audioOnly = if (effect.kind == OpKind.RERUN) effect.audioOnly else null,
            createdAt = context.now + order++,
          ),
        )
        is Effect.DropOps -> pending.filter { op -> effect.kinds.any { it.name == op.kind } }.forEach { ops.delete(it.id) }
        else -> Unit
      }
    }
    return Applied(row, step)
  }

  private suspend fun runEffects(id: RequestId, applied: Applied) {
    val before = applied.before
    val current = applied.after ?: before
    applied.step.effects.forEach { effect ->
      when (effect) {
        Effect.DeleteFiles -> if (!files.delete(id)) log.w("requests.files_not_deleted")
        Effect.ReleaseSource -> release(before)
        Effect.StopIntake -> {
          work.stopIntake(id)
          handles.drop(id)
        }
        is Effect.StartIntake -> startIntake(current, effect.fromKeptCopy)
        Effect.AbortUpload -> uploads.cancel(id)
        Effect.ForgetUploadSession -> if (before.kind == KIND_FILE) {
          scope.launch { runCatching { client.deleteUpload(id) } }
        }
        is Effect.Message -> messages.post(effect.kind, listOf(title(current)))
        Effect.NotifyAttention -> RequestTitles.error(current)?.let { error ->
          notifier.needsAttention(id, title(current), RequestTitles.site(current), error)
        }
        Effect.NotifyReady -> current.jobId?.let { job ->
          val count = runCatching { shorts.shorts(JobId(job)).first().size }.getOrDefault(0)
          notifier.clipReady(JobId(job), title(current), count)
        }
        Effect.Trigger -> trigger.request(SyncReason.REQUEST_CREATED)
        is Effect.Enqueue, is Effect.DropOps -> Unit
      }
    }
  }

  private fun release(row: RequestEntity) {
    val uri = row.sourceUri ?: return
    if (row.sourcePersisted) runCatching { reader.release(uri) }
  }

  private suspend fun startIntake(row: RequestEntity, fromKeptCopy: Boolean) {
    val id = RequestId(row.id)
    when {
      fromKeptCopy || row.sourcePersisted -> work.startFileIntake(id)
      else -> {
        // The share sheet still holds this request: its grant lets the app open the source again (8.3, D-49).
        val opened = row.sourceUri?.let { uri -> runCatching { reader.open(uri) }.getOrNull() }
        if (opened == null) {
          apply(id, QueueEvent.CopyFailed(CopyCodes.UNREADABLE))
        } else {
          handles.put(id, opened)
          work.startShareIntake(id)
        }
      }
    }
  }

  /** Marks an outbox operation answered. */
  suspend fun finish(op: RequestOpEntity, state: OpState, code: String? = null) {
    ops.update(op.copy(state = state.name, lastErrorCode = code))
  }

  private companion object {
    const val KIND_FILE = "FILE"
  }
}
