package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.WordRef

/**
 * Clean examples of a change (D-16; the web's `features/learn/corpus.ts` `pickExamples`): playable ones only, the
 * clearest alignment first (lowest `diff_cost`; the most divergent are mostly alignment noise), one per word, up to a
 * limit. Ties keep a fixed order (job, phrase, word), so the same corpus always gives the same examples.
 */
object ExamplePicker {
  /** Learn's phenomenon page and the quiz explanation (web default). */
  const val LIMIT: Int = 8

  /** The IPA chart's symbol examples (web `IpaChart`'s limit). */
  const val SYMBOL_LIMIT: Int = 6

  /** Up to [limit] examples among [records]: eligible rows, clearest first, one per [OccurrenceRecord.wordKey]. */
  fun pick(records: List<OccurrenceRecord>, limit: Int = LIMIT): List<OccurrenceRecord> =
    pickBy(records.filter { it.eligible }, limit, { it.wordKey }, { it.diffCost }) { it.word }

  /**
   * The same rule over domain [Occurrence]s, which a repository returns only when they are eligible: clearest first,
   * one per word ([WordKey] of the text).
   */
  fun pickOccurrences(occurrences: List<Occurrence>, limit: Int = LIMIT): List<Occurrence> =
    pickBy(occurrences, limit, { WordKey.of(it.wordText) }, { it.diffCost }) { it.word }

  /**
   * Examples of an IPA symbol (the IPA chart; architecture 9.4): eligible rows whose realized OR dictionary IPA holds
   * [symbol], picked like [pick]. Both forms are tokenized by [tokenizer] (the reference's `ipa_tokens`, so "t̚" and
   * "tʃ" are not a "t"; stress and length marks ignored), so a flapped /t/ is still an example of "t" through its
   * dictionary form. The web's chart reads the heard form only; 9.4 widens it on purpose.
   */
  fun withSymbol(
    records: List<OccurrenceRecord>,
    symbol: String,
    tokenizer: IpaTokenizer,
    limit: Int = SYMBOL_LIMIT,
  ): List<OccurrenceRecord> = pick(
    records.filter { tokenizer.containsSymbol(it.realizedIpa, symbol) || tokenizer.containsSymbol(it.dictIpa, symbol) },
    limit,
  )

  private fun <T> pickBy(
    items: List<T>,
    limit: Int,
    key: (T) -> String,
    cost: (T) -> Double,
    ref: (T) -> WordRef,
  ): List<T> {
    if (limit <= 0) return emptyList()
    val order = compareBy<T> { cost(it) }
      .thenBy { ref(it).jobId.value }
      .thenBy { ref(it).segment }
      .thenBy { ref(it).word }
    val seen = HashSet<String>()
    val out = ArrayList<T>()
    for (item in items.sortedWith(order)) {
      val wordKey = key(item)
      if (wordKey.isEmpty() || !seen.add(wordKey)) continue
      out += item
      if (out.size >= limit) break
    }
    return out
  }
}
