package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import org.junit.Test

/** `pickExamples` (D-16): the web's case (`features/learn/learn.test.tsx`) and the symbol examples of the IPA chart. */
class ExamplePickerTest {
  private fun row(
    word: String,
    index: Int,
    diff: Double = 0.2,
    eligible: Boolean = true,
    realized: String? = "ɡɛɾ",
    job: String = "job-1",
    dict: String? = "ɡˈɛt",
  ) = OccurrenceRecord(
    word = WordRef(JobId(job), 0, index), change = ChangeLabel("flapping"), shortFirstSegment = 0, wordText = word,
    wordKey = WordKey.of(word), dictIpa = dict, realizedIpa = realized, startMs = 1_000, endMs = 1_300,
    diffCost = diff, lowConfidence = !eligible, eligible = eligible, crossesBoundary = false, nextWordText = "it",
    advice = Advice.PRODUCE, family = Family.TD,
  )

  /** The web fixture `flaps`. */
  private val flaps = listOf(
    row("get", 3, diff = 0.3),
    row("water", 7, diff = 0.1, realized = "wɔɾɚ"),
    row("Get", 9, diff = 0.05), // same word: shown once
    row("lot", 11, eligible = false), // not trustworthy
    row("it", 12, eligible = false), // cannot be played
  )

  @Test
  fun picksCleanPlayableExamplesOnePerWordClearestFirst() {
    assertThat(ExamplePicker.pick(flaps).map { it.wordText }).containsExactly("Get", "water").inOrder()
  }

  @Test
  fun respectsTheLimitAndKeepsAFixedOrderOnTies() {
    val many = (0 until 20).map { row("word${'a' + it}", it, diff = 0.1) }
    val picked = ExamplePicker.pick(many.reversed())
    assertThat(picked).hasSize(ExamplePicker.LIMIT)
    assertThat(picked.map { it.word.word }).isEqualTo((0 until 8).toList())
    assertThat(ExamplePicker.pick(many, limit = 0)).isEmpty()
    // a word with no letters (a numeral) has no key and is never an example
    assertThat(ExamplePicker.pick(listOf(row("9.30", 1, diff = 0.0), row("nine", 2)))).hasSize(1)
  }

  @Test
  fun theSameRuleOverDomainOccurrences() {
    val occurrences = OccurrenceExtractor.occurrences(flaps.filter { it.eligible }, "clip")
    assertThat(ExamplePicker.pickOccurrences(occurrences).map { it.wordText }).containsExactly("Get", "water").inOrder()
  }

  private val tokenizer = IpaTokenizer(ReferenceSnapshot.ipaTokens)

  private fun withSymbol(rows: List<OccurrenceRecord>, symbol: String, limit: Int = ExamplePicker.SYMBOL_LIMIT) =
    ExamplePicker.withSymbol(rows, symbol, tokenizer, limit).map { it.wordText }

  /** The heard form holds the symbol and the dictionary form does not ("t̚" and "ɾ" are never in a dictionary form). */
  @Test
  fun symbolExamplesMatchTheHeardForm() {
    val rows = listOf(
      row("that", 1, realized = "ðæt̚", dict = "ðˈæt"),
      row("water", 2, realized = "wɔɾɚ", dict = "wˈɔːtɚ"),
      row("gotcha", 3, realized = "ɡɑtʃə", dict = null),
      row("eat", 4, realized = "it", dict = null),
    )
    assertThat(withSymbol(rows, "t̚")).containsExactly("that")
    assertThat(withSymbol(rows, "ɾ")).containsExactly("water")
    // the heard form alone: "t̚" and "tʃ" are the inventory's symbols, not a plain "t"
    assertThat(withSymbol(rows.map { it.copy(dictIpa = null) }, "t")).containsExactly("eat")
  }

  /**
   * Architecture 9.4: "the realized or dictionary IPA contains the symbol". A /t/ that was flapped, unreleased or
   * glottalized is still an example of "t" through its dictionary form, which is tokenized like the heard form: stress
   * and length marks ignored, the inventory's multi-character symbols kept whole.
   */
  @Test
  fun symbolExamplesMatchTheDictionaryForm() {
    val rows = listOf(
      row("water", 1, diff = 0.1, realized = "wɑɾɚ", dict = "wˈɔːtɚ"),
      row("gotcha", 2, diff = 0.2, realized = "ɡɑɾə", dict = "ɡˈɑtʃə"),
      row("seat", 3, diff = 0.3, realized = "sit̚", dict = "sˈiːt"),
      row("cat", 4, diff = 0.4, realized = "kæʔ", dict = "kˈæt"),
      row("sat", 5, diff = 0.5, realized = "sæʔ", dict = "sˈæt", eligible = false),
    )
    // "gotcha" holds "tʃ", not "t"; "sat" is not eligible
    assertThat(withSymbol(rows, "t")).containsExactly("water", "seat", "cat").inOrder()
    assertThat(withSymbol(rows, "tʃ")).containsExactly("gotcha")
    // "wˈɔːtɚ": neither the stress mark nor the length mark hides "ɔ"
    assertThat(withSymbol(rows, "ɔ")).containsExactly("water")
  }

  /** A word that holds the symbol in both forms is one example: the pick's one-per-word rule spans both readings. */
  @Test
  fun symbolExamplesStayOnePerWordAcrossBothForms() {
    val rows = listOf(
      row("better", 1, diff = 0.3, realized = "bɛɾɚ", dict = "bˈɛtɚ"),
      row("Better", 2, diff = 0.1, realized = "bɛtɚ", dict = "bˈɛtɚ"),
      row("rate", 3, diff = 0.2, realized = "ɹeɪʔ", dict = null),
    )
    assertThat(ExamplePicker.withSymbol(rows, "t", tokenizer).map { it.word.word }).containsExactly(2)
    assertThat(ExamplePicker.withSymbol(rows, "ɾ", tokenizer).map { it.word.word }).containsExactly(1)
    assertThat(withSymbol(rows, "t", limit = 0)).isEmpty()
  }
}
