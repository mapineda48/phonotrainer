package com.mapineda48.phonotrainer.core.connectivity

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Computer names as the phone shows and compares them (server-contract 2.3, design 9.26, security review L-2). A name
 * comes from whatever answered at an address: a discovery reply, `hello`, the pairing answer or `health`. Only the real
 * computer cleans its own name, so this module cleans every name again before it leaves the module, and compares
 * names for the same-name conflict on a key that ignores what cannot be seen (spec D-39, F-02-AC10).
 */
object ComputerNames {
  /** A shown name has at most this many user-perceived characters (grapheme clusters), like design 9.26's titles. */
  const val MAX_GRAPHEMES: Int = 60

  /** Shown when neither the answer nor the discovery reply carries a usable name. */
  const val FALLBACK: String = "PhonoTrainer computer"

  /** Longer input is cut before cleaning, so a hostile answer cannot make cleaning slow. */
  private const val MAX_INPUT_CHARS = 1_024
  private const val ELLIPSIS = "\u2026"

  /**
   * [raw] cleaned: format characters (`Cf`: bidirectional overrides, embeddings and isolates, zero-width characters,
   * the soft hyphen) and unpaired surrogates are removed; control characters and line and paragraph separators (`Cc`,
   * `Zl`, `Zp`) become a space, so two words never run together; runs of white space become one space; the result is
   * trimmed and kept to [MAX_GRAPHEMES] grapheme clusters (a longer name keeps its first 59, trimmed, and ends with
   * "…"). An empty result gives [fallback] cleaned the same way, or [FALLBACK].
   */
  fun clean(raw: String?, fallback: String? = null): String = tidy(raw) ?: tidy(fallback) ?: FALLBACK

  /**
   * The key two names are compared on: the name with invisible characters removed, in NFKC, case-folded and without
   * any white space. "Study desk", "STUDY DESK", "Studydesk" and "Study desk" written with a zero-width space, a soft
   * hyphen, bidirectional isolates or full-width letters all have one key.
   */
  fun conflictKey(name: String): String {
    val normalized = Normalizer.normalize(strip(name.take(MAX_INPUT_CHARS)), Normalizer.Form.NFKC)
    return normalized.uppercase(Locale.ROOT).lowercase(Locale.ROOT).filterNot(::isSpace)
  }

  private fun tidy(raw: String?): String? {
    if (raw == null) return null
    val text = collapse(strip(raw.take(MAX_INPUT_CHARS))).trim()
    return text.takeIf { it.isNotEmpty() }?.let(::cap)
  }

  private fun strip(raw: String): String {
    val out = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
      val codePoint = raw.codePointAt(index)
      when (Character.getType(codePoint).toByte()) {
        Character.FORMAT, Character.SURROGATE -> Unit
        Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> out.append(' ')
        else -> out.appendCodePoint(codePoint)
      }
      index += Character.charCount(codePoint)
    }
    return out.toString()
  }

  private fun collapse(text: String): String {
    val out = StringBuilder(text.length)
    var space = false
    for (char in text) {
      if (isSpace(char)) {
        if (!space) out.append(' ')
        space = true
      } else {
        out.append(char)
        space = false
      }
    }
    return out.toString()
  }

  private fun isSpace(char: Char): Boolean = char.isWhitespace() || Character.isSpaceChar(char)

  /** At most [MAX_GRAPHEMES] grapheme clusters; never splits an accented letter or an emoji. */
  private fun cap(text: String): String {
    val clusters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var count = 0
    var keep = 0
    var end = clusters.next()
    while (end != BreakIterator.DONE) {
      count++
      if (count == MAX_GRAPHEMES - 1) keep = end
      if (count > MAX_GRAPHEMES) return text.substring(0, keep).trimEnd() + ELLIPSIS
      end = clusters.next()
    }
    return text
  }
}
