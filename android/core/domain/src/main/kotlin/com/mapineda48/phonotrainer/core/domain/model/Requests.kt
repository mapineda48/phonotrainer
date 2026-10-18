package com.mapineda48.phonotrainer.core.domain.model

import com.mapineda48.phonotrainer.core.domain.model.remote.SeparationNote
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import java.time.Instant

/** One entry of the device queue (architecture 3.4, 8.4; work-plan 4.2 additive fields). */
data class Request(
  val id: RequestId,
  val kind: RequestKind,
  val state: RequestState,
  val title: String,
  val site: Site?,
  val link: String?,
  val fileName: String?,
  val fileBytes: Long?,
  val createdAt: Instant,
  val progress: RequestProgress?,
  val lastError: RequestError?,
  val jobId: JobId?,
  /** The one authority on what the row offers (architecture 8.4). */
  val actions: Set<RequestAction>,
  /** Why it waits: held, attempts, next attempt, needsAppOpen (architecture 8.5). */
  val waiting: WaitingInfo? = null,
  /** Milestone times of the request detail. */
  val timeline: RequestTimeline = RequestTimeline(),
  /** "Starting again on your computer" (architecture 8.4). */
  val rerunPending: Boolean = false,
  /** "Last known: Analyzing 40 % (10 min ago)". */
  val updatedAt: Instant? = null,
  /**
   * The clip's length in seconds once it is known: the picked or shared file's facts, or the job's `media.duration_s`
   * or `summary.duration_s` (server-contract 6.3); null before (a link before its download). The request detail's
   * source line and its estimate "Usually takes about as long as the clip (about 4 min)." (spec F-20 item 2).
   */
  val durationS: Double? = null,
)

enum class RequestState {
  PREPARING,
  COPY_FAILED,
  COPY_MISSING,
  WAITING,
  SENDING,
  SENT,
  IN_LINE,
  ANALYZING,
  READY_ON_COMPUTER,
  GETTING_ON_PHONE,
  ON_PHONE,
  NEEDS_ATTENTION,
  CANCEL_WAITING,
  CANCELLED,
}

enum class RequestAction {
  CANCEL_PREPARING,
  SEND_NOW,
  STOP_SENDING,
  REMOVE,
  CANCEL,
  UNDO_CANCEL,
  TRY_AGAIN,
  TRY_AUDIO_ONLY,
  OPEN_LINK,
  SHOW_DETAILS,
  CONNECT_AGAIN,
  WHY,
}

/** Work-plan 4.2. [held]: "Stop sending" (D-46). */
data class WaitingInfo(
  val held: Boolean,
  val attempts: Int,
  val nextAttemptAt: Instant?,
  /**
   * A file too large for one background run: "Open PhonoTrainer on your home Wi-Fi to send this file: …"
   * (architecture 8.5, spec D-41); false for links.
   */
  val needsAppOpen: Boolean = false,
)

data class RequestTimeline(
  val sentAt: Instant? = null,
  val inLineAt: Instant? = null,
  val analyzingAt: Instant? = null,
  val readyAt: Instant? = null,
  val onPhoneAt: Instant? = null,
)

// Work-plan Appendix A.4: requests (WP-09, WP-22, WP-23).

enum class RequestKind { LINK, FILE }

/** "Analyzing 61 % · Hear the phones · phrase 3 of 10" (F-20). */
data class RequestProgress(
  val stage: Stage?,
  val percent: Int?,
  val queuePosition: Int?,
  val sentBytes: Long?,
  val totalBytes: Long?,
  val downloadPercent: Int?,
  val segment: Int? = null,
  val segments: Int? = null,
  /**
   * The job's `separation` (server-contract 6.3): [SeparationNote.OFF] (the option was off), [SeparationNote.SKIPPED]
   * (the computer skipped it), [SeparationNote.UNKNOWN] (a value this app does not know); null otherwise. The
   * stepper's "skipped" node for "Separate the dialogue" (spec F-20 item 2, design 9.19).
   */
  val separation: SeparationNote? = null,
)

/**
 * [code]: a request-level code (server-contract 8.1), a job code (8.2), or a phone-side copy code (architecture 8.3):
 * "copy_no_space", "copy_unreadable", "copy_interrupted" (state COPY_FAILED), "copy_missing" (state COPY_MISSING).
 */
data class RequestError(
  val code: String,
  val message: String?,
  val downloaderVersion: String?,
  val atSending: Boolean,
  /** The space a refused copy needs: "Free {N} MB" (architecture 8.3). */
  val neededBytes: Long? = null,
)

/** Own requests only (F-03); [onComputer] is split for the status card rows "1 analyzing", "1 in line". */
data class RequestCounts(
  val waiting: Int,
  val onComputer: Int,
  val needsAttention: Int,
  val readyNew: Int,
  val analyzing: Int = 0,
  val inLine: Int = 0,
)

/**
 * [separateDialogue]: "Separate the dialogue from music and effects" (spec F-19), sent as the link route's
 * `separate_dialogue` (server-contract 6.5), as [FileOptions.separateDialogue] is for files.
 */
data class LinkInput(
  val sharedText: String,
  val subject: String?,
  val audioOnly: Boolean,
  val force: Boolean = false,
  val separateDialogue: Boolean = true,
)

data class ParsedLink(val shared: String, val sent: String, val key: String, val site: Site)

data class Duplicate(val requestId: RequestId?, val jobId: JobId?, val title: String?)

sealed interface AddResult {
  data class Added(val id: RequestId) : AddResult

  data class DuplicateFound(val duplicate: Duplicate) : AddResult

  data object NoLink : AddResult

  /** The insert itself failed (architecture 8.1). */
  data object OutOfSpace : AddResult
}

/** [persisted]: picked with a lasting permission. */
data class FileSource(
  val uri: String,
  val displayName: String?,
  val mimeType: String?,
  val declaredBytes: Long?,
  val persisted: Boolean,
)

data class FileOptions(val audioOnly: Boolean, val separateDialogue: Boolean = true)

data class AddFilesResult(val added: List<RequestId>, val refused: List<FileRefusal>)

data class FileRefusal(val displayName: String?, val reason: FileRefusalReason, val neededBytes: Long?)

enum class FileRefusalReason { UNSUPPORTED_TYPE, TOO_LARGE, NO_SPACE, UNREADABLE, OWN_PROVIDER, TOO_MANY }

data class IntakeProgress(
  val id: RequestId,
  val copiedBytes: Long,
  val totalBytes: Long?,
  val done: Boolean,
  val failed: RequestError?,
)

data class SendReport(val sent: Int, val refused: Int, val transient: Int, val blocked: Boolean)
