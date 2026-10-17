package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.DrawableRes
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.ui.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The words of a clip card and a clip row (spec F-03 item 7, F-04; design 9.18), made once so that what a row shows
 * and the one sentence TalkBack reads for it are the same words. The availability words are :core:ui's, the ones
 * `AvailabilityChip` draws. Numbers use English grouping ("1,489"): the app is English only.
 */
object ClipCardCopy {
  /** Joins the parts of a meta line. */
  const val DOT: String = " · "

  private val numbers: NumberFormat get() = NumberFormat.getIntegerInstance(Locale.US)

  /** "1,489". */
  fun number(value: Int): String = numbers.format(value)

  /** A length: "4 min 21 s", "4 min", "45 s", "1 h 3 min" (seconds rounded). */
  fun duration(seconds: Double, resources: Resources): String {
    val total = seconds.coerceAtLeast(0.0).roundToLong()
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val rest = total % 60
    return when {
      hours > 0 -> resources.getString(R.string.clip_duration_hours, hours.toInt(), minutes.toInt())
      minutes > 0 && rest == 0L -> resources.getString(R.string.clip_duration_whole_minutes, minutes.toInt())
      minutes > 0 -> resources.getString(R.string.clip_duration_minutes, minutes.toInt(), rest.toInt())
      else -> resources.getString(R.string.clip_duration_seconds, rest.toInt())
    }
  }

  /** The Library row's meta line: "4 min 21 s · 691 words · 14 changes" ("10 min 14 s · Audio only · 1,489 words"). */
  fun rowMeta(clip: ClipSummary, resources: Resources): String = listOfNotNull(
    clip.durationS?.let { duration(it, resources) },
    resources.getString(R.string.ui_video_audio_only).takeIf { clip.video == VideoAvailability.AudioOnlyClip },
    clip.words?.let { resources.getQuantityString(R.plurals.clip_words, it, number(it)) },
    clip.changeCount?.let { resources.getQuantityString(R.plurals.clip_changes, it, number(it)) },
  ).joinToString(DOT)

