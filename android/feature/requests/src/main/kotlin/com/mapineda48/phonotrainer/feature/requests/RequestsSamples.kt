package com.mapineda48.phonotrainer.feature.requests

import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import com.mapineda48.phonotrainer.feature.requests.add.AddClipUiState
import com.mapineda48.phonotrainer.feature.requests.add.AddPhase
import com.mapineda48.phonotrainer.feature.requests.add.AddProblem
import com.mapineda48.phonotrainer.feature.requests.add.AddedFile
import com.mapineda48.phonotrainer.feature.requests.add.DoneMessage
import com.mapineda48.phonotrainer.feature.requests.add.PickedFile
import com.mapineda48.phonotrainer.feature.requests.add.PickedKind
import java.time.ZoneOffset

/** Every state of Requests, the request detail and "Add a clip", for the previews and screen tests (made-up data). */
internal object RequestsSamples {
  private val now = RequestRowSamples.now
  private val all = RequestRowSamples.all.map { it.second }

  val browserFailed = ComputerJob(
    id = JobId("20261010-080000-b0b0"),
    title = "Lecture recording",
    status = RemoteStatus.ERROR,
    stage = null,
    percent = 0,
    queuePosition = null,
    source = ClipSource(SourceKind.UPLOAD, null, null, "lecture.mp4"),
    error = RequestError("processing_failed", "Something went wrong.", null, atSending = false),
    createdAt = now.minusSeconds(3_600),
    hidden = false,
  )

  val browserRunning = browserFailed.copy(id = JobId("20261010-081000-c1c1"), title = "Interview", status = RemoteStatus.RUNNING, percent = 30, error = null)

  // Requests (P-03).
  val loading = RequestsUiState(load = Load.LOADING, now = now)
  val failed = RequestsUiState(load = Load.FAILED, status = RequestRowSamples.connected, now = now)
  val empty = RequestsUiState(load = Load.READY, status = RequestRowSamples.connected, now = now)
  val full = RequestsUiState(
    load = Load.READY,
    groups = RequestsRules.groups(all, now),
    browser = listOf(browserRunning, browserFailed),
    browserExpanded = true,
    status = RequestRowSamples.connected,
    capabilities = RequestRowSamples.capabilities,
    now = now,
  )
  val offline = full.copy(status = RequestRowSamples.unreachable, browserExpanded = false)
  val paused = full.copy(
    groups = RequestsRules.groups(listOf(RequestRowSamples.waiting), now),
    browser = emptyList(),
    status = ComputerStatus.ConnectAgain,
  )

  val requestsStates: List<Pair<String, RequestsUiState>> = listOf(
    "loading" to loading,
    "error" to failed,
    "empty" to empty,
    "every group" to full,
    "offline" to offline,
    "paused" to paused,
  )

  // The request detail (P-04).
  private fun detail(request: Request?, details: Boolean = false) = RequestDetailUiState(
    load = Load.READY,
    request = request,
    status = RequestRowSamples.connected,
    capabilities = RequestRowSamples.capabilities,
    detailsExpanded = details,
    now = now,
    zone = ZoneOffset.UTC,
  )

  val detailAnalyzing = detail(RequestRowSamples.analyzing)
  val detailForbidden = detail(RequestRowSamples.downloadForbidden, details = true)
  val detailTooLong = detail(RequestRowSamples.tooLong, details = true)
  val detailWaiting = detail(RequestRowSamples.waiting)
  val detailOnPhone = detail(RequestRowSamples.onPhone.copy(timeline = RequestRowSamples.onPhone.timeline.copy(readyAt = now.minusSeconds(60), onPhoneAt = now)))
  val detailRemoved = detail(null)
  val detailLoading = RequestDetailUiState(now = now)

  val detailStates: List<Pair<String, RequestDetailUiState>> = listOf(
    "loading" to detailLoading,
    "analyzing" to detailAnalyzing,
    "download failed, details open" to detailForbidden,
    "too long, details open" to detailTooLong,
    "waiting" to detailWaiting,
    "on this phone" to detailOnPhone,
    "removed" to detailRemoved,
  )

  // "Add a clip" (P-05).
  private val link = ParsedLink("https://youtu.be/Ab3xQ9kLmQx7", "https://youtu.be/Ab3xQ9kLmQx7", "yt:Ab3xQ9kLmQx7", Site.YOUTUBE)
  private val video = PickedFile("content://picker/1", "bus-stop-chat.mp4", "video/mp4", 48L * 1024 * 1024, 42_000, PickedKind.VIDEO)
  private val bigVideo = PickedFile("content://picker/2", "long-interview.mp4", "video/mp4", 600L * 1024 * 1024, 2_520_000, PickedKind.VIDEO)
  private val audio = PickedFile("content://picker/3", "podcast.m4a", "audio/mp4", 6L * 1024 * 1024, 300_000, PickedKind.AUDIO)

  val addEmpty = AddClipUiState(status = RequestRowSamples.connected)
  val addLink = AddClipUiState(link = link.shared, parsed = link, status = RequestRowSamples.connected)
  val addOther = AddClipUiState(
    link = "https://example.org/talk",
    parsed = ParsedLink("https://example.org/talk", "https://example.org/talk", "https://example.org/talk", Site.OTHER),
    status = RequestRowSamples.unreachable,
  )
  val addNotALink = AddClipUiState(link = "Meet me at noon", linkError = true, status = RequestRowSamples.unreachable)
  val addFiles = AddClipUiState(files = listOf(video, bigVideo, audio), audioOnly = true, status = ComputerStatus.NotConnected)
  val addDuplicate = addLink.copy(phase = AddPhase.DuplicateFound(Duplicate(RequestId("waiting"), null, "How to order coffee like a local")))
  val addOutOfSpace = addLink.copy(phase = AddPhase.Problem(AddProblem.OutOfSpace))
  val addRefused = addFiles.copy(
    phase = AddPhase.Problem(
      AddProblem.Refused(3, listOf(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null), FileRefusal("long-interview.mp4", FileRefusalReason.TOO_LARGE, null))),
    ),
  )
  val addLinkSent = addLink.copy(phase = AddPhase.LinkAdded(RequestId("analyzing"), DoneMessage.SENT))
  val addLinkQueued = addOther.copy(phase = AddPhase.LinkAdded(RequestId("waiting"), DoneMessage.ADDED))
  val addFilesAdded = addFiles.copy(
    phase = AddPhase.FilesAdded(
      files = listOf(
        AddedFile(RequestId("preparing"), "bus-stop-chat.mp4", 48L * 1024 * 1024, IntakeProgress(RequestId("preparing"), 19L * 1024 * 1024, 48L * 1024 * 1024, false, null), RequestRowSamples.preparing),
        AddedFile(RequestId("waiting"), "podcast.m4a", 6L * 1024 * 1024, null, RequestRowSamples.waiting),
        AddedFile(RequestId("copy-no-space"), "long-interview.mp4", 600L * 1024 * 1024, null, RequestRowSamples.copyNoSpace),
      ),
      total = 4,
      refusals = listOf(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null)),
    ),
  )

  val addStates: List<Pair<String, AddClipUiState>> = listOf(
    "empty" to addEmpty,
    "link" to addLink,
    "other website, queued" to addOther,
    "not a link" to addNotALink,
    "files" to addFiles,
    "duplicate" to addDuplicate,
    "out of space" to addOutOfSpace,
    "files refused" to addRefused,
    "link sent" to addLinkSent,
    "link queued" to addLinkQueued,
    "files added" to addFilesAdded,
  )
}
