package com.mapineda48.phonotrainer.core.ui.ipa

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import org.junit.Test

/**
 * `IpaText` reads IPA symbol by symbol with `:core:common`'s `IpaTokenizer` (architecture 2.2, design 4.3, A-05): one
 * word is cut exactly as the tokenizer cuts it, and the gaps between words (white space and the ‿ link) are never read
 * as symbols.
 */
class IpaReadingTest {
  /** `reference.ipa_tokens` as the computer publishes it. */
  private val tokens = SampleReference.ipaTokens
  private val reading = IpaReading(tokens)
  private val tokenizer = IpaTokenizer(tokens)

  /** Words built from every multi-character symbol of the reference, with stress marks and diacritics around them. */
  private val words: List<String> = tokens.flatMap { listOf(it, "b$it", "ˈ${it}t", "s${it}ʰ", "ˌk${it}n̩") } +
    listOf("tənˈaɪt", "bʌʔn̩", "ˈwɔɾɚ", "ðæt̚", "tʰɪk", "ʰa", "ˌʌnˈ", "pɪ", "kɪ", "ɾʌp", "")

  @Test
  fun oneWordIsCutExactlyAsTheTokenizerCutsIt() {
    for (word in words) {
      assertWithMessage(word).that(reading.split(word)).containsExactlyElementsIn(tokenizer.split(word)).inOrder()
    }
  }

  @Test
  fun whiteSpaceAndTheLinkSeparateWordsAndAreNeverSymbols() {
    assertThat(reading.split("pɪ‿kɪ‿ɾʌp")).containsExactly("p", "ɪ", "k", "ɪ", "ɾ", "ʌ", "p").inOrder()
    assertThat(reading.split("ˈwɔɾɚ  baɪ\n‿ ðæt̚")).containsExactly("ˈw", "ɔ", "ɾ", "ɚ", "b", "aɪ", "ð", "æ", "t̚")
      .inOrder()
    // Each word is the tokenizer's cut of that word alone: a stress mark or a modifier letter next to a gap never
    // joins a phone of the word on the other side.
    val phrase = words.filter { it.isNotEmpty() }
    assertThat(reading.split(phrase.joinToString("‿"))).containsExactlyElementsIn(phrase.flatMap(tokenizer::split))
      .inOrder()
    assertThat(reading.split(" ‿\t")).isEmpty()
  }

  @Test
  fun aSymbolIsNamedAsWrittenThenWithoutStressAndLength() {
    val named = IpaReading(tokens) { mapOf("aɪ" to "the vowel of price", "i" to "close front vowel")[it] }
    assertThat(named.nameOf("ˈaɪ")).isEqualTo("the vowel of price")
    assertThat(named.nameOf("iː")).isEqualTo("close front vowel")
    assertThat(named.nameOf("ɾ")).isNull()
    assertThat(IpaReading.bare("ˌiː")).isEqualTo(IpaTokenizer.bareSymbol("ˌiː"))
  }
}
