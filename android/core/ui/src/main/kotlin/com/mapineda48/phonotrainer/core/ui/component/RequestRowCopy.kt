package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.ui.R
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

/** What a request card draws under its words (design 9.19). */
enum class RequestVisual {
  /** Nothing: a failure, or a request that is done. */
  NONE,

  /** The route line Phone → Computer → Ready (waiting states). */
  ROUTE,

  /** The six-stage stepper (on your computer). */
  STEPPER,
}

/** One reason line of a card: its icon (clock or info, 16 dp) and its words. */
@Immutable
data class RequestReason(@DrawableRes val icon: Int, val text: String)

/**
 * A request card in words (design 9.19), everything [RequestRow] draws and TalkBack reads, computed by
 * [RequestRowCopy.words] so tests check the copy without drawing it.
 *
 * [failure] is set for the alert-card states (a failure, a lost copy, a cancelled request): its title is the state
 * line and its body the sentence. [actions] are `Request.actions` (the one authority, architecture 8.4) in the order
 * the card shows them: a failure's in its spec 8.6 order ([RequestFailureCopy]), the others in a fixed order.
 */
@Immutable
data class RequestRowWords(
  val state: String,
  @DrawableRes val stateIcon: Int,
  val source: String,
  @DrawableRes val sourceIcon: Int,
  val failure: FailureCopy?,
  val body: String?,
  val reasons: List<RequestReason>,
  /** The bar of a copy, an upload or a download (0 to 1), or null for none. */
  val progress: Float?,
  val visual: RequestVisual,
  val actions: List<RequestAction>,
) {
  /** The alert card variant (design 9.19): a failure. */
  val alert: Boolean get() = failure != null

  /** The inline primary action of the compact row (Library "In progress", F-04 item 1), or null. */
  val primary: RequestAction? get() = actions.firstOrNull()
}

/**
 * The words of a request card (design 9.19; spec F-20 item 1, 7.7): the state line, the source line, the reason lines,
 * the bar and the actions in their order. [RequestRow] draws them; the request detail (`:feature:requests`) reuses
 * the same functions so a request reads the same on both.
 */
object RequestRowCopy {
  /** The words of [state] at [now] ([RequestRowState.now] when it names one). */
  fun words(state: RequestRowState, now: Instant, resources: Resources): RequestRowWords {
    val request = state.request
    val at = state.now ?: now
    val failure = failureOf(request, state.capabilities, resources)
    return RequestRowWords(
      state = failure?.title ?: stateWords(request, state.status, at, resources),
      stateIcon = stateIcon(request, failure != null),
      source = source(request, at, resources),
      sourceIcon = sourceIcon(request),
      failure = failure,
      body = failure?.body?.takeIf { it.isNotBlank() }
        ?: resources.getString(R.string.request_in_line_note).takeIf { request.state == RequestState.IN_LINE && !request.rerunPending },
      reasons = reasons(request, state.status, at, resources),
      progress = progressOf(request),
      visual = visualOf(request, failure != null),
      actions = failure?.actions ?: ORDER.filter { it in request.actions },
    )
  }

  /** Whether [request] shows as a failure (the alert card, "Needs your attention"). */
  fun isFailure(request: Request): Boolean = !request.rerunPending && request.state in FAILURES

  /**
   * The failure copy of [request] (spec 8.6, 7.7), or null when it is not a failure. A cancelled request without a code
   * reads as `cancelled`, a failure without one as an unknown failure.
   */
  fun failureOf(request: Request, capabilities: Capabilities?, resources: Resources): FailureCopy? {
    if (!isFailure(request)) return null
    val error = request.lastError ?: RequestError(
      code = if (request.state == RequestState.CANCELLED) RequestFailureCopy.Codes.CANCELLED else RequestFailureCopy.Codes.PROCESSING_FAILED,
      message = null,
      downloaderVersion = null,
      atSending = request.jobId == null,
    )
    val context = FailureContext(
      kind = request.kind,
      site = request.site,
      available = request.actions,
      maxLinkDurationS = capabilities?.maxLinkDurationS?.takeIf { it > 0 },
      isVideo = isVideo(request),
      maxUploadBytes = capabilities?.maxUploadBytes?.takeIf { it > 0 },
      freeBytes = capabilities?.freeBytes?.takeIf { it >= 0 },
    )
    return RequestFailureCopy.of(error, context, resources)
  }

