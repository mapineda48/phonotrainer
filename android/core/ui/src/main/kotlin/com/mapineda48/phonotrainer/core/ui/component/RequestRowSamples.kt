package com.mapineda48.phonotrainer.core.ui.component

import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.RequestTimeline
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import java.time.Duration
import java.time.Instant

/**
 * Requests in every state of design 9.19, for the previews and screen tests of the request card, Requests and the
 * request detail (made-up titles and links, no personal data). [now] is the moment they are seen at.
 */
object RequestRowSamples {
  val now: Instant = Instant.parse("2026-10-10T09:41:00Z")

  val connected: ComputerStatus = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 1, lowOnSpace = false)
  val unreachable: ComputerStatus = ComputerStatus.NotReachable("Desk PC", now.minusSeconds(600), false, ProbeOutcome.NoAnswer(3))

  val capabilities = Capabilities(
    linkDownloads = true,
    downloaderVersion = "2026.08.19",
    dialogueSeparation = true,
    renditions = false,
    phoneVideo = false,
    resumableUploads = false,
    recordCompare = false,
    maxUploadBytes = 2L * 1024 * 1024 * 1024,
    maxChunkBytes = 8L * 1024 * 1024,
    maxLinkDurationS = 3_600,
    freeBytes = 1_500_000_000,
  )

  /** A request with the fields every sample shares; each sample changes what its state needs. */
  fun request(
    id: String,
    state: RequestState,
    kind: RequestKind = RequestKind.LINK,
    actions: Set<RequestAction> = emptySet(),
    title: String = "How to order coffee like a local",
    site: Site? = if (kind == RequestKind.LINK) Site.YOUTUBE else null,
    link: String? = if (kind == RequestKind.LINK) "https://www.youtube.com/watch?v=Ab3xQ9kLmQx7" else null,
    fileName: String? = if (kind == RequestKind.FILE) "bus-stop-chat.mp4" else null,
    fileBytes: Long? = if (kind == RequestKind.FILE) 48L * 1024 * 1024 else null,
    progress: RequestProgress? = null,
    error: RequestError? = null,
    jobId: JobId? = null,
    waiting: WaitingInfo? = null,
    rerunPending: Boolean = false,
    createdAt: Instant = now.minusSeconds(180),
  ) = Request(
    id = RequestId(id),
    kind = kind,
    state = state,
    title = title,
    site = site,
    link = link,
    fileName = fileName,
    fileBytes = fileBytes,
    createdAt = createdAt,
    progress = progress,
    lastError = error,
    jobId = jobId,
    actions = actions,
    waiting = waiting,
    timeline = RequestTimeline(
      sentAt = jobId?.let { createdAt.plusSeconds(20) },
      inLineAt = jobId?.let { createdAt.plusSeconds(21) },
      analyzingAt = if (state == RequestState.ANALYZING) createdAt.plusSeconds(60) else null,
    ),
    rerunPending = rerunPending,
    updatedAt = now.minus(Duration.ofMinutes(10)),
  )

  private val job = JobId("20261010-090200-a1b2")

  val preparing = request(
    "preparing",
    RequestState.PREPARING,
    kind = RequestKind.FILE,
    title = "bus-stop-chat.mp4",
    actions = setOf(RequestAction.CANCEL_PREPARING),
    progress = RequestProgress(null, 40, null, 19L * 1024 * 1024, 48L * 1024 * 1024, null),
  )

  val waiting = request(
    "waiting",
    RequestState.WAITING,
    kind = RequestKind.FILE,
    title = "bus-stop-chat.mp4",
    actions = setOf(RequestAction.SEND_NOW, RequestAction.REMOVE),
    waiting = WaitingInfo(held = false, attempts = 1, nextAttemptAt = now.plusSeconds(110)),
  )

  val waitingHeld = request(
    "held",
    RequestState.WAITING,
    actions = setOf(RequestAction.SEND_NOW, RequestAction.REMOVE),
    waiting = WaitingInfo(held = true, attempts = 0, nextAttemptAt = null),
  )

  val waitingLarge = request(
    "large",
    RequestState.WAITING,
    kind = RequestKind.FILE,
    title = "long-interview.mp4",
    fileName = "long-interview.mp4",
    fileBytes = 600L * 1024 * 1024,
    actions = setOf(RequestAction.REMOVE),
    waiting = WaitingInfo(held = false, attempts = 0, nextAttemptAt = null, needsAppOpen = true),
    createdAt = now.minus(Duration.ofDays(15)),
  )

  val sending = request(
    "sending",
    RequestState.SENDING,
    kind = RequestKind.FILE,
    title = "film-scene.mp4",
    fileName = "film-scene.mp4",
    fileBytes = 312L * 1024 * 1024,
    actions = setOf(RequestAction.STOP_SENDING, RequestAction.REMOVE),
    progress = RequestProgress(null, 40, null, 125L * 1024 * 1024, 312L * 1024 * 1024, null),
  )

  val inLine = request(
    "in-line",
    RequestState.IN_LINE,
    actions = setOf(RequestAction.CANCEL),
    progress = RequestProgress(null, 0, 2, null, null, null),
    jobId = job,
  )

  val analyzing = request(
    "analyzing",
    RequestState.ANALYZING,
    site = Site.X,
    link = "https://x.com/someone/status/1890000000000000041",
    actions = setOf(RequestAction.CANCEL),
    progress = RequestProgress(Stage.PHONES, 61, null, null, null, null, segment = 3, segments = 10),
    jobId = job,
  )

  val cancelWaiting = request(
    "cancel-waiting",
    RequestState.CANCEL_WAITING,
    actions = setOf(RequestAction.UNDO_CANCEL),
    progress = RequestProgress(Stage.TRANSCRIBE, 45, null, null, null, null),
    jobId = job,
  )

  val rerun = request(
    "rerun",
    RequestState.NEEDS_ATTENTION,
    actions = setOf(RequestAction.REMOVE),
    error = RequestError("interrupted", "Interrupted: the server stopped.", null, atSending = false),
    jobId = job,
    rerunPending = true,
  )

  val getting = request(
    "getting",
    RequestState.GETTING_ON_PHONE,
    progress = RequestProgress(null, 70, null, 7_000_000, 10_000_000, null),
    jobId = job,
  )

  val onPhone = request("on-phone", RequestState.ON_PHONE, jobId = job)

  val downloadForbidden = request(
    "forbidden",
    RequestState.NEEDS_ATTENTION,
    title = "YouTube link",
    actions = setOf(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK, RequestAction.REMOVE),
    error = RequestError("download_forbidden", "HTTP Error 403: Forbidden", "2026.08.19", atSending = false),
    jobId = job,
  )

  val tooLong = request(
    "too-long",
    RequestState.NEEDS_ATTENTION,
    actions = setOf(RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.REMOVE),
    error = RequestError("too_long", "TooLongRefused: the video is 2:14:00 long.", null, atSending = false),
    jobId = job,
  )

  val copyNoSpace = request(
    "copy-no-space",
    RequestState.COPY_FAILED,
    kind = RequestKind.FILE,
    title = "bus-stop-chat.mp4",
    actions = setOf(RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE),
    error = RequestError("copy_no_space", null, null, atSending = true, neededBytes = 120L * 1024 * 1024),
  )

  val cancelled = request(
    "cancelled",
    RequestState.CANCELLED,
    actions = setOf(RequestAction.TRY_AGAIN, RequestAction.REMOVE),
    error = RequestError("cancelled", "Cancelled during the analysis.", null, atSending = false),
    jobId = job,
  )

  /** One request of each card state of design 9.19, named. */
  val all: List<Pair<String, Request>> = listOf(
    "preparing" to preparing,
    "waiting" to waiting,
    "held" to waitingHeld,
    "large file" to waitingLarge,
    "sending" to sending,
    "in line" to inLine,
    "analyzing" to analyzing,
    "cancel waiting" to cancelWaiting,
    "starting again" to rerun,
    "getting it" to getting,
    "on this phone" to onPhone,
    "download failed" to downloadForbidden,
    "too long" to tooLong,
    "no space for the copy" to copyNoSpace,
    "cancelled" to cancelled,
  )
}
