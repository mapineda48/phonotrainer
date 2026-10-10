package com.mapineda48.phonotrainer.core.common.ipa

import com.mapineda48.phonotrainer.core.domain.model.Word

/**
 * Cuts IPA strings into the inventory's symbols (architecture 2.2: `:core:ui`'s `IpaText`, the lesson's Compare step
 * and the corpus's symbol examples all need it, and none of them may depend on `:core:analysis`).
 *
 * [tokens] are the multi-character symbols of the inventory: `reference.ipa_tokens` ("aɪ", "tʃ", "ɑːɹ", "t̚", …,
 * published longest first). Without them "aɪ" would be cut in two and half of a correctly spoken word would read as
 * dropped. The order given does not matter: two different tokens of one length can never both match at one place,
 * so the tokenizer simply tries the longest first.
 *
 * Two readings, each a port of the web client's:
 * - [split] (web `features/analysis/lib/phones.ts` `splitPhones`): the dictionary form phone by phone, a stress mark
 *   kept with the phone that carries it ("tənˈaɪt" → t, ə, n, ˈaɪ, t).
 * - [tokenize] (web `features/learn/corpus.ts` `tokenizeIpa`): symbols for searching, stress marks, spaces, ‿, "."
 *   and "|" dropped ("ˈbʌʔn̩" → b, ʌ, ʔ, n̩).
 *
 * Indices are UTF-16 code units, exactly as in the web client, so both agree symbol for symbol.
 */
class IpaTokenizer(tokens: Collection<String>) {
  private val multi: List<String> = tokens.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

  /** The multi-character symbols this tokenizer knows, longest first. */
  val tokens: List<String> get() = multi

  /**
   * An untimed IPA string (the dictionary form) cut into phones: a multi-character token first, else one character
   * plus the diacritics and modifier letters that follow it; a stress mark ("ˈ", "ˌ") is attached to the phone after
   * it. A trailing stress mark with no phone after it is dropped. Web `splitPhones`.
   */
  fun split(ipa: String): List<String> {
    val out = ArrayList<String>()
    val pending = StringBuilder() // stress marks waiting for their phone
    var i = 0
    while (i < ipa.length) {
      val char = ipa[i]
      if (isStress(char)) {
        pending.append(char)
        i += 1
        continue
      }
      val token = tokenAt(ipa, i)
      if (token != null) {
        out += pending.toString() + token
        pending.setLength(0)
        i += token.length
        continue
      }
      if (pending.isEmpty() && out.isNotEmpty() && isPhoneModifier(char)) {
        out[out.lastIndex] = out.last() + char
      } else {
        out += pending.toString() + char
      }
      pending.setLength(0)
      i += 1
    }
    return out
  }

  /**
   * Symbols for searching: a multi-character token first, else one character plus the combining marks and length
   * marks that follow it. Stress marks, white space, "‿", "." and "|" are dropped. Web `tokenizeIpa`.
   */
  fun tokenize(text: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
      val char = text[i]
      if (isIgnored(char)) {
        i += 1
        continue
      }
      val token = tokenAt(text, i)
      if (token != null) {
        out += token
        i += token.length
        continue
      }
      var j = i + 1
      while (j < text.length && isCombiningOrLength(text[j])) j += 1
      out += text.substring(i, j)
      i = j
    }
    return out
  }

  /** Whether [text] holds [symbol], ignoring stress and length marks ("biːt" holds "i"; "ðæt̚" does not hold "t"). */
  fun containsSymbol(text: String?, symbol: String): Boolean {
    if (text.isNullOrEmpty()) return false
    val target = bareSymbol(symbol)
    return tokenize(text).any { bareSymbol(it) == target }
  }

  /**
   * The dictionary form of [word], symbol by symbol, each marked heard or not against what THIS word realized only
   * (web `dictionaryPhones`): the next word's sounds never stand in for this word's. Empty without a dictionary form.
   */
  fun dictionaryPhones(word: Word): List<DictionaryPhone> {
    val ipa = word.dictIpa ?: return emptyList()
    val said = word.realizedPhones.mapTo(HashSet()) { bareSymbol(it.symbol) }
    return split(ipa).map { DictionaryPhone(it, bareSymbol(it) in said) }
  }

  private fun tokenAt(text: String, index: Int): String? = multi.firstOrNull { text.startsWith(it, index) }

  companion object {
    /** A tokenizer with no multi-character symbol: every phone is one character plus its marks. */
    val PLAIN: IpaTokenizer = IpaTokenizer(emptyList())

    /** "/uː/" and "/u/", "/ˈɛ/" and "/ɛ/" are the same phone when rows are compared (web `bareSymbol`). */
    fun bareSymbol(symbol: String): String = symbol.filterNot { isStress(it) || it == LONG }

    /** The dictionary IPA without stress marks, comparable with a realized string (web `stripStress`). */
    fun stripStress(ipa: String): String = ipa.filterNot(::isStress)

    private const val PRIMARY_STRESS = 'ˈ'
    private const val SECONDARY_STRESS = 'ˌ'
    private const val LONG = 'ː'
    private const val HALF_LONG = 'ˑ'
    private const val UNDERTIE = '‿'
    private const val BYTE_ORDER_MARK = '﻿'

    private fun isStress(char: Char): Boolean = char == PRIMARY_STRESS || char == SECONDARY_STRESS

    /** Web `phones.ts` `COMBINING`: spacing modifier letters, combining diacritics (two blocks), superscript n. */
    private fun isPhoneModifier(char: Char): Boolean =
      char in 'ʰ'..'˿' || char in '̀'..'ͯ' || char in '᷀'..'᷿' || char == 'ⁿ'

    /** Web `corpus.ts` `COMBINING`: combining diacritics and the length marks. */
    private fun isCombiningOrLength(char: Char): Boolean = char in '̀'..'ͯ' || char == LONG || char == HALF_LONG

    /** Web `corpus.ts` `IGNORED`: white space (JavaScript's `\s`), stress marks, "‿", "." and "|". */
    private fun isIgnored(char: Char): Boolean =
      char.isWhitespace() || char == BYTE_ORDER_MARK || isStress(char) || char == UNDERTIE || char == '.' || char == '|'
  }
}

/** One symbol of a dictionary form and whether this word's heard sounds include it (lesson step 2). */
data class DictionaryPhone(val symbol: String, val heard: Boolean)
