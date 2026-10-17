package com.mapineda48.phonotrainer.core.requests.rules

/**
 * A request's provisional title (spec 8.2 rule 5): the shared subject, or the shared text around the link, until the
 * computer reports the real title. Every title is untrusted text, so it is cleaned the same way: control (`Cc`) and
 * format (`Cf`) characters removed, white space collapsed, at most [MAX_CHARS] characters.
 */
internal object Titles {
  const val MAX_CHARS: Int = 200

  /** Characters that only separate a title from its link in shared text ("Great scene - https://…"). */
  private const val SEPARATORS = " \t-–—|:·•,;"

  fun clean(raw: String): String {
    val out = StringBuilder(raw.length)
    var space = false
    raw.codePoints().forEach { point ->
      when {
        Character.isWhitespace(point) || Character.isSpaceChar(point) || point == LINE_SEPARATOR || point == PARAGRAPH_SEPARATOR -> {
          space = out.isNotEmpty()
        }
        Character.getType(point) == Character.CONTROL.toInt() || Character.getType(point) == Character.FORMAT.toInt() -> Unit
        else -> {
          if (space) out.append(' ')
          space = false
          out.appendCodePoint(point)
        }
      }
    }
    val text = out.toString()
    return if (text.codePointCount(0, text.length) <= MAX_CHARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_CHARS)).trimEnd()
  }

  /**
   * The provisional title of a shared link: [subject] when it holds text that is not a link, otherwise the shared text
   * without [link]; null when neither has any (the request then reads "{site} link").
   */
  fun hint(sharedText: String, subject: String?, link: String): String? {
    subject?.let(::clean)?.takeIf { it.isNotEmpty() && !it.contains("://") }?.let { return it }
    val around = clean(sharedText.replace(link, " ")).trim { it in SEPARATORS }
    return around.takeIf { it.isNotEmpty() && !it.contains("://") }
  }

  private const val LINE_SEPARATOR = 0x2028
  private const val PARAGRAPH_SEPARATOR = 0x2029
}
