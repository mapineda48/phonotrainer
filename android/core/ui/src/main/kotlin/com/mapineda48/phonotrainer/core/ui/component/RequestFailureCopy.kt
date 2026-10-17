package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.StringRes
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.R

/**
 * What a failure's copy depends on besides the error (architecture 19). [available] is `Request.actions`, the one
 * authority on what the request offers (architecture 8.4).
 */
data class FailureContext(
  val kind: RequestKind,
  val site: Site?,
  val available: Set<RequestAction>,
  /** `PairedComputer.capabilities?.maxLinkDurationS`: the `too_long` limit ("1 hour"); null when unknown. */
  val maxLinkDurationS: Long?,
  /**
   * Whether a file request is a video (its name or type says so); null when unknown. A `copy_no_space` body names "this
   * video" only for a video (spec 7.7). Additive to the architecture's shape, with a default.
   */
  val isVideo: Boolean? = null,
  /** `capabilities.maxUploadBytes`, for "larger than {limit}" at sending (spec 8.6); null when unknown. */
  val maxUploadBytes: Long? = null,
  /** `capabilities.freeBytes`, for the job `no_space` body "{free space}" (spec 8.6); null when unknown. */
  val freeBytes: Long? = null,
)

/** A failure in words: its title, its body (empty when spec 8.6 gives none) and the actions in spec 8.6 order. */
data class FailureCopy(val title: String, val body: String, val actions: List<RequestAction>)

/**
 * The one mapping from a request failure to its copy (architecture 19, delivery review D-24; WP-22): a request-level
 * code of server-contract 8.1, a job code of 8.2 or a phone-side copy code of architecture 8.3, to the title, body and
 * actions of spec 8.6 (or 7.7 for the copy codes), and to the F-23 notification line. Request rows, the share sheet and
 * `Notifier` all use it.
 *
 * Rules (architecture 19): the actions are the code's spec 8.6 order intersected with [FailureContext.available], so
 * the copy never offers what the state machine refuses; an action the state machine offers that the code's row does not
 * list is kept at the end rather than hidden (SC-14: a failure never loses its way out). Only a code the app does not
 * know maps to `processing_failed`'s copy with the computer's cleaned message. The strings live in
 * `strings_request.xml`, so both functions take the [Resources] to read them from (an addition to the architecture's
 * signature: a plain object cannot reach string resources otherwise).
 */
object RequestFailureCopy {
  fun of(error: RequestError, context: FailureContext, resources: Resources): FailureCopy {
    val rule = ruleOf(error.code, context)
    val title = resources.getString(rule.title)
    val body = rule.body(error, context, resources)
    return FailureCopy(title, body, ordered(rule.order, context.available))
  }

  /**
   * F-23 (D-40, F-23-AC4): "Couldn't download {site} link — Tap to try again or try audio only." for
   * `download_forbidden`; for every other failure its spec 8.6 title + " — Tap to see what you can do.".
   */
  fun notificationLine(error: RequestError, site: Site?, resources: Resources): String {
    if (error.code == Codes.DOWNLOAD_FORBIDDEN) {
      val word = siteWord(site)
      return if (word == null) {
        resources.getString(R.string.request_failure_notification_download_other)
      } else {
        resources.getString(R.string.request_failure_notification_download, resources.getString(word))
      }
    }
    val kind = if (site != null) RequestKind.LINK else RequestKind.FILE
    val title = resources.getString(ruleOf(error.code, FailureContext(kind, site, emptySet(), null)).title)
    return resources.getString(R.string.request_failure_notification, title)
  }

  /**
   * The `too_long` limit in words (architecture 19): whole hours when [seconds] is a multiple of 3,600 ("1 hour", "2
   * hours"), otherwise whole minutes ("90 minutes"); null when unknown.
   */
  fun limitWords(seconds: Long?, resources: Resources): String? {
    if (seconds == null || seconds <= 0) return null
    return if (seconds % SECONDS_PER_HOUR == 0L) {
      val hours = (seconds / SECONDS_PER_HOUR).toInt()
      resources.getQuantityString(R.plurals.request_limit_hours, hours, hours)
    } else {
      val minutes = ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
      resources.getQuantityString(R.plurals.request_limit_minutes, minutes, minutes)
    }
  }

  /** Every code this mapping words on purpose (server-contract 8.1, 8.2; architecture 8.3); any other is unknown. */
  val KNOWN_CODES: Set<String> = setOf(
    Codes.CANCELLED, Codes.INTERRUPTED, Codes.REFUSED_ADDRESS, Codes.LIVE_STREAM, Codes.TOO_LONG, Codes.PLAYLIST,
    Codes.DOWNLOAD_FORBIDDEN, Codes.DOWNLOAD_UNAVAILABLE, Codes.UNSUPPORTED_SITE, Codes.NETWORK, Codes.NO_SPACE,
    Codes.TOOL_MISSING, Codes.NOT_MEDIA, Codes.EXTRACT_FAILED, Codes.PROCESSING_FAILED, Codes.INVALID_URL,
    Codes.UNSUPPORTED_TYPE, Codes.TOO_LARGE, Codes.CHECKSUM_MISMATCH, Codes.SOURCE_GONE, Codes.COPY_NO_SPACE,
    Codes.COPY_UNREADABLE, Codes.COPY_INTERRUPTED, Codes.COPY_MISSING,
  )

