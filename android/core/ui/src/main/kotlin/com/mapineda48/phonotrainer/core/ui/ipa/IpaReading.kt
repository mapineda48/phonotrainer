package com.mapineda48.phonotrainer.core.ui.ipa

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer

/**
 * How `IpaText` reads IPA aloud (A-05): the inventory's multi-character symbols ([tokens], `reference.ipa_tokens`, so
 * "aɪ" stays one symbol) and each symbol's phonetic name ("voiced alveolar tap", `DidacticContent.phone(symbol).name`).
 * The app shell provides one built from the reference snapshot and the didactic content through [LocalIpaReading];
 * without it, symbols are read as they are written.
 */
@Immutable
class IpaReading(
  tokens: Collection<String> = emptyList(),
  private val names: (String) -> String? = { null },
) {
  private val tokenizer = IpaTokenizer(tokens)

  /** The name of [symbol], trying it as written, then without stress and length marks. */
  fun nameOf(symbol: String): String? = names(symbol) ?: bare(symbol).takeIf { it != symbol }?.let(names)

  /**
   * [ipa] cut into phones, word by word: white space and the ‿ link (design 4.3) separate the words and are never
   * symbols, and each word is cut by `:core:common`'s [IpaTokenizer.split] (a multi-character token first, else one
   * character plus the diacritics and modifier letters that follow it; a stress mark goes with the phone after it).
   */
  fun split(ipa: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (end in 0..ipa.length) {
      if (end < ipa.length && !isGap(ipa[end])) continue
      if (end > start) out += tokenizer.split(ipa.substring(start, end))
      start = end + 1
    }
    return out
  }

  companion object {
    private const val UNDERTIE = '‿'

    /** A gap between IPA words: white space or the undertie that links two words. */
    private fun isGap(char: Char): Boolean = char.isWhitespace() || char == UNDERTIE

    /** [symbol] without stress marks and the length mark. */
    fun bare(symbol: String): String = IpaTokenizer.bareSymbol(symbol)
  }
}

val LocalIpaReading = staticCompositionLocalOf { IpaReading() }