  /** The state line of a request that is not a failure (design 9.19), "Last known: …" for a computer out of reach. */
  fun stateWords(request: Request, status: ComputerStatus?, now: Instant, resources: Resources): String {
    val words = plainState(request, resources)
    val outOfReach = status != null && status !is ComputerStatus.Connected
    val updated = request.updatedAt
    return if (outOfReach && updated != null && !request.rerunPending && request.state in ON_COMPUTER) {
      resources.getString(R.string.request_last_known, words, ago(updated, now, resources))
    } else {
      words
    }
  }

  private fun plainState(request: Request, resources: Resources): String {
    if (request.rerunPending) return resources.getString(R.string.request_state_rerun)
    val progress = request.progress
    return when (request.state) {
      RequestState.PREPARING -> progress?.percent?.let { resources.getString(R.string.request_state_preparing, it.coerceIn(0, PERCENT)) }
        ?: resources.getString(R.string.request_state_preparing_unknown)
      RequestState.WAITING -> resources.getString(R.string.request_state_waiting)
      RequestState.SENDING -> {
        val percent = progress?.percent
        val total = progress?.totalBytes?.takeIf { it > 0 }
        if (percent != null && total != null) {
          resources.getString(R.string.request_state_sending, percent.coerceIn(0, PERCENT), bytesOf(progress.sentBytes ?: 0, total, resources))
        } else {
          resources.getString(R.string.request_state_sending_unknown)
        }
      }
      RequestState.SENT -> resources.getString(R.string.request_state_sent)
      RequestState.IN_LINE -> progress?.queuePosition?.takeIf { it > 0 }
        ?.let { resources.getString(R.string.request_state_in_line, ordinal(it)) }
        ?: resources.getString(R.string.request_state_in_line_unknown)
      RequestState.ANALYZING -> analyzing(request, resources)
      RequestState.CANCEL_WAITING -> resources.getString(R.string.request_state_cancel_waiting)
      RequestState.READY_ON_COMPUTER -> resources.getString(R.string.request_state_ready)
      RequestState.GETTING_ON_PHONE -> progress?.percent?.let { resources.getString(R.string.request_state_getting, it.coerceIn(0, PERCENT)) }
        ?: resources.getString(R.string.request_state_getting_unknown)
      RequestState.ON_PHONE -> resources.getString(R.string.request_state_on_phone)
      // The failure states read their failure title (words); these only serve a caller that asks anyway.
      RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING ->
        resources.getString(R.string.request_failure_processing_title)
      RequestState.CANCELLED -> resources.getString(R.string.request_failure_cancelled_title)
    }
  }

  /** "Analyzing 61 % · Hear the phones · phrase 3 of 10" (spec F-20 item 1, the web's `stageSummary`). */
  private fun analyzing(request: Request, resources: Resources): String {
    val progress = request.progress
    val head = progress?.percent?.let { resources.getString(R.string.request_state_analyzing, it.coerceIn(0, PERCENT)) }
      ?: resources.getString(R.string.request_state_analyzing_unknown)
    val stages = RequestRowStages.of(request, resources)
    val current = stages.firstOrNull { it.state == StepState.CURRENT }
      ?: return head
    return listOfNotNull(head, resources.getString(RequestRowStages.name(current.stage)), current.detail).joinToString(" · ")
  }

  /**
   * The source line under the card's title (design 9.19): the site word and the shortened link, or the file's kind and
   * size (and its name when the computer gave the clip another title); while the request is on this phone, its age
   * ("added 3 min ago", spec F-20 item 1).
   */
  fun source(request: Request, now: Instant, resources: Resources): String {
    val parts = mutableListOf<String>()
    when (request.kind) {
      RequestKind.LINK -> {
        parts += resources.getString(siteWord(request.site))
        request.link?.let { parts += shortLink(it) }
      }
      RequestKind.FILE -> {
        request.fileName?.takeIf { it.isNotBlank() && it != request.title }?.let { parts += it }
        parts += resources.getString(if (isVideo(request) == false) R.string.request_file_audio else R.string.request_file_video)
        request.fileBytes?.takeIf { it > 0 }?.let { parts += bytes(it, resources) }
      }
    }
    if (request.state in ON_THIS_PHONE && !request.rerunPending) {
      parts += resources.getString(R.string.request_added, ago(request.createdAt, now, resources))
    }
    return parts.joinToString(" · ")
  }

