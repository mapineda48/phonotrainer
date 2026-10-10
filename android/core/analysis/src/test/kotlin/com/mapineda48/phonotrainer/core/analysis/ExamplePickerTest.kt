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
    realized: String = "ɡɛɾ",
    job: String = "job-1",
  ) = OccurrenceRecord(
    word = WordRef(JobId(job), 0, index), change = ChangeLabel("flapping"), shortFirstSegment = 0, wordText = word,
    wordKey = WordKey.of(word), dictIpa = "ɡˈɛt", realizedIpa = realized, startMs = 1_000, endMs = 1_300,
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

  @Test
  fun symbolExamplesUseTheInventorysSymbols() {
    val tokenizer = IpaTokenizer(ReferenceSnapshot.ipaTokens)
    val rows = listOf(
      row("that", 1, realized = "ðæt̚"),
      row("water", 2, realized = "wɔɾɚ"),
      row("gotcha", 3, realized = "ɡɑtʃə"),
      row("eat", 4, realized = "it"),
    )
    // "t̚" and "tʃ" are not a plain "t"
    assertThat(ExamplePicker.withSymbol(rows, "t", tokenizer).map { it.wordText }).containsExactly("eat")
    assertThat(ExamplePicker.withSymbol(rows, "t̚", tokenizer).map { it.wordText }).containsExactly("that")
    assertThat(ExamplePicker.withSymbol(rows, "ɾ", tokenizer, limit = 6).map { it.wordText }).containsExactly("water")
  }
}
