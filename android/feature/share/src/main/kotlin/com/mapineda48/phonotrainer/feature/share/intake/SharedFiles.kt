package com.mapineda48.phonotrainer.feature.share.intake

import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import java.util.Locale

/** Whether a shared item is a video or an audio file (spec 8.4 item 2: "Video" / "Audio"). */
enum class SharedKind { VIDEO, AUDIO }

/**
 * One shared item as the sheet shows it (spec 8.4 item 2). Everything here comes from the sharing app's provider and
 * is untrusted (architecture 8.3, security review M-4): [displayName] is already reduced to a printable base name, and
 * the name, type and size are only shown and handed to the queue as hints, which checks them again while it copies.
 * [refused] is set when the sheet itself refuses the item before any request (a type the computer cannot analyze, a
 * URI that is not another app's content).
 */
data class SharedItem(
  val uri: String,
  val displayName: String?,
  val mimeType: String?,
  val declaredBytes: Long?,
  val durationMs: Long?,
  val kind: SharedKind?,
  val refused: FileRefusalReason? = null,
) {
  /** The request's source: a shared item, never one picked with a lasting permission (architecture 8.3). */
  fun source(): FileSource = FileSource(uri, displayName, mimeType, declaredBytes, persisted = false)
}

/** What the providers of shared items say about them (Android: the content resolver; tests: a fake). */
interface SharedFiles {
  /**
   * The items of [uris], in order. A URI that is not `content://`, or that belongs to this app's own providers, is
   * refused without being queried; the others are asked for their name, size and type, and a seekable one for its
   * duration. [intentType] is the share's own type, used when a provider names none.
   */
  suspend fun inspect(uris: List<String>, intentType: String?): List<SharedItem>
}

/**
 * The media the computer analyzes (spec 8.3, `phonotrainer/jobs.py`): the same closed list the queue keeps
 * (architecture 8.3), here only to tell the learner when the sheet opens that a file cannot be analyzed (SH-AC10);
 * the queue decides again when the request is made.
 */
object SharedMedia {
  /** The suffixes, as the sheet lists them ("Supported: MP4, MOV, …"). */
  val VIDEO_SUFFIXES: Set<String> = setOf("webm", "mp4", "mkv", "mov", "avi", "m4v")
  val AUDIO_SUFFIXES: Set<String> = setOf("mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")

  private val MIME_SUFFIX: Map<String, String> = mapOf(
    "video/webm" to "webm", "video/mp4" to "mp4", "video/x-matroska" to "mkv", "video/quicktime" to "mov",
    "video/x-msvideo" to "avi", "video/avi" to "avi", "video/x-m4v" to "m4v",
    "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
    "audio/vnd.wave" to "wav", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a",
    "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg", "application/ogg" to "ogg",
    "audio/opus" to "opus", "audio/aac" to "aac", "audio/aacp" to "aac", "audio/x-aac" to "aac", "audio/webm" to "webm",
  )

  /** The accepted suffix of an item: its name's, else its type's; null when neither is one the computer takes. */
  fun suffixOf(displayName: String?, mimeType: String?): String? {
    val suffix = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
    if (suffix != null && (suffix in VIDEO_SUFFIXES || suffix in AUDIO_SUFFIXES)) return suffix
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    return MIME_SUFFIX[mime]
  }

  /** Video or audio, from the type first, then the suffix; null when neither says. */
  fun kindOf(suffix: String?, mimeType: String?): SharedKind? {
    val mime = mimeType?.lowercase(Locale.ROOT)
    return when {
      mime != null && mime.startsWith("audio/") -> SharedKind.AUDIO
      mime != null && mime.startsWith("video/") -> SharedKind.VIDEO
      suffix in VIDEO_SUFFIXES -> SharedKind.VIDEO
      suffix in AUDIO_SUFFIXES -> SharedKind.AUDIO
      else -> null
    }
  }

  /**
   * A provider's name as the sheet may show it: the last path segment, without control (`Cc`) or format (`Cf`)
   * characters (a right-to-left override cannot disguise the extension), trimmed, at most [MAX_NAME_CHARS] characters.
   */
  fun safeName(raw: String?): String? {
    if (raw == null) return null
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
      var index = 0
      while (index < base.length) {
        val codePoint = base.codePointAt(index)
        val type = Character.getType(codePoint)
        if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
      }
    }.trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
    return if (cleaned.codePointCount(0, cleaned.length) <= MAX_NAME_CHARS) {
      cleaned
    } else {
      cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_NAME_CHARS))
    }
  }

  /** The longest name kept (architecture 8.3). */
  const val MAX_NAME_CHARS: Int = 200
}