  /** The reason lines of a waiting request (spec F-20 item 1, D-41, D-46, D-61): held, or the D-61 reason; large file. */
  fun reasons(request: Request, status: ComputerStatus?, now: Instant, resources: Resources): List<RequestReason> {
    if (request.state != RequestState.WAITING || request.rerunPending) return emptyList()
    val waiting = request.waiting
    val first = when {
      waiting?.held == true -> resources.getString(R.string.request_reason_held)
      status != null -> WaitingReasonCopy.of(status, waiting, now, resources)
      else -> null
    }
    return listOfNotNull(
      first?.let { RequestReason(PhonoIcons.StatusWaiting, it) },
      resources.getString(R.string.request_reason_large_file).takeIf { waiting?.needsAppOpen == true }
        ?.let { RequestReason(PhonoIcons.Info, it) },
    )
  }

  private fun progressOf(request: Request): Float? {
    if (request.rerunPending) return null
    val percent = request.progress?.percent ?: return null
    return when (request.state) {
      RequestState.PREPARING, RequestState.SENDING, RequestState.GETTING_ON_PHONE -> percent.coerceIn(0, PERCENT) / PERCENT.toFloat()
      else -> null
    }
  }

  private fun visualOf(request: Request, failure: Boolean): RequestVisual = when {
    failure || request.rerunPending -> RequestVisual.NONE
    request.state == RequestState.PREPARING || request.state == RequestState.WAITING || request.state == RequestState.SENDING ->
      RequestVisual.ROUTE
    request.state in STEPPER_STATES -> RequestVisual.STEPPER
    else -> RequestVisual.NONE
  }

  /** The state's icon (design 9.19): the place or the activity, circle-alert for a failure. */
  @DrawableRes
  fun stateIcon(request: Request, failure: Boolean = isFailure(request)): Int = when {
    request.rerunPending -> PhonoIcons.Replay
    failure -> if (request.state == RequestState.CANCELLED) PhonoIcons.StatusCancelled else PhonoIcons.StatusAttention
    else -> when (request.state) {
      RequestState.PREPARING -> PhonoIcons.StatusPreparing
      RequestState.WAITING, RequestState.IN_LINE -> PhonoIcons.StatusWaiting
      RequestState.SENDING, RequestState.SENT -> PhonoIcons.StatusSending
      RequestState.ANALYZING -> PhonoIcons.StatusAnalyzing
      RequestState.CANCEL_WAITING, RequestState.CANCELLED -> PhonoIcons.StatusCancelled
      RequestState.READY_ON_COMPUTER, RequestState.ON_PHONE -> PhonoIcons.StatusReady
      RequestState.GETTING_ON_PHONE -> PhonoIcons.StatusDownloading
      RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING -> PhonoIcons.StatusAttention
    }
  }

  /** The source's icon: the site's generic glyph, or a video or audio file. */
  @DrawableRes
  fun sourceIcon(request: Request): Int = when (request.kind) {
    RequestKind.LINK -> when (request.site) {
      Site.YOUTUBE, Site.YOUTUBE_SHORTS -> PhonoIcons.SiteYoutube
      Site.X -> PhonoIcons.SiteX
      Site.OTHER, null -> PhonoIcons.SiteOther
    }
    RequestKind.FILE -> if (isVideo(request) == false) PhonoIcons.SitePhoneAudio else PhonoIcons.SitePhoneFile
  }

  /** "YouTube", "YouTube Shorts", "X", "Other website". */
  @StringRes
  fun siteWord(site: Site?): Int = when (site) {
    Site.YOUTUBE -> R.string.request_site_youtube
    Site.YOUTUBE_SHORTS -> R.string.request_site_shorts
    Site.X -> R.string.request_site_x
    Site.OTHER, null -> R.string.request_site_other
  }

