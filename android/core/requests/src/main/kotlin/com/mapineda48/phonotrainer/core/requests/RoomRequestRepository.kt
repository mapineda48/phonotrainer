package com.mapineda48.phonotrainer.core.requests

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.requests.store.LiveFacts
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestMapper
import com.mapineda48.phonotrainer.core.requests.store.RequestWords
import com.mapineda48.phonotrainer.core.requests.intake.FileIntake
import com.mapineda48.phonotrainer.core.requests.intake.IntakeHandles
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.Titles
import com.mapineda48.phonotrainer.core.requests.rules.stored
import com.mapineda48.phonotrainer.core.requests.rules.storedState
import com.mapineda48.phonotrainer.core.requests.send.QueueSender
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.withLock

/**
 * [RequestRepository] over the `request` table (architecture 8): local first, so every request is stored before any
 * network attempt or copy (8.1); `Request.actions` is decided here and nowhere else (8.4).
 */
@Singleton
internal class RoomRequestRepository @Inject constructor(
  private val core: QueueCore,
  private val live: QueueLive,
  private val handles: IntakeHandles,
  private val intake: FileIntake,
  private val sender: QueueSender,
  private val status: ComputerStatusRepository,
  private val pairing: PairingStore,
  private val parser: LinkParser,
  words: RequestWords,
  private val log: PhonoLog,
) : RequestRepository {
  private val mapper = RequestMapper(words)

  private val facts: Flow<LiveFacts> = combine(
    status.status,
    handles.readable,
    live.jobs,
    combine(live.sent, live.copied) { sent, copied -> sent to copied },
    pairing.pairing.map { it?.uploadThroughput }.distinctUntilChanged(),
  ) { computer, readable, jobs, (sent, copied), throughput ->
    LiveFacts(computer, readable, jobs, sent, copied, throughput)
  }

  override fun requests(): Flow<List<Request>> =
    combine(core.requests.observeAll(), facts) { rows, now -> rows.map { mapper.map(it, now) } }

  override fun request(id: RequestId): Flow<Request?> =
    combine(core.requests.observe(id.value), facts) { row, now -> row?.let { mapper.map(it, now) } }

  override fun counts(): Flow<RequestCounts> = requests().map(RequestMapper::counts).distinctUntilChanged()

  override suspend fun findDuplicate(input: LinkInput): Duplicate? {
    val parsed = parser.parse(input.sharedText, input.subject) ?: return null
    core.requests.latestWithLinkKey(parsed.key)?.let { row ->
      return Duplicate(RequestId(row.id), row.jobId?.let(::JobId), core.title(row))
    }
    return core.requests.catalogueLinks().firstOrNull { clip ->
      clip.sourceUrl == parsed.sent || parser.parse(clip.sourceUrl, null)?.key == parsed.key
    }?.let { clip -> Duplicate(null, JobId(clip.jobId), Titles.clean(clip.title)) }
  }

  override suspend fun addLink(input: LinkInput): AddResult {
    val parsed = parser.parse(input.sharedText, input.subject) ?: return AddResult.NoLink
    if (!input.force) findDuplicate(input)?.let { return AddResult.DuplicateFound(it) }
    val now = core.now()
    val row = RequestEntity(
      id = core.ids.next(),
      seq = 0,
      kind = RequestKind.LINK.stored(),
      state = RequestState.WAITING.stored(),
      createdAt = now,
      updatedAt = now,
      linkShared = parsed.shared,
      linkSent = parsed.sent,
      linkKey = parsed.key,
      site = parsed.site.name,
      titleHint = Titles.hint(input.sharedText, input.subject, parsed.shared),
      audioOnly = input.audioOnly,
      separateDialogue = true,
      targetServerId = currentServer(),
    )
    val stored = try {
      core.insert(row)
    } catch (expected: Exception) {
      // The insert itself failed (SQLite full): nothing is pretended (architecture 8.1).
      log.w("requests.insert_failed", expected)
      return AddResult.OutOfSpace
    }
    sender.requestCreated()
    return AddResult.Added(RequestId(stored.id))
  }

  override suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult =
    intake.add(files, options, currentServer())

  override fun holdSources(ids: Set<RequestId>): AutoCloseable = handles.hold(ids)

  override fun intake(id: RequestId): Flow<IntakeProgress> =
    combine(core.requests.observe(id.value), live.copied.map { it[id.value] }) { view, moved ->
      view?.let { row ->
        val request = row.request
        when (storedState(request.state)) {
          RequestState.PREPARING -> IntakeProgress(id, moved?.bytes ?: 0, moved?.total ?: request.fileBytes, done = false, failed = null)
          RequestState.COPY_FAILED -> IntakeProgress(
            id,
            copiedBytes = 0,
            totalBytes = request.fileBytes,
            done = false,
            failed = RequestError(
              code = request.stateReason ?: "copy_unreadable",
              message = null,
              downloaderVersion = null,
              atSending = true,
              neededBytes = request.neededBytes,
            ),
          )
          else -> IntakeProgress(id, request.copyBytes ?: request.fileBytes ?: 0, request.fileBytes, done = true, failed = null)
        }
      }
    }.transformWhile { progress ->
      if (progress != null) emit(progress)
      progress != null
    }.distinctUntilChanged()

  override suspend fun stopIntake(id: RequestId) {
    core.apply(id, QueueEvent.CancelPreparing)
  }

  override suspend fun perform(id: RequestId, action: RequestAction) {
    val request = request(id).first() ?: return
    if (action !in request.actions) {
      log.w("requests.action_not_offered") { "$action on ${request.state}" }
      return
    }
    when (action) {
      RequestAction.CANCEL_PREPARING -> core.apply(id, QueueEvent.CancelPreparing)
      RequestAction.SEND_NOW -> core.apply(id, QueueEvent.SendNow)?.also {
        if (request.kind == RequestKind.FILE) sender.scheduleUploads()
      }
      RequestAction.STOP_SENDING -> core.apply(id, QueueEvent.StopSending)
      RequestAction.REMOVE -> core.apply(id, QueueEvent.Remove)
      RequestAction.CANCEL -> core.apply(id, QueueEvent.Cancel(connected = status.status.value is ComputerStatus.Connected))
      // Never while a cancel is on the wire: an undone cancel must not reach the computer (F-20-AC12).
      RequestAction.UNDO_CANCEL -> core.sendLock.withLock { core.apply(id, QueueEvent.UndoCancel) }
      RequestAction.TRY_AGAIN -> core.apply(id, QueueEvent.TryAgain)
      RequestAction.TRY_AUDIO_ONLY -> core.apply(id, QueueEvent.TryAudioOnly)
      // The UI's own actions: opening the link, the details, the connection screens.
      RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.CONNECT_AGAIN, RequestAction.WHY -> Unit
    }
  }

  private suspend fun currentServer(): String? = runCatching { pairing.current()?.serverId?.value }.getOrNull()
}
