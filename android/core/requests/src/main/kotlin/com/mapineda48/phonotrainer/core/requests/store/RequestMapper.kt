package com.mapineda48.phonotrainer.core.requests.store

import com.mapineda48.phonotrainer.core.database.library.dao.RequestWithJob
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.RequestTimeline
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.requests.rules.ActionFacts
import com.mapineda48.phonotrainer.core.requests.rules.BackgroundWindow
import com.mapineda48.phonotrainer.core.requests.rules.MediaTypes
import com.mapineda48.phonotrainer.core.requests.rules.RequestActions
import com.mapineda48.phonotrainer.core.requests.rules.Titles
import com.mapineda48.phonotrainer.core.requests.rules.queuePause
import com.mapineda48.phonotrainer.core.requests.rules.storedKind
import com.mapineda48.phonotrainer.core.requests.rules.storedState
import java.time.Instant

/** What a request shows besides its row (architecture 8.4, 8.5): the moment's connection, sources, progress, speed. */
internal data class LiveFacts(
  val status: ComputerStatus,
  val readable: Set<RequestId>,
  val jobs: Map<String, RemoteJob>,
  val sent: Map<String, Moved>,
  val copied: Map<String, Moved>,
  /** The moving average of measured upload rates to the paired computer (bytes per second), or null. */
  val throughput: Double?,
)

/** The request's words and facts that do not depend on the moment. */
internal object RequestTitles {
  /**
   * The title: the computer's (once it reported one), else the shared subject or text around the link, else the file
   * name, else "{site} link" or "Video" (spec 8.2 rule 5). Every title is cleaned untrusted text.
   */
  fun of(row: RequestEntity, jobTitle: String?, words: RequestWords): String {
    row.titleHint?.let(Titles::clean)?.takeIf { it.isNotEmpty() }?.let { return it }
    jobTitle?.let(Titles::clean)?.takeIf { it.isNotEmpty() && !it.contains("://") }?.let { return it }
    return when (storedKind(row.kind)) {
      RequestKind.LINK -> words.linkTitle(site(row))
      RequestKind.FILE -> row.fileName?.let(Titles::clean)?.takeIf { it.isNotEmpty() } ?: words.fileTitle(isVideo(row))
    }
  }

  fun site(row: RequestEntity): Site? = row.site?.let { name -> Site.entries.firstOrNull { it.name == name } }

  /** The extension of the copy, or of the name and type the provider gave. */
  fun extension(row: RequestEntity): String? =
    row.copyFile?.substringAfterLast('.', "")?.takeIf { it in MediaTypes.SUFFIXES }
      ?: MediaTypes.extensionOf(row.fileName, row.fileMime)

  fun isVideo(row: RequestEntity): Boolean =
    storedKind(row.kind) == RequestKind.FILE && MediaTypes.isVideo(extension(row), row.fileMime)

  /** The request's failure, or its last reason to wait; null when there is none. */
  fun error(row: RequestEntity, downloaderVersion: String? = null): RequestError? {
    val state = storedState(row.state)
    val copyState = state == RequestState.COPY_FAILED || state == RequestState.COPY_MISSING
    val code = (if (copyState) row.stateReason else row.lastErrorCode) ?: return null
    return RequestError(
      code = code,
      message = if (copyState) null else row.lastErrorMessage,
      downloaderVersion = downloaderVersion,
      atSending = copyState || row.jobId == null,
      neededBytes = if (copyState) row.neededBytes else null,
    )
  }
}

/** A stored request as the domain sees it (architecture 3.4, work-plan 4.2). */
internal class RequestMapper(private val words: RequestWords) {
  fun map(view: RequestWithJob, facts: LiveFacts): Request {
    val row = view.request
    val id = RequestId(row.id)
    val kind = storedKind(row.kind)
    val stored = storedState(row.state)
    // A clip that started to come to this phone reads "Getting it on this phone" before its row says so.
    val state = if (stored == RequestState.READY_ON_COMPUTER && view.packTransfers > 0) RequestState.GETTING_ON_PHONE else stored
    val live = row.jobId?.let(facts.jobs::get)
    val connected = facts.status is ComputerStatus.Connected
    val copyKept = row.copyComplete && row.copyFile != null
    val actionFacts = ActionFacts(
      kind = kind,
      state = state,
      code = if (state == RequestState.COPY_FAILED || state == RequestState.COPY_MISSING) row.stateReason else row.lastErrorCode,
      hasJob = row.jobId != null,
      isVideo = RequestTitles.isVideo(row),
      audioOnly = row.audioOnly || row.audioExtracted,
      copyKept = copyKept,
      readable = row.sourcePersisted || id in facts.readable,
      connected = connected,
      pause = facts.status.queuePause(),
      rerunPending = view.rerunPending,
      cancelPending = view.cancelPending,
    )
    return Request(
      id = id,
      kind = kind,
      state = state,
      title = RequestTitles.of(row, view.jobTitle, words),
      site = RequestTitles.site(row),
      link = row.linkSent ?: row.linkShared,
      fileName = row.fileName,
      fileBytes = row.fileBytes ?: row.copyBytes,
      createdAt = Instant.ofEpochMilli(row.createdAt),
      progress = progress(view, state, live, facts),
      lastError = RequestTitles.error(row, live?.error?.downloaderVersion),
      jobId = row.jobId?.let(::JobId),
      actions = RequestActions.of(actionFacts),
      waiting = waiting(row, state, kind, facts),
      timeline = RequestTimeline(
        sentAt = row.sentAt?.let(Instant::ofEpochMilli),
        inLineAt = row.inLineAt?.let(Instant::ofEpochMilli),
        analyzingAt = row.analyzingAt?.let(Instant::ofEpochMilli),
        readyAt = row.readyAt?.let(Instant::ofEpochMilli),
        onPhoneAt = row.onPhoneAt?.let(Instant::ofEpochMilli),
      ),
      rerunPending = view.rerunPending,
      updatedAt = Instant.ofEpochMilli(row.updatedAt),
    )
  }