  /** The codes this mapping knows (the strings of server-contract 8 and architecture 8.3). */
  object Codes {
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
    const val REFUSED_ADDRESS = "refused_address"
    const val LIVE_STREAM = "live_stream"
    const val TOO_LONG = "too_long"
    const val PLAYLIST = "playlist"
    const val DOWNLOAD_FORBIDDEN = "download_forbidden"
    const val DOWNLOAD_UNAVAILABLE = "download_unavailable"
    const val UNSUPPORTED_SITE = "unsupported_site"
    const val NETWORK = "network"
    const val NO_SPACE = "no_space"
    const val TOOL_MISSING = "tool_missing"
    const val NOT_MEDIA = "not_media"
    const val EXTRACT_FAILED = "extract_failed"
    const val PROCESSING_FAILED = "processing_failed"
    const val INVALID_URL = "invalid_url"
    const val UNSUPPORTED_TYPE = "unsupported_type"
    const val TOO_LARGE = "too_large"
    const val CHECKSUM_MISMATCH = "checksum_mismatch"
    const val SOURCE_GONE = "source_gone"
    const val COPY_NO_SPACE = "copy_no_space"
    const val COPY_UNREADABLE = "copy_unreadable"
    const val COPY_INTERRUPTED = "copy_interrupted"
    const val COPY_MISSING = "copy_missing"
  }

  private const val SECONDS_PER_HOUR = 3_600L
  private const val SECONDS_PER_MINUTE = 60L

  /** One row of spec 8.6 (or 7.7): the title, how the body is worded and the order of the actions. */
  private class Rule(
    @StringRes val title: Int,
    val order: List<RequestAction>,
    val body: (RequestError, FailureContext, Resources) -> String,
  )

  private fun fixed(@StringRes body: Int): (RequestError, FailureContext, Resources) -> String =
    { _, _, resources -> resources.getString(body) }

  private val none: (RequestError, FailureContext, Resources) -> String = { _, _, _ -> "" }

  /** The computer's cleaned message (server-contract 8.3), whole. */
  private val message: (RequestError, FailureContext, Resources) -> String = { error, _, _ -> error.message.orEmpty().trim() }

  private val TRY_AGAIN = RequestAction.TRY_AGAIN
  private val TRY_AUDIO_ONLY = RequestAction.TRY_AUDIO_ONLY
  private val OPEN_LINK = RequestAction.OPEN_LINK
  private val SHOW_DETAILS = RequestAction.SHOW_DETAILS
  private val REMOVE = RequestAction.REMOVE

