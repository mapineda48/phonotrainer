package com.mapineda48.phonotrainer.feature.share

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind
import java.util.Locale
import kotlin.math.ceil

/** Numbers as the sheet writes them: sizes in binary units ("2 GB", "412 MB"), durations ("0:42", "1:02:03"). */
object ShareFormat {
  private const val KB = 1024.0
  private const val MB = KB * 1024
  private const val GB = MB * 1024

  /** "850 KB", "4.2 MB", "412 MB", "1.5 GB", "2 GB". */
  fun bytes(value: Long): String {
    val bytes = value.coerceAtLeast(0).toDouble()
    return when {
      bytes >= GB -> "${oneDecimal(bytes / GB)} GB"
      bytes >= MB -> if (bytes / MB >= 10) "${Math.round(bytes / MB)} MB" else "${oneDecimal(bytes / MB)} MB"
      else -> "${ceil(bytes / KB).toLong().coerceAtLeast(1)} KB"
    }
  }

  /** "Free {N} MB": the space to free, rounded up, with grouping ("1,024"). */
  fun megabytes(value: Long): String = String.format(Locale.US, "%,d", ceil(value.coerceAtLeast(0) / MB).toLong().coerceAtLeast(1))

  /** "0:42", "12:05", "1:02:03". */
  fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
  }

  /**
   * The link as the well shows it (design 9.22: "youtube.com/watch?v=Ab3x… · Show it all"): without its scheme and a
   * leading "www.", cut at [SHORT_LINK_CHARS] characters with an ellipsis; [full] gives it all, scheme included.
   */
  fun link(sent: String, full: Boolean): String {
    if (full) return sent
    val bare = sent.substringAfter("://").removePrefix("www.")
    return if (bare.length <= SHORT_LINK_CHARS) bare else bare.take(SHORT_LINK_CHARS - 1) + "…"
  }

  /** Whether [link] with [full] = false hides part of [sent]. */
  fun isShortened(sent: String): Boolean = sent.substringAfter("://").removePrefix("www.").length > SHORT_LINK_CHARS

  private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else String.format(Locale.US, "%.1f", rounded)
  }

  /** The short form keeps this many characters. */
  const val SHORT_LINK_CHARS: Int = 25
}

/** The sheet's words that depend on state (spec 8.3, 8.4, 8.4.1), read from [Resources] so tests read the same text. */
object ShareCopy {
  fun siteTitle(site: Site, resources: Resources): String = resources.getString(
    when (site) {
      Site.YOUTUBE -> R.string.share_site_youtube
      Site.YOUTUBE_SHORTS -> R.string.share_site_shorts
      Site.X -> R.string.share_site_x
      Site.OTHER -> R.string.share_site_other
    },
  )

  /** "Video · 12 MB · 0:42" (each part only when known). */
  fun oneFileLine(kind: SharedKind, bytes: Long?, durationMs: Long?, resources: Resources): String = listOfNotNull(
    resources.getString(if (kind == SharedKind.VIDEO) R.string.share_kind_video else R.string.share_kind_audio),
    bytes?.let(ShareFormat::bytes),
    durationMs?.let(ShareFormat::duration),
  ).joinToString(SEPARATOR)

  /** "3 videos · 412 MB", "2 audio files", "3 files · 1.2 GB". */
  fun manyFilesLine(count: Int, videos: Int, audios: Int, bytes: Long?, resources: Resources): String {
    val what = when (count) {
      videos -> resources.getQuantityString(R.plurals.share_many_videos, count, count)
      audios -> resources.getQuantityString(R.plurals.share_many_audios, count, count)
      else -> resources.getQuantityString(R.plurals.share_many_files, count, count)
    }
    return listOfNotNull(what, bytes?.let(ShareFormat::bytes)).joinToString(SEPARATOR)
  }

  /** One refused file's own message (spec 8.3, 7.7). */
  fun refusal(refusal: FileRefusal, video: Boolean, maxUploadBytes: Long, resources: Resources): String = when (refusal.reason) {
    FileRefusalReason.UNSUPPORTED_TYPE -> resources.getString(R.string.share_unsupported)
    FileRefusalReason.TOO_LARGE -> resources.getString(R.string.share_too_large, ShareFormat.bytes(maxUploadBytes))
    FileRefusalReason.NO_SPACE -> {
      val needed = ShareFormat.megabytes(refusal.neededBytes ?: 0)
      resources.getString(if (video) R.string.share_no_space_video else R.string.share_no_space_file, needed)
    }
    FileRefusalReason.UNREADABLE, FileRefusalReason.OWN_PROVIDER -> resources.getString(R.string.share_unreadable)
    FileRefusalReason.TOO_MANY -> resources.getString(R.string.share_too_many)
  }

  /**
   * A multiple share that was (partly) refused: "2 of 3 added." then one sentence per reason, in a fixed order, with
   * the files' names ("1 file type isn't supported: voice.amr.", SH-AC14).
   */
  fun partial(added: Int, total: Int, refusals: List<FileRefusal>, resources: Resources): String {
    val sentences = mutableListOf(resources.getQuantityString(R.plurals.share_partial_added, total, added, total))
    REASON_ORDER.forEach { (reasons, named, unnamed) ->
      val group = refusals.filter { it.reason in reasons }
      if (group.isEmpty()) return@forEach
      val names = group.mapNotNull { it.displayName }.joinToString(", ")
      sentences += when {
        named == null -> resources.getQuantityString(unnamed, group.size, group.size)
        names.isEmpty() -> resources.getQuantityString(unnamed, group.size, group.size)
        else -> resources.getQuantityString(named, group.size, group.size, names)
      }
    }
    return sentences.joinToString(" ")
  }

  private val REASON_ORDER: List<Triple<Set<FileRefusalReason>, Int?, Int>> = listOf(
    Triple(setOf(FileRefusalReason.UNSUPPORTED_TYPE), R.plurals.share_partial_unsupported, R.plurals.share_partial_unsupported_unnamed),
    Triple(setOf(FileRefusalReason.TOO_LARGE), R.plurals.share_partial_too_large, R.plurals.share_partial_too_large_unnamed),
    Triple(setOf(FileRefusalReason.NO_SPACE), R.plurals.share_partial_no_space, R.plurals.share_partial_no_space_unnamed),
    Triple(
      setOf(FileRefusalReason.UNREADABLE, FileRefusalReason.OWN_PROVIDER),
      R.plurals.share_partial_unreadable,
      R.plurals.share_partial_unreadable_unnamed,
    ),
    Triple(setOf(FileRefusalReason.TOO_MANY), null, R.plurals.share_partial_too_many),
  )

  private const val SEPARATOR = " · "
}
