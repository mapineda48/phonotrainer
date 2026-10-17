package com.mapineda48.phonotrainer.feature.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.ui.extension.PhonoAppRoot
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind
import java.time.Instant

/** One state of the sheet per row of design 9.22 (and spec 8.4.1), for previews and screen tests. */
internal object ShareSamples {
  private val link = SharedSummary.Link(Site.YOUTUBE, "https://www.youtube.com/watch?v=Ab3xYz09Qw1")
  private val video = SharedSummary.OneFile("Street interview.mp4", SharedKind.VIDEO, 48_500_000, 42_000)
  private val connected = ComputerStatus.Connected("Studio PC", analyzing = 1, inLine = 1, lowOnSpace = false)
  private val unreachable = ComputerStatus.NotReachable("Studio PC", null, false, ProbeOutcome.NoAnswer(3))
  private val id = RequestId("6b1d5d6f-1c7f-4ce7-8a64-2a2c1ccf8d22")

  val checking = ShareUiState(link, ComputerStatus.Checking, checking = true, SharePhase.Confirm, audioOnlyOffered = true)
  val connectedLink = checking.copy(status = connected, checking = false)
  val queuedLink = checking.copy(status = unreachable, checking = false)
  val notPaired = checking.copy(status = ComputerStatus.NotConnected, checking = false)
  val needsAttention = checking.copy(status = ComputerStatus.ConnectAgain, checking = false)
  val otherSite = queuedLink.copy(summary = SharedSummary.Link(Site.OTHER, "https://example.org/talks/42"))
  val largeVideo = ShareUiState(
    SharedSummary.OneFile("Lecture.mp4", SharedKind.VIDEO, 640_000_000, 3_600_000),
    unreachable,
    checking = false,
    SharePhase.Confirm,
    audioOnly = true,
    audioOnlyOffered = true,
    largeFile = true,
  )
  val preparing = ShareUiState(video, unreachable, checking = false, SharePhase.Preparing(listOf(id), 0.4f, true))
  val copyFailed = ShareUiState(
    video,
    unreachable,
    checking = false,
    SharePhase.CopyFailed(
      Request(
        id, RequestKind.FILE, RequestState.COPY_FAILED, "Street interview.mp4", null, null, "Street interview.mp4",
        48_500_000, Instant.EPOCH, null, RequestError("copy_no_space", null, null, false, 120_000_000), null,
        setOf(RequestAction.REMOVE, RequestAction.TRY_AUDIO_ONLY),
      ),
      RequestError("copy_no_space", null, null, false, 120_000_000),
    ),
  )
  val added = queuedLink.copy(phase = SharePhase.Done(listOf(id), DoneMessage.ADDED))
  val sending = connectedLink.copy(phase = SharePhase.Done(listOf(id), DoneMessage.SENDING))
  val sent = connectedLink.copy(phase = SharePhase.Done(listOf(id), DoneMessage.SENT))
  val partial = ShareUiState(
    SharedSummary.ManyFiles(3, videos = 2, audios = 1, bytes = 412_000_000),
    unreachable,
    checking = false,
    SharePhase.Done(
      listOf(id, RequestId("b")),
      DoneMessage.ADDED,
      total = 3,
      refusals = listOf(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null)),
    ),
  )
  val noLink = ShareUiState(checking = false, phase = SharePhase.Problem(ShareProblem.NoLink))
  val unsupported = ShareUiState(
    SharedSummary.OneFile("voice.amr", SharedKind.AUDIO, 30_000, null),
    unreachable,
    checking = false,
    SharePhase.Problem(ShareProblem.Refused(1, listOf(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null)))),
  )
  val noSpace = ShareUiState(
    video,
    unreachable,
    checking = false,
    SharePhase.Problem(
      ShareProblem.Refused(1, listOf(FileRefusal("Street interview.mp4", FileRefusalReason.NO_SPACE, 900_000_000)), true),
    ),
    audioOnlyOffered = true,
  )
  val duplicate = queuedLink.copy(
    phase = SharePhase.DuplicateFound(Duplicate(id, JobId("20261004-133519-a77c"), "Street interview: weekend plans")),
  )
  val question = added.copy(question = NotificationQuestion.ASKING)
  val stop = preparing.copy(confirmStop = true)

  /** Every state, named for the screen tests' messages. */
  val all: List<Pair<String, ShareUiState>> = listOf(
    "checking" to checking, "connected" to connectedLink, "queued" to queuedLink, "not paired" to notPaired,
    "needs attention" to needsAttention, "other site" to otherSite, "large video" to largeVideo,
    "preparing" to preparing, "copy failed" to copyFailed, "added" to added, "sending" to sending, "sent" to sent,
    "partial" to partial, "no link" to noLink, "unsupported" to unsupported, "no space" to noSpace,
    "duplicate" to duplicate,
  )
}

internal class ShareStates : PreviewParameterProvider<ShareUiState> {
  override val values: Sequence<ShareUiState> = ShareSamples.all.map { it.second }.asSequence()
}

/** The sheet in each state of design 9.22 (renders `19`, `20`), light and dark. */
@PreviewLightDark
@Composable
internal fun ShareSheetPreview(@PreviewParameter(ShareStates::class) state: ShareUiState) {
  PhonoAppRoot(UserSettings(), reducedMotion = true, talkBackOn = false) {
    ShareSheet(state, ShareActions())
  }
}

/** The notification question over the sheet (spec F-23, D-51). */
@PreviewLightDark
@Composable
internal fun ShareQuestionPreview() {
  PhonoAppRoot(UserSettings(), reducedMotion = true, talkBackOn = false) {
    ShareSheet(ShareSamples.question, ShareActions())
  }
}

/** "Stop adding this video?" over a copy (SH-AC13). */
@PreviewLightDark
@Composable
internal fun ShareStopPreview() {
  PhonoAppRoot(UserSettings(), reducedMotion = true, talkBackOn = false) {
    ShareSheet(ShareSamples.stop, ShareActions())
  }
}