  /** The shelf card's meta line: "4 min 21 s · 12 shorts" ("Audio only · 31 shorts" without a length). */
  fun cardMeta(clip: ClipSummary, resources: Resources): String = listOfNotNull(
    clip.durationS?.let { duration(it, resources) },
    resources.getString(R.string.ui_video_audio_only).takeIf { clip.video == VideoAvailability.AudioOnlyClip },
    clip.shortsTotal.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.clip_shorts, it, number(it)) },
  ).joinToString(DOT)

  /** Where the clip came from, in one word ("YouTube", "X", "Website", "Uploaded file", "Computer"). */
  fun source(source: ClipSource, resources: Resources): String = resources.getString(
    when (source.kind) {
      SourceKind.LINK -> when (source.site) {
        Site.YOUTUBE, Site.YOUTUBE_SHORTS -> R.string.clip_source_youtube
        Site.X -> R.string.clip_source_x
        Site.OTHER, null -> R.string.clip_source_website
      }
      SourceKind.UPLOAD -> R.string.clip_source_upload
      SourceKind.PATH, SourceKind.IMPORT, SourceKind.UNKNOWN -> R.string.clip_source_computer
    },
  )

  /** The source's icon, decorative (the word carries it). */
  @DrawableRes
  fun sourceIcon(source: ClipSource): Int = when (source.kind) {
    SourceKind.LINK -> when (source.site) {
      Site.YOUTUBE, Site.YOUTUBE_SHORTS -> PhonoIcons.SiteYoutube
      Site.X -> PhonoIcons.SiteX
      Site.OTHER, null -> PhonoIcons.SiteOther
    }
    SourceKind.UPLOAD -> PhonoIcons.SitePhoneFile
    SourceKind.PATH, SourceKind.IMPORT, SourceKind.UNKNOWN -> PhonoIcons.SiteComputer
  }

  /** The availability words of spec 7.11, exactly as `AvailabilityChip` draws them. */
  fun availability(availability: ClipAvailability, resources: Resources): String = when (availability) {
    ClipAvailability.OnThisPhone -> resources.getString(R.string.ui_availability_on_phone)
    is ClipAvailability.Bringing -> resources.getString(R.string.ui_availability_bringing, availability.percent)
    ClipAvailability.NotOnThisPhone -> resources.getString(R.string.ui_availability_not_on_phone)
    ClipAvailability.GettingReadyOnComputer -> resources.getString(R.string.ui_availability_getting_ready)
    is ClipAvailability.DownloadPaused -> resources.getString(
      when (availability.reason) {
        PauseReason.BUDGET_REACHED -> R.string.ui_availability_paused_budget
        PauseReason.FREE_SPACE_FLOOR -> R.string.ui_availability_paused_space
        PauseReason.NOT_CONNECTED -> R.string.ui_availability_paused_offline
        PauseReason.NOT_WANTED -> R.string.ui_availability_paused
      },
    )
    ClipAvailability.NeedsDownloadingAgain -> resources.getString(R.string.ui_availability_again)
  }

  /** The video's words, exactly as `VideoAvailabilityChip` draws them. */
  fun video(video: VideoAvailability, resources: Resources): String = resources.getString(
    when (video) {
      VideoAvailability.OnThisPhone -> R.string.ui_video_on_phone
      is VideoAvailability.Downloading -> return resources.getString(R.string.ui_video_downloading, video.percent)
      VideoAvailability.NotOnThisPhone -> R.string.ui_video_not_on_phone
      VideoAvailability.GettingReadyOnComputer -> R.string.ui_video_getting_ready
      VideoAvailability.NotAvailableFromComputer -> R.string.ui_video_not_available
      VideoAvailability.CannotPlayOnThisPhone -> R.string.ui_video_cannot_play
      VideoAvailability.AudioOnlyClip -> R.string.ui_video_audio_only
    },
  )

  /** "You've seen 12 of 15 shorts"; "15 shorts" before the first one is seen; null while the clip has no shorts. */
  fun seen(clip: ClipSummary, resources: Resources): String? = when {
    clip.shortsTotal <= 0 -> null
    clip.shortsSeen <= 0 -> resources.getQuantityString(R.plurals.clip_shorts, clip.shortsTotal, number(clip.shortsTotal))
    else -> resources.getQuantityString(
      R.plurals.clip_shorts_seen,
      clip.shortsTotal,
      clip.shortsSeen.coerceAtMost(clip.shortsTotal),
      clip.shortsTotal,
    )
  }

  /** "From a computer you forgot" / "From another library" (spec 7.9); null for an active clip. */
  fun origin(origin: ClipOrigin, resources: Resources): String? = when (origin) {
    ClipOrigin.ACTIVE -> null
    ClipOrigin.FORGOTTEN_COMPUTER -> resources.getString(R.string.clip_origin_forgotten)
    ClipOrigin.OTHER_LIBRARY -> resources.getString(R.string.clip_origin_other_library)
  }

  /** "Older rules: re-analyze on your computer to update", when the clip was analyzed with older rules. */
  fun olderRules(clip: ClipSummary, resources: Resources): String? =
    resources.getString(R.string.clip_older_rules).takeIf { clip.olderRules }

  /** "Kept", when the learner keeps the clip on this phone. */
  fun kept(clip: ClipSummary, resources: Resources): String? = resources.getString(R.string.clip_kept).takeIf { clip.kept }

  /**
   * The Library row in one sentence, its visible lines in reading order: "Coffee shop small talk, 4 min 21 s · 691
   * words · 14 changes, YouTube, On this phone, Kept, You've seen 12 of 15 shorts".
   */
  fun rowSentence(clip: ClipSummary, resources: Resources): String = listOfNotNull(
    clip.title,
    rowMeta(clip, resources).ifBlank { null },
    source(clip.source, resources),
    availability(clip.availability, resources),
    kept(clip, resources),
    seen(clip, resources),
    olderRules(clip, resources),
    origin(clip.origin, resources),
  ).joinToString(", ")

  /** The shelf card in one sentence: title, meta, the family counts, availability and origin. */
  fun cardSentence(state: ClipCardState, familyNames: Map<Family, String>, resources: Resources): String = listOfNotNull(
    state.clip.title,
    cardMeta(state.clip, resources).ifBlank { null },
    state.families.filter { it.count > 0 }.takeIf { it.isNotEmpty() }?.joinToString(", ") {
      resources.getString(R.string.clip_family_count, familyNames[it.family].orEmpty(), it.count)
    },
    availability(state.clip.availability, resources),
    kept(state.clip, resources),
    origin(state.clip.origin, resources),
  ).joinToString(", ")

  /** A size in decimal units, as the system shows it: "640 KB", "2.1 MB", "17 MB", "1.2 GB". */
  fun bytes(bytes: Long, resources: Resources): String {
    fun scaled(value: Double): String =
      if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.roundToLong().toString()
    return when {
      bytes < KB -> resources.getString(R.string.clip_size_bytes, bytes.coerceAtLeast(0).toInt())
      bytes < MB -> resources.getString(R.string.clip_size_kb, scaled(bytes / KB.toDouble()))
      bytes < GB -> resources.getString(R.string.clip_size_mb, scaled(bytes / MB.toDouble()))
      else -> resources.getString(R.string.clip_size_gb, scaled(bytes / GB.toDouble()))
    }
  }

  private const val KB = 1_000L
  private const val MB = 1_000_000L
  private const val GB = 1_000_000_000L
}