  /** Whether a file request is a video, from its name's suffix (the computer's list, spec 8.3); null for a link or unknown. */
  fun isVideo(request: Request): Boolean? {
    if (request.kind != RequestKind.FILE) return null
    val suffix = request.fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return when (suffix) {
      in VIDEO_SUFFIXES -> true
      in AUDIO_SUFFIXES -> false
      else -> null
    }
  }

  /**
   * The label of [action] on [request]'s card: the same words everywhere (`RequestAction.labelRes`), and "…when
   * connected" for an action that needs the computer while it is not (spec F-20 States): a cancel of an accepted
   * request and a re-run of a failed or cancelled job.
   */
  @StringRes
  fun label(action: RequestAction, request: Request, status: ComputerStatus?): Int {
    val waitsForComputer = status != null && status !is ComputerStatus.Connected
    val rerun = request.jobId != null && (request.state == RequestState.NEEDS_ATTENTION || request.state == RequestState.CANCELLED)
    return when {
      waitsForComputer && action == RequestAction.CANCEL -> R.string.request_action_cancel_when_connected
      waitsForComputer && rerun && action == RequestAction.TRY_AGAIN -> R.string.request_action_try_again_when_connected
      waitsForComputer && rerun && action == RequestAction.TRY_AUDIO_ONLY -> R.string.request_action_try_audio_only_when_connected
      else -> action.labelRes()
    }
  }

  /** The icon of [action]'s button (design 6). */
  @DrawableRes
  fun icon(action: RequestAction): Int = when (action) {
    RequestAction.CANCEL_PREPARING -> PhonoIcons.Close
    RequestAction.SEND_NOW -> PhonoIcons.StatusSending
    RequestAction.STOP_SENDING -> PhonoIcons.Pause
    RequestAction.REMOVE -> PhonoIcons.Remove
    RequestAction.CANCEL -> PhonoIcons.CancelAnalysis
    RequestAction.UNDO_CANCEL -> PhonoIcons.Replay
    RequestAction.TRY_AGAIN -> PhonoIcons.Replay
    RequestAction.TRY_AUDIO_ONLY -> PhonoIcons.AudioOnly
    RequestAction.OPEN_LINK -> PhonoIcons.External
    RequestAction.SHOW_DETAILS -> PhonoIcons.Details
    RequestAction.CONNECT_AGAIN -> PhonoIcons.ConnConnectAgain
    RequestAction.WHY -> PhonoIcons.Help
  }

  /**
   * The computer's message as "Show details" shows it (spec F-20 item 2, F-20-AC7): the computer cleans it already
   * (server-contract 8.3) and so does `:core:requests`, and the phone still never shows a terminal escape sequence or
   * another control character (a new line and a tab stay); null when nothing is left.
   */
  fun details(message: String?): String? {
    val text = message ?: return null
    val plain = ESCAPES.replace(text, "").filter { it == '\n' || it == '\t' || !Character.isISOControl(it) }
    return plain.trim().takeIf { it.isNotEmpty() }
  }

  /** The link as a card shows it: no scheme, no "www.", the middle cut when long ("youtube.com/watch?v=…Qx7"). */
  fun shortLink(link: String): String {
    val bare = link.trim().substringAfter("://").removePrefix("www.")
    return if (bare.length <= SHORT_LINK_CHARS) bare else bare.take(SHORT_LINK_HEAD) + "…" + bare.takeLast(SHORT_LINK_TAIL)
  }

  /** A size in binary units, as the share sheet writes it: "850 KB", "4.2 MB", "412 MB", "1.5 GB", "2 GB". */
  fun bytes(value: Long, resources: Resources): String {
    val bytes = value.coerceAtLeast(0).toDouble()
    return when {
      bytes >= GB -> resources.getString(R.string.request_size_gb, oneDecimal(bytes / GB))
      bytes >= MB -> resources.getString(R.string.request_size_mb, if (bytes / MB >= 10) Math.round(bytes / MB).toString() else oneDecimal(bytes / MB))
      else -> resources.getString(R.string.request_size_kb, ceil(bytes / KB).toLong().coerceAtLeast(1).toString())
    }
  }

