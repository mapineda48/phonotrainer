package com.mapineda48.phonotrainer.core.requests.rules

import java.util.Locale

/**
 * Which files the queue takes and how they are named (architecture 8.3, server-contract 6.6). Provider metadata is
 * untrusted (security review M-4): the copy is always `requests/<id>/source.<ext>` with `<ext>` from [SUFFIXES]; the
 * display name is reduced to a base name without control or format characters, at most [MAX_NAME_CHARS] long, and is
 * used only for display and as the upload's `file_name`.
 */
internal object MediaTypes {
  /** The computer's media suffixes (`phonotrainer/jobs.py`), the closed list of copy extensions. */
  val SUFFIXES: List<String> = listOf("webm", "mp4", "mkv", "mov", "avi", "m4v", "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac")

  private val VIDEO_SUFFIXES = setOf("webm", "mp4", "mkv", "mov", "avi", "m4v")

  /** The longest display name kept, and the longest `file_name` sent (server-contract 6.6). */
  const val MAX_NAME_CHARS: Int = 200

  /** MIME types that name one of [SUFFIXES]; anything else (AMR, 3GP voice notes…) is refused (SH-AC10). */
  private val MIME_SUFFIX: Map<String, String> = mapOf(
    "video/webm" to "webm",
    "video/mp4" to "mp4",
    "video/x-matroska" to "mkv",
    "video/quicktime" to "mov",
    "video/x-msvideo" to "avi",
    "video/avi" to "avi",
    "video/x-m4v" to "m4v",
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/wave" to "wav",
    "audio/vnd.wave" to "wav",
    "audio/mp4" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/m4a" to "m4a",
    "audio/flac" to "flac",
    "audio/x-flac" to "flac",
    "audio/ogg" to "ogg",
    "application/ogg" to "ogg",
    "audio/opus" to "opus",
    "audio/aac" to "aac",
    "audio/aacp" to "aac",
    "audio/x-aac" to "aac",
    "audio/webm" to "webm",
  )

  /**
   * The copy's extension: the display name's suffix when it is one of [SUFFIXES], otherwise the MIME type's; null when
   * neither names an accepted type (nothing is created, SH-AC10).
   */
  fun extensionOf(displayName: String?, mimeType: String?): String? {
    val suffix = displayName?.let(::baseName)?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
    if (suffix != null && suffix in SUFFIXES) return suffix
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    return MIME_SUFFIX[mime]
  }

  /** Whether the file is a video ("Audio only" extracts its audio track); `.webm` and `.mp4` count as video. */
  fun isVideo(extension: String?, mimeType: String?): Boolean {
    val mime = mimeType?.lowercase(Locale.ROOT)
    if (mime != null && mime.startsWith("audio/")) return false
    if (mime != null && mime.startsWith("video/")) return true
    return extension?.lowercase(Locale.ROOT) in VIDEO_SUFFIXES
  }

  /**
   * The display name as the queue keeps it: the last path segment of whatever the provider said, without control
   * (`Cc`) or format (`Cf`) characters, trimmed, at most [MAX_NAME_CHARS] characters; null when nothing is left.
   */
  fun safeDisplayName(raw: String?): String? {
    if (raw == null) return null
    val cleaned = Titles.clean(baseName(raw))
    return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
  }

  /**
   * The upload's `file_name` (server-contract 6.6): the safe display name with the copy's extension (an extracted
   * audio track is `.m4a`, `.ogg` or `.webm`), never longer than [MAX_NAME_CHARS] and never a dot file.
   */
  fun uploadName(displayName: String?, extension: String): String {
    val stem = safeDisplayName(displayName)
      ?.let { name -> if (name.contains('.')) name.substringBeforeLast('.') else name }
      ?.trim()
      ?.trimStart('.')
      ?.takeIf { it.isNotEmpty() }
      ?: DEFAULT_STEM
    val room = MAX_NAME_CHARS - extension.length - 1
    return stem.codePointPrefix(room).trimEnd() + "." + extension
  }

  private fun baseName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')

  private fun String.codePointPrefix(max: Int): String {
    if (codePointCount(0, length) <= max) return this
    return substring(0, offsetByCodePoints(0, max))
  }

  private const val DEFAULT_STEM = "upload"
}