  private fun ruleOf(code: String, context: FailureContext): Rule = when (code) {
    Codes.REFUSED_ADDRESS ->
      Rule(R.string.request_failure_refused_address_title, listOf(REMOVE), fixed(R.string.request_failure_refused_address_body))
    // A refusal at sending with no 8.6 row of its own: the link was not one the computer could take.
    Codes.INVALID_URL ->
      Rule(R.string.request_failure_refused_address_title, listOf(REMOVE), fixed(R.string.request_failure_invalid_url_body))
    Codes.UNSUPPORTED_TYPE ->
      Rule(R.string.request_failure_file_refused_title, listOf(TRY_AUDIO_ONLY, REMOVE), fixed(R.string.request_failure_unsupported_type_body))
    Codes.TOO_LARGE -> Rule(R.string.request_failure_file_refused_title, listOf(TRY_AUDIO_ONLY, REMOVE)) { _, ctx, res ->
      val limit = ctx.maxUploadBytes?.takeIf { it > 0 }
      if (limit == null) {
        res.getString(R.string.request_failure_too_large_unknown_body)
      } else {
        res.getString(R.string.request_failure_too_large_body, RequestRowCopy.bytes(limit, res))
      }
    }
    // Damaged on the way three times (server-contract 8.1): the copy is still on the phone, so it may pass.
    Codes.CHECKSUM_MISMATCH ->
      Rule(R.string.request_failure_file_refused_title, listOf(TRY_AGAIN, REMOVE), fixed(R.string.request_failure_checksum_body))
    Codes.DOWNLOAD_FORBIDDEN -> Rule(
      R.string.request_failure_download_forbidden_title,
      listOf(TRY_AGAIN, TRY_AUDIO_ONLY, OPEN_LINK, REMOVE),
      fixed(R.string.request_failure_download_forbidden_body),
    )
    Codes.DOWNLOAD_UNAVAILABLE -> Rule(
      R.string.request_failure_download_unavailable_title,
      listOf(OPEN_LINK, REMOVE),
      fixed(R.string.request_failure_download_unavailable_body),
    )
    Codes.UNSUPPORTED_SITE -> Rule(
      R.string.request_failure_unsupported_site_title,
      listOf(OPEN_LINK, REMOVE),
      fixed(R.string.request_failure_unsupported_site_body),
    )
    Codes.PLAYLIST ->
      Rule(R.string.request_failure_playlist_title, listOf(OPEN_LINK, REMOVE), fixed(R.string.request_failure_playlist_body))
    Codes.LIVE_STREAM -> Rule(
      R.string.request_failure_live_stream_title,
      listOf(TRY_AGAIN, OPEN_LINK, REMOVE),
      fixed(R.string.request_failure_live_stream_body),
    )
    Codes.TOO_LONG -> Rule(R.string.request_failure_too_long_title, listOf(OPEN_LINK, SHOW_DETAILS, REMOVE)) { _, ctx, res ->
      val limit = limitWords(ctx.maxLinkDurationS, res)
      if (limit == null) {
        res.getString(R.string.request_failure_too_long_unknown_body)
      } else {
        res.getString(R.string.request_failure_too_long_body, limit)
      }
    }
    Codes.NETWORK -> Rule(R.string.request_failure_network_title, listOf(TRY_AGAIN, REMOVE), none)
    // D-49: "Try audio only" for a link, "Try again" (once space is freed) for a file.
    Codes.NO_SPACE -> Rule(R.string.request_failure_no_space_title, listOf(TRY_AUDIO_ONLY, TRY_AGAIN, REMOVE)) { _, ctx, res ->
      ctx.freeBytes?.takeIf { it >= 0 }?.let { res.getString(R.string.request_failure_no_space_body, RequestRowCopy.bytes(it, res)) }.orEmpty()
    }
    Codes.NOT_MEDIA, Codes.EXTRACT_FAILED -> Rule(R.string.request_failure_not_media_title, listOf(REMOVE), message)
    Codes.TOOL_MISSING -> Rule(
      R.string.request_failure_tool_missing_title,
      listOf(TRY_AGAIN, REMOVE),
      fixed(R.string.request_failure_tool_missing_body),
    )
    Codes.INTERRUPTED -> Rule(
      R.string.request_failure_interrupted_title,
      listOf(TRY_AGAIN, REMOVE),
      fixed(R.string.request_failure_interrupted_body),
    )
    Codes.CANCELLED -> Rule(R.string.request_failure_cancelled_title, listOf(TRY_AGAIN, REMOVE), none)
    Codes.SOURCE_GONE ->
      Rule(R.string.request_failure_source_gone_title, listOf(REMOVE), fixed(R.string.request_failure_source_gone_body))
    Codes.COPY_NO_SPACE -> Rule(R.string.request_failure_copy_title, listOf(TRY_AUDIO_ONLY, REMOVE)) { error, ctx, res ->
      val megabytes = RequestRowCopy.megabytes(error.neededBytes ?: 0)
      when {
        TRY_AUDIO_ONLY in ctx.available -> res.getString(R.string.request_failure_copy_no_space_video, megabytes)
        ctx.isVideo == false -> res.getString(R.string.request_failure_copy_no_space_file, megabytes)
        else -> res.getString(R.string.request_failure_copy_no_space_video_reshare, megabytes)
      }
    }
    Codes.COPY_UNREADABLE -> Rule(R.string.request_failure_copy_title, listOf(REMOVE), fixed(R.string.request_failure_copy_unreadable))
    Codes.COPY_INTERRUPTED -> Rule(R.string.request_failure_copy_title, listOf(REMOVE), fixed(R.string.request_failure_copy_interrupted))
    Codes.COPY_MISSING ->
      Rule(R.string.request_failure_copy_missing_title, listOf(REMOVE), fixed(R.string.request_failure_copy_missing_body))
    // processing_failed, and any code this app does not know (architecture 19, NFR-08).
    else -> Rule(R.string.request_failure_processing_title, listOf(TRY_AGAIN, SHOW_DETAILS, REMOVE)) { error, _, res ->
      error.message?.lineSequence()?.map(String::trim)?.firstOrNull { it.isNotEmpty() }
        ?: res.getString(R.string.request_failure_processing_body)
    }
  }

  /**
   * [order] ∩ [available], then whatever else the one authority offers in the enum's order (never hidden), "Remove"
   * last as every row of spec 8.6 has it.
   */
  private fun ordered(order: List<RequestAction>, available: Set<RequestAction>): List<RequestAction> {
    val first = order.filter { it in available && it != REMOVE }
    val rest = RequestAction.entries.filter { it in available && it !in first && it != REMOVE }
    return first + rest + listOfNotNull(REMOVE.takeIf { it in available })
  }

  @StringRes
  private fun siteWord(site: Site?): Int? = when (site) {
    Site.YOUTUBE -> R.string.request_site_youtube
    Site.YOUTUBE_SHORTS -> R.string.request_site_shorts
    Site.X -> R.string.request_site_x
    Site.OTHER, null -> null
  }
}
