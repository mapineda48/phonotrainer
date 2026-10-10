package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef

/** Which spotlight a short shows: it depends on the feed mode it is played in (spec F-07 item 3). */
sealed interface SpotlightMode {
  /** Mix, In order, and a set's shorts that are not saved: rules 3 to 5 (precomputed in the `short` row). */
  data object Default : SpotlightMode

  /** A change mode ("only flapping"): the clearest candidate with [label]. */
  data class Change(val label: ChangeLabel) : SpotlightMode

  /** Saved mode, or a saved short in a set: the saved [word] (and its saved [change], when it has one). */
  data class Saved(val word: WordRef, val change: ChangeLabel? = null) : SpotlightMode
}

/**
 * The spotlight rule (spec F-07 item 3, architecture 9.3; work-plan WP-12 calls it "Spotlight", the name of the domain
 * type it returns). Candidates of a short are its words with at least one change that are not low confidence, last at
 * least 60 ms and have a heard form: exactly the [OccurrenceRecord.eligible] rows of the short, so a change mode's
 * count and what it plays agree (spec D-48). The order is deterministic, so a short shows the same spotlight in the
 * same mode every time (F-07-AC8):
 * 1. Saved mode, and a saved short in a set: the saved word;
 * 2. a change mode: the clearest candidate with that change;
 * 3. otherwise a "Safe to produce" change before a "Recognize only" one;
 * 4. then the lowest `diff_cost` (the clearest alignment, web `pickExamples`);
 * 5. ties: the earliest word, then (two labels of one word with the same advice) the label in alphabetical order.
 *
 * A word whose change crosses into the next word ([OccurrenceRecord.crossesBoundary]) plays with it.
 */
object SpotlightRules {
  /**
   * The spotlight of a short among [records] (the short's `occurrence` rows, eligible or not; rows of other shorts
   * must not be passed), in [mode]:
   * - [SpotlightMode.Default]: null when the short has no candidate ("No labelled change here").
   * - [SpotlightMode.Change]: null when no candidate has that change (such a short is not in that mode's sequence).
   * - [SpotlightMode.Saved]: the saved word with its saved change when the word still has it, else with its best
   *   change by rules 3 to 5, even if it is not a candidate (the learner chose it); when the saved word has no change
   *   in [records], the default spotlight.
   */
  fun choose(records: List<OccurrenceRecord>, mode: SpotlightMode): Spotlight? = when (mode) {
    SpotlightMode.Default -> records.filter { it.eligible }.minWithOrNull(DEFAULT_ORDER)?.toSpotlight()
    is SpotlightMode.Change ->
      records.filter { it.eligible && it.change == mode.label }.minWithOrNull(CLEAREST_ORDER)?.toSpotlight()
    is SpotlightMode.Saved -> {
      val ofWord = records.filter { it.word == mode.word }
      val saved = ofWord.firstOrNull { it.change == mode.change } ?: ofWord.minWithOrNull(DEFAULT_ORDER)
      saved?.toSpotlight() ?: choose(records, SpotlightMode.Default)
    }
  }

  /** Whether the short whose rows are [records] is played in the change mode of [label] (it has a candidate). */
  fun hasCandidate(records: List<OccurrenceRecord>, label: ChangeLabel): Boolean =
    records.any { it.eligible && it.change == label }

  private fun OccurrenceRecord.toSpotlight() = Spotlight(word, change, advice, crossesBoundary)

  /** Rules 4 and 5: lowest `diff_cost`, earliest word, then the label. */
  private val CLEAREST_ORDER: Comparator<OccurrenceRecord> =
    compareBy<OccurrenceRecord> { it.diffCost }
      .thenBy { it.word.segment }
      .thenBy { it.word.word }
      .thenBy { it.change.value }

  /** Rule 3 first ("Safe to produce" before "Recognize only"), then [CLEAREST_ORDER]. */
  private val DEFAULT_ORDER: Comparator<OccurrenceRecord> =
    compareBy<OccurrenceRecord> { if (it.advice == Advice.PRODUCE) 0 else 1 }.then(CLEAREST_ORDER)
}
