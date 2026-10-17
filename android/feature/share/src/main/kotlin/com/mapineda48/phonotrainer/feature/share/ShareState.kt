package com.mapineda48.phonotrainer.feature.share

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.component.BridgeLook
import com.mapineda48.phonotrainer.core.ui.component.BridgePhone
import com.mapineda48.phonotrainer.core.ui.component.ShareBridgeState
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind

/** What was shared, as the sheet's "what was shared" well shows it (spec 8.4 item 2, design 9.22). */
@Immutable
sealed interface SharedSummary {
  /** Nothing is shown yet: the intent is being read. */
  data object Reading : SharedSummary

  /** A link: its site and the link the queue will send (tracking parameters removed, spec 8.2). */
  data class Link(val site: Site, val sent: String) : SharedSummary

  /** One file: its printable name, kind, declared size and duration (hints, architecture 8.3). */
  data class OneFile(val name: String?, val kind: SharedKind, val bytes: Long?, val durationMs: Long?) : SharedSummary

  /** Several files: "3 videos · 412 MB" (sizes of the items that declared one). */
  data class ManyFiles(val count: Int, val videos: Int, val audios: Int, val bytes: Long?) : SharedSummary
}

/** Why a sheet shows a caution notice instead of the bridge (design 9.22: "Empty / errors / partial / duplicate"). */
@Immutable
sealed interface ShareProblem {
  /** "There's no link in what you shared." (SH-AC7). */
  data object NoLink : ShareProblem

  /** The file share had no item the sheet could read. */
  data object NothingReadable : ShareProblem

  /** "PhonoTrainer couldn't save this request: your phone is out of space." (architecture 8.1). */
  data object RecordOutOfSpace : ShareProblem

  /**
   * Files refused before any request (spec 8.3): one file's own message, or "0 of 3 added." and the reasons.
   * [tryAudioOnly]: one video refused for its size or for this phone's space, which "Audio only" would let in while the
   * sheet can still read it (D-49).
   */
  data class Refused(val total: Int, val refusals: List<FileRefusal>, val tryAudioOnly: Boolean = false) : ShareProblem
}

/** The message after the tap (spec 8.4 item 8). */
enum class DoneMessage {
  /** "Added to your queue." */
  ADDED,

  /** "Sending to your computer…" (a link, while connected). */
  SENDING,

  /** "Sent to your computer." (the computer confirmed while the sheet is open). */
  SENT,

  /** The computer refused it at sending: Requests says what can be done. */
  REFUSED,
}

/** Where the sheet is in the share flow (spec 8.4.1). */
@Immutable
sealed interface SharePhase {
  /** The intent and the items' facts are being read. */
  data object Reading : SharePhase

  /** The sheet waits for "Analyze" / "Add to queue" or "Cancel". */
  data object Confirm : SharePhase

  /** The tap was taken: the request is being stored. */
  data object Saving : SharePhase

  /** "You already added this link." with "Open it" / "Analyze again anyway" (SH-AC8). */
  data class DuplicateFound(val duplicate: Duplicate) : SharePhase

  /** Nothing was (or could be) added; a caution notice says why. */
  data class Problem(val problem: ShareProblem) : SharePhase

  /** Files are being copied into the app: "Preparing the file… 40 %" ([fraction] null while sizes are unknown). */
  data class Preparing(val ids: List<RequestId>, val fraction: Float?, val anyVideo: Boolean) : SharePhase

  /**
   * A copy failed while the sheet is open (`COPY_FAILED`): [request] is the failed one, whose `actions` decide what is
   * offered ("Try audio only" only while this sheet holds its source, D-49).
   */
  data class CopyFailed(val request: Request, val error: RequestError) : SharePhase

  /**
   * Stored (and, for files, copied): [message] is current; [refusals] of a multiple share that added only some
   * ("2 of 3 added. 1 file type isn't supported: …", SH-AC14) out of [total].
   */
  data class Done(
    val ids: List<RequestId>,
    val message: DoneMessage,
    val total: Int = ids.size,
    val refusals: List<FileRefusal> = emptyList(),
  ) : SharePhase

  /** The sheet has finished (closed by itself, cancelled, stopped or removed): the activity goes. */
  data object Closed : SharePhase
}

/** The in-context notification question of F-23 (asked once, after the first request is stored). */
enum class NotificationQuestion {
  /** Not shown. */
  NONE,

  /** "Get a notification when your clip is ready?" with "Not now" and "Notify me". */
  ASKING,

  /** The system's permission dialog is open. */
  SYSTEM_PROMPT,
}

/** Everything the sheet draws (design 9.22). */
@Immutable
data class ShareUiState(
  val summary: SharedSummary = SharedSummary.Reading,
  val status: ComputerStatus = ComputerStatus.NotConnected,
  /** The sheet's fresh probe is still within its 2 s (spec 8.4 item 3). */
  val checking: Boolean = true,
  val phase: SharePhase = SharePhase.Reading,
  val audioOnly: Boolean = false,
  /** "Audio only" is offered: always for links, for files when one is a video. */
  val audioOnlyOffered: Boolean = false,
  /** A video above 500 MB: "Large file: sending takes a few minutes on Wi-Fi." (spec 8.3). */
  val largeFile: Boolean = false,
  val question: NotificationQuestion = NotificationQuestion.NONE,
  /** "Stop adding this video?" is open (SH-AC13). */
  val confirmStop: Boolean = false,
  /** The computer's upload limit, for "larger than your computer accepts ({limit})" (2 GiB when never connected). */
  val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
  /** The computer's link limit (`maxLinkDurationS`), for the failure copy. */
  val maxLinkDurationS: Long? = null,
) {
  /** The bridge's state (design 9.22). */
  val bridge: ShareBridgeState
    get() = ShareBridgeState(
      status = status,
      checking = checking,
      phone = when (val current = phase) {
        is SharePhase.Preparing -> BridgePhone.Preparing(current.fraction)
        is SharePhase.CopyFailed -> BridgePhone.Alert
        else -> BridgePhone.Idle
      },
    )

  /** "Analyze" while checking or connected, "Add to queue" otherwise (design 9.22). */
  val analyzeNow: Boolean
    get() = bridge.look == BridgeLook.CHECKING || bridge.look == BridgeLook.CONNECTED

  /** The share is a link (the legal line, the site note). */
  val isLink: Boolean
    get() = summary is SharedSummary.Link

  /** The sheet may close by itself now (spec 8.4 item 8, D-51); TalkBack is the composable's to add. */
  val mayCloseByItself: Boolean
    get() = phase is SharePhase.Done && question == NotificationQuestion.NONE && !confirmStop

  companion object {
    /** The limit when the phone has never been connected (spec 8.3). */
    const val DEFAULT_MAX_UPLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** Above this, a video's "Audio only" is preselected (spec 8.3). */
    const val LARGE_FILE_BYTES: Long = 500L * 1024 * 1024
  }
}