  private fun waiting(row: RequestEntity, state: RequestState, kind: RequestKind, facts: LiveFacts): WaitingInfo? {
    if (state != RequestState.WAITING && state != RequestState.SENDING) return null
    val bytes = row.fileBytes ?: row.copyBytes
    return WaitingInfo(
      held = row.held,
      attempts = row.attempts,
      nextAttemptAt = row.nextAttemptAt?.let(Instant::ofEpochMilli),
      // The uploader's own rule (architecture 8.5), whatever the connection state; never for links.
      needsAppOpen = kind == RequestKind.FILE && bytes != null && !BackgroundWindow.fits(bytes, facts.throughput),
    )
  }

  private fun progress(view: RequestWithJob, state: RequestState, live: RemoteJob?, facts: LiveFacts): RequestProgress? {
    val row = view.request
    return when (state) {
      RequestState.PREPARING -> facts.copied[row.id].let { moved ->
        val total = moved?.total ?: row.fileBytes
        RequestProgress(
          stage = null,
          percent = percent(moved?.bytes ?: 0, total),
          queuePosition = null,
          sentBytes = moved?.bytes ?: 0,
          totalBytes = total,
          downloadPercent = null,
        )
      }
      RequestState.SENDING -> facts.sent[row.id].let { moved ->
        val total = moved?.total ?: row.fileBytes ?: row.copyBytes
        RequestProgress(
          stage = null,
          percent = percent(moved?.bytes ?: 0, total),
          queuePosition = null,
          sentBytes = moved?.bytes ?: 0,
          totalBytes = total,
          downloadPercent = null,
        )
      }
      RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING, RequestState.CANCEL_WAITING -> RequestProgress(
        stage = live?.stage ?: view.jobStage?.let { name -> Stage.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
        percent = live?.percent ?: view.jobPercent,
        queuePosition = live?.queuePosition ?: view.jobQueuePosition,
        sentBytes = null,
        totalBytes = null,
        downloadPercent = live?.downloadPercent,
        segment = live?.segment,
        segments = live?.segments,
      )
      RequestState.GETTING_ON_PHONE -> RequestProgress(
        stage = null,
        percent = percent(view.packDoneBytes ?: 0, view.packTotalBytes),
        queuePosition = null,
        sentBytes = view.packDoneBytes,
        totalBytes = view.packTotalBytes,
        downloadPercent = null,
      )
      else -> null
    }
  }

  private fun percent(done: Long, total: Long?): Int? =
    total?.takeIf { it > 0 }?.let { ((done.coerceIn(0, it) * PERCENT) / it).toInt() }

  companion object {
    private const val PERCENT = 100L

    /**
     * Own requests only (F-03): waiting on this phone (being prepared, waiting, sending), on the computer (sent, in
     * line, analyzing, a cancel or a re-run on its way), needing the learner (failed, refused, a lost copy), and ready
     * on the computer but not on this phone yet ([RequestCounts.readyNew]).
     */
    fun counts(requests: List<Request>): RequestCounts {
      var waiting = 0
      var analyzing = 0
      var inLine = 0
      var onComputer = 0
      var attention = 0
      var ready = 0
      requests.forEach { request ->
        when {
          request.rerunPending -> {
            onComputer++
            inLine++
          }
          else -> when (request.state) {
            RequestState.PREPARING, RequestState.WAITING, RequestState.SENDING -> waiting++
            RequestState.SENT, RequestState.IN_LINE -> {
              onComputer++
              inLine++
            }
            RequestState.ANALYZING -> {
              onComputer++
              analyzing++
            }
            RequestState.CANCEL_WAITING -> onComputer++
            RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING -> attention++
            RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE -> ready++
            RequestState.ON_PHONE, RequestState.CANCELLED -> Unit
          }
        }
      }
      return RequestCounts(
        waiting = waiting,
        onComputer = onComputer,
        needsAttention = attention,
        readyNew = ready,
        analyzing = analyzing,
        inLine = inLine,
      )
    }
  }
}
