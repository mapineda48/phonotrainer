package com.mapineda48.phonotrainer.core.requests.rules

/**
 * The text behind "Show details" (spec F-20-AC7): what the computer said about a failure, which may carry its
 * downloader's terminal output. It is untrusted, so terminal escape sequences (CSI, OSC and the other `ESC` forms,
 * and the 8-bit CSI) are removed with every control and format character except line breaks and tabs; carriage
 * returns become line breaks, runs of blank lines shrink to one, and at most [MAX_CHARS] characters are kept.
 */
internal object Details {
  const val MAX_CHARS: Int = 2_000

  private val ESCAPES = Regex(
    // CSI (ESC [ or the 8-bit CSI): parameters, intermediates, one final byte.
    "(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]" +
      // OSC: up to BEL or the string terminator (ESC \).
      "|\u001B][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?" +
      // Any other escape: ESC and the byte after it.
      "|\u001B[@-Z\\\\-_]?",
  )
  private val BLANK_LINES = Regex("\n[ \t]*(?:\n[ \t]*)+")

  fun clean(raw: String): String {
    val stripped = ESCAPES.replace(raw.replace("\r\n", "\n").replace('\r', '\n'), "")
    val out = StringBuilder(stripped.length)
    stripped.codePoints().forEach { point ->
      val type = Character.getType(point)
      val keep = point == '\n'.code || point == '\t'.code ||
        (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt())
      if (keep) out.appendCodePoint(point)
    }
    val text = BLANK_LINES.replace(out.toString(), "\n").trim()
    return if (text.codePointCount(0, text.length) <= MAX_CHARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_CHARS)).trimEnd()
  }
}