  /** "125 of 312 MB" when both sizes share a unit, "850 KB of 1.2 GB" otherwise (spec F-20 item 1, 7.7). */
  fun bytesOf(done: Long, total: Long, resources: Resources): String {
    val whole = bytes(total, resources)
    val part = bytes(done.coerceIn(0, total), resources)
    val unit = whole.substringAfterLast(' ')
    return if (part.substringAfterLast(' ') == unit) {
      resources.getString(R.string.request_bytes_of, part.substringBeforeLast(' '), whole)
    } else {
      resources.getString(R.string.request_bytes_of, part, whole)
    }
  }

  /** "Free {N} MB": the space to free in MB, rounded up, at least 1, with grouping ("1,024"). */
  fun megabytes(value: Long): String = String.format(Locale.US, "%,d", ceil(value.coerceAtLeast(0) / MB).toLong().coerceAtLeast(1))

  /** "just now", "3 min ago", "2 hours ago", "yesterday", "15 days ago". */
  fun ago(from: Instant, now: Instant, resources: Resources): String {
    val elapsed = Duration.between(from, now).let { if (it.isNegative) Duration.ZERO else it }
    val minutes = elapsed.toMinutes()
    val hours = elapsed.toHours()
    val days = elapsed.toDays()
    return when {
      minutes < 1 -> resources.getString(R.string.request_ago_now)
      hours < 1 -> resources.getQuantityString(R.plurals.request_ago_minutes, minutes.toInt(), minutes.toInt())
      days < 1 -> resources.getQuantityString(R.plurals.request_ago_hours, hours.toInt(), hours.toInt())
      days == 1L -> resources.getString(R.string.request_ago_yesterday)
      else -> resources.getQuantityString(R.plurals.request_ago_days, days.toInt(), days.toInt())
    }
  }

  /** "1st", "2nd", "3rd", "4th", "11th", "22nd". */
  fun ordinal(n: Int): String {
    val suffix = when {
      n % 100 in 11..13 -> "th"
      n % 10 == 1 -> "st"
      n % 10 == 2 -> "nd"
      n % 10 == 3 -> "rd"
      else -> "th"
    }
    return "$n$suffix"
  }

  private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else String.format(Locale.US, "%.1f", rounded)
  }

  /** The order of a card's actions when it is not a failure (a failure's come from its spec 8.6 row). */
  val ORDER: List<RequestAction> = listOf(
    RequestAction.SEND_NOW,
    RequestAction.STOP_SENDING,
    RequestAction.UNDO_CANCEL,
    RequestAction.CANCEL,
    RequestAction.CANCEL_PREPARING,
    RequestAction.CONNECT_AGAIN,
    RequestAction.WHY,
    RequestAction.TRY_AGAIN,
    RequestAction.TRY_AUDIO_ONLY,
    RequestAction.OPEN_LINK,
    RequestAction.SHOW_DETAILS,
    RequestAction.REMOVE,
  )

  /** ANSI CSI sequences ("ESC [ 31 m"), OSC sequences ending with BEL or ST, and lone two-character escapes. */
  private val ESCAPES = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")

  private val FAILURES = setOf(RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING, RequestState.CANCELLED)
  private val ON_COMPUTER = setOf(RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING)
  private val STEPPER_STATES = setOf(RequestState.SENT, RequestState.IN_LINE, RequestState.ANALYZING, RequestState.CANCEL_WAITING)
  private val ON_THIS_PHONE = setOf(
    RequestState.PREPARING,
    RequestState.WAITING,
    RequestState.SENDING,
    RequestState.COPY_FAILED,
    RequestState.COPY_MISSING,
  )
  private val VIDEO_SUFFIXES = setOf("webm", "mp4", "mkv", "mov", "avi", "m4v")
  private val AUDIO_SUFFIXES = setOf("mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")
  private const val PERCENT = 100
  private const val KB = 1024.0
  private const val MB = KB * 1024
  private const val GB = MB * 1024
  private const val SHORT_LINK_CHARS = 30
  private const val SHORT_LINK_HEAD = 24
  private const val SHORT_LINK_TAIL = 3
}
