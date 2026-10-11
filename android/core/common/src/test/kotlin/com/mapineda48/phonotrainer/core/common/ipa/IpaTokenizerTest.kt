package com.mapineda48.phonotrainer.core.common.ipa

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Word
import org.junit.Test

/**
 * The cases of the web client's `features/analysis/lib/lib.test.ts` (`splitPhones`, `dictionaryPhones`) and
 * `features/learn/learn.test.tsx` (`tokenizeIpa`, `containsSymbol`), ported one for one, plus the edges the port must
 * keep. `:core:analysis`'s `IpaTokensReferenceTest` runs the same tokenizer against the bundled reference snapshot.
 */
class IpaTokenizerTest {
  /** The web test fixture's `reference.ipa_tokens` (`web/src/test/fixtures.tsx`). */
  private val webFixtureTokens = listOf(
    "ɑːɹ", "ɔːɹ", "aɪə", "aɪɚ", "oːɹ", "aɪ", "aʊ", "eɪ", "oʊ", "ɔɪ", "tʃ", "dʒ",
    "iː", "uː", "ɑː", "ɔː", "ɜː", "ɪɹ", "ʊɹ", "ɛɹ", "iə", "eə", "əl", "ju",
  )

  /** `reference.ipa_tokens` as the computer publishes it (API 6; `web/src/test/reference.full.json`). */
  private val referenceTokens = listOf(
    "aɪə", "aɪɚ", "oːɹ", "ɑːɹ", "ɔːɹ", "aɪ", "aʊ", "b̚", "dʒ", "d̚", "eə", "eɪ", "iə", "iː", "ju", "k̚", "l̩",
    "m̩", "n̩", "oʊ", "p̚", "tʃ", "t̚", "uː", "ŋ̍", "ɑː", "ɔɪ", "ɔː", "əl", "ɛɹ", "ɜː", "ɡ̚", "ɪɹ", "ɾ̃", "ʊɹ",
  )

  private val lesson = IpaTokenizer(webFixtureTokens)
  private val corpus = IpaTokenizer(referenceTokens)

  @Test
  fun splitKeepsDiacriticsWithTheirBaseSymbol() {
    assertThat(lesson.split("bɛtɚ")).containsExactly("b", "ɛ", "t", "ɚ").inOrder()
    assertThat(lesson.split("bʌʔn̩")).containsExactly("b", "ʌ", "ʔ", "n̩").inOrder()
    assertThat(lesson.split("")).isEmpty()
  }

  @Test
  fun splitDoesNotCutMultiCharacterSymbols() {
    // "aɪ" split in two flagged half of a correctly spoken word as dropped
    assertThat(lesson.split("baɪ")).containsExactly("b", "aɪ").inOrder()
    assertThat(lesson.split("bʌdʒɪt")).containsExactly("b", "ʌ", "dʒ", "ɪ", "t").inOrder()
    assertThat(lesson.split("ɑːɹ")).containsExactly("ɑːɹ")
  }

  @Test
  fun splitAttachesTheStressMarkToThePhoneThatCarriesIt() {
    assertThat(lesson.split("bˈɛtɚ")).containsExactly("b", "ˈɛ", "t", "ɚ").inOrder()
    assertThat(lesson.split("tənˈaɪt")).containsExactly("t", "ə", "n", "ˈaɪ", "t").inOrder()
  }

  @Test
  fun splitEdges() {
    // a modifier letter joins the phone before it; a stress mark never does, and a lone trailing one is dropped
    assertThat(lesson.split("tʰɪk")).containsExactly("tʰ", "ɪ", "k").inOrder()
    assertThat(lesson.split("ˌʌnˈ")).containsExactly("ˌʌ", "n").inOrder()
    // a modifier at the very start has no phone to join
    assertThat(lesson.split("ʰa")).containsExactly("ʰ", "a").inOrder()
    // the order of the token list does not matter: the longest match wins
    assertThat(IpaTokenizer(listOf("ɑː", "ɑːɹ")).split("kɑːɹ")).containsExactly("k", "ɑːɹ").inOrder()
    assertThat(IpaTokenizer.PLAIN.split("baɪ")).containsExactly("b", "a", "ɪ").inOrder()
  }

