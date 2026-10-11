package com.mapineda48.phonotrainer.core.ui.ipa

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

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
  private val multi: List<String> = tokens.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

  /** The name of [symbol], trying it as written, then without stress and length marks. */
  fun nameOf(symbol: String): String? = names(symbol) ?: bare(symbol).takeIf { it != symbol }?.let(names)

  /**
   * [ipa] cut into phones: a multi-character token first, else one character plus the diacritics and modifier letters
   * that follow it; stress marks attach to the phone after them. The same reading as `IpaTokenizer.split`
   * (`:core:common`, a port of the web's `splitPhones`), kept here until this module moves onto it.
   */
  fun split(ipa: String): List<String> {
    val out = ArrayList<String>()
    val pending = StringBuilder()
    var i = 0
    while (i < ipa.length) {
      val char = ipa[i]
      if (isStress(char)) {
        pending.append(char)
        i += 1
        continue
      }
      if (char.isWhitespace() || char == UNDERTIE) {
        i += 1
        continue
      }
      val token = multi.firstOrNull { ipa.startsWith(it, i) }
      if (token != null) {
        out += pending.toString() + token
        pending.setLength(0)
        i += token.length
        continue
      }
      if (pending.isEmpty() && out.isNotEmpty() && isModifier(char)) {
        out[out.lastIndex] = out.last() + char
      } else {
        out += pending.toString() + char
      }
      pending.setLength(0)
      i += 1
    }
    return out
  }

  companion object {
    private const val UNDERTIE = '‿'

    /** [symbol] without stress marks and the length mark. */
    fun bare(symbol: String): String = symbol.filterNot { isStress(it) || it == 'ː' }

    private fun isStress(char: Char): Boolean = char == 'ˈ' || char == 'ˌ'

    /** Spacing modifier letters, combining diacritics (two blocks) and superscript n (web `phones.ts`). */
    private fun isModifier(char: Char): Boolean =
      char in 'ʰ'..'˿' || char in '̀'..'ͯ' || char in '᷀'..'᷿' || char == 'ⁿ'
  }
}

val LocalIpaReading = staticCompositionLocalOf { IpaReading() }
