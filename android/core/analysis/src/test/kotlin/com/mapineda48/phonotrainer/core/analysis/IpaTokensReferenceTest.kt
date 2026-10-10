package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.Variant
import org.junit.Test

/**
 * `:core:common`'s [IpaTokenizer] against the computer's real `reference.ipa_tokens` (the snapshot `:core:content`
 * bundles): every multi-character symbol of the inventory stays one symbol, both readings, and the web's cases hold
 * with the full table.
 */
class IpaTokensReferenceTest {
  private val tokens = ReferenceSnapshot.ipaTokens
  private val tokenizer = IpaTokenizer(tokens)

  @Test
  fun theSnapshotPublishesMultiCharacterSymbolsLongestFirst() {
    assertThat(tokens).isNotEmpty()
    assertThat(tokens).containsAtLeast("aɪ", "tʃ", "dʒ", "ɑːɹ", "t̚", "n̩")
    assertThat(tokens.all { it.length > 1 }).isTrue()
    assertThat(tokens.map { it.length }).isInOrder(Comparator.reverseOrder<Int>())
  }

  @Test
  fun everyInventorySymbolIsOneSymbol() {
    for (token in tokens) {
      assertWithMessage(token).that(tokenizer.tokenize(token)).containsExactly(token)
      assertWithMessage(token).that(tokenizer.split(token)).containsExactly(token)
      assertWithMessage(token).that(tokenizer.split("ˈ$token")).containsExactly("ˈ$token")
      assertWithMessage(token).that(tokenizer.containsSymbol("b${token}d", token)).isTrue()
    }
  }

  @Test
  fun theWebCasesHoldWithTheFullTable() {
    assertThat(tokenizer.split("baɪ")).containsExactly("b", "aɪ").inOrder()
    assertThat(tokenizer.split("bʌdʒɪt")).containsExactly("b", "ʌ", "dʒ", "ɪ", "t").inOrder()
    assertThat(tokenizer.split("tənˈaɪt")).containsExactly("t", "ə", "n", "ˈaɪ", "t").inOrder()
    assertThat(tokenizer.tokenize("ðæt̚")).containsExactly("ð", "æ", "t̚").inOrder()
    assertThat(tokenizer.tokenize("ˈbʌʔn̩")).containsExactly("b", "ʌ", "ʔ", "n̩").inOrder()
    assertThat(tokenizer.tokenize("ɡɑtʃə")).containsExactly("ɡ", "ɑ", "tʃ", "ə").inOrder()
  }

  @Test
  fun everyDictionaryFormOfACurrentAnalysisSplitsIntoItsPhones() {
    // The dictionary row of the Compare step: each symbol marked heard or not against this word's sounds only.
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val water = clip.phrases[0].words[4]
    assertThat(tokenizer.dictionaryPhones(water).map { it.symbol to it.heard })
      .containsExactly("w" to true, "ˈɔ" to true, "t" to false, "ɚ" to true).inOrder()
    val nine = clip.phrases[1].words[4]
    assertThat(tokenizer.dictionaryPhones(nine).map { it.symbol })
      .containsExactly("n", "ˈaɪ", "n", "θ", "ˈɝ", "t", "i").inOrder()
  }
}