  @Test
  fun tokenizeSplitsIntoInventorySymbolsKeepingDiacriticsAndDroppingStress() {
    assertThat(corpus.tokenize("ðæt̚")).containsExactly("ð", "æ", "t̚").inOrder()
    assertThat(corpus.tokenize("ˈbʌʔn̩")).containsExactly("b", "ʌ", "ʔ", "n̩").inOrder()
    assertThat(corpus.tokenize("ɡɑtʃə")).containsExactly("ɡ", "ɑ", "tʃ", "ə").inOrder()
  }

  @Test
  fun tokenizeDropsSeparators() {
    assertThat(corpus.tokenize("dɪd ‿ju. |ɡoʊ")).containsExactly("d", "ɪ", "d", "ju", "ɡ", "oʊ").inOrder()
    // length marks stay with the vowel even when no token names the long vowel
    assertThat(IpaTokenizer.PLAIN.tokenize("biːt")).containsExactly("b", "iː", "t").inOrder()
    assertThat(corpus.tokenize("")).isEmpty()
  }

  @Test
  fun containsSymbolDoesNotMistakeAnUnreleasedOrAnAffricateTForAPlainT() {
    assertThat(corpus.containsSymbol("ðæt̚", "t")).isFalse()
    assertThat(corpus.containsSymbol("ðæt̚", "t̚")).isTrue()
    assertThat(corpus.containsSymbol("ɡɑtʃə", "t")).isFalse()
    assertThat(corpus.containsSymbol("biːt", "i")).isTrue()
    assertThat(corpus.containsSymbol(null, "t")).isFalse()
    assertThat(corpus.containsSymbol("", "t")).isFalse()
  }

  @Test
  fun dictionaryPhonesAreCheckedAgainstWhatThisWordRealizedOnly() {
    val better = word(dictIpa = "bɛtɚ", realized = listOf("b", "ɛ", "ɾ", "ɚ"))
    assertThat(lesson.dictionaryPhones(better)).containsExactly(
      DictionaryPhone("b", heard = true),
      DictionaryPhone("ɛ", heard = true),
      DictionaryPhone("t", heard = false),
      DictionaryPhone("ɚ", heard = true),
    ).inOrder()
    // stress and length marks do not hide a match
    val beat = word(dictIpa = "bˈiːt", realized = listOf("b", "i", "t"))
    assertThat(lesson.dictionaryPhones(beat).map { it.heard }).containsExactly(true, true, true)
    assertThat(lesson.dictionaryPhones(word(dictIpa = null, realized = listOf("b")))).isEmpty()
  }

  @Test
  fun bareSymbolAndStripStress() {
    assertThat(IpaTokenizer.bareSymbol("ˈuː")).isEqualTo("u")
    assertThat(IpaTokenizer.bareSymbol("ˌɛ")).isEqualTo("ɛ")
    assertThat(IpaTokenizer.stripStress("tənˈaɪt")).isEqualTo("tənaɪt")
    assertThat(IpaTokenizer.stripStress("ˌʌnˈbiːt")).isEqualTo("ʌnbiːt")
  }

  @Test
  fun tokensAreKeptLongestFirstWithoutRepeatsOrEmptyEntries() {
    val tokenizer = IpaTokenizer(listOf("aɪ", "", "aɪə", "aɪ"))
    assertThat(tokenizer.tokens).containsExactly("aɪə", "aɪ").inOrder()
  }

  private fun word(dictIpa: String?, realized: List<String>) = Word(
    index = 0, text = "w", startS = 0.0, endS = 1.0, dictIpa = dictIpa, canonicalIpa = null, realizedIpa = null,
    canonicalPhones = emptyList(),
    realizedPhones = realized.mapIndexed { i, symbol -> TimedPhone(symbol, i * 0.06, i * 0.06 + 0.05) },
    changes = emptyList(), diffCost = 0.0, lowConfidence = false, boundaryLinkNext = false, lexicalForm = null,
    oov = false,
  )
}
