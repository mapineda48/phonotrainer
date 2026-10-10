package com.mapineda48.phonotrainer.core.domain.model

/**
 * One column of the lesson's Compare step (web `phones.ts` `Column`), computed by `:core:analysis` `CompareRows`
 * and served by `ClipContentRepository.compareRows`. The rows of the word come first, then (when its change crosses
 * the boundary) the rows of the next word, each group in time order.
 */
data class CompareRow(
  /** The expected sound's symbol (`canonical_aligned`): the dictionary form placed in time, or, with espeak, the
   *  form "Expected by the aligner". Null for an added sound. */
  val dictionary: String?,
  /** The heard sound's symbol (`realized_aligned`). Null for a sound not heard. */
  val said: String?,
  val status: CompareStatus,
  /** Reserved: the change this sound shows. Nothing attributes a label to a single sound yet, so it is null in v1. */
  val change: ChangeLabel?,
  // WP-12 refinement (additive):
  /** The expected sound with its time ("Play this sound"); [dictionary] is its symbol. */
  val dictionaryPhone: TimedPhone? = null,
  /** The heard sound with its time; [said] is its symbol. */
  val saidPhone: TimedPhone? = null,
  /** True for a row of the next word (web `Column.word == 1`): the divider and the per-word header go before the
   *  first such row. */
  val nextWord: Boolean = false,
)

/** Web `Op`: = same, ≠ changed, – dropped ("not heard"), + added. */
enum class CompareStatus { SAME, CHANGED, NOT_HEARD, ADDED }

/** One word × change of a playable clip (the `occurrence` table, architecture 4.2; `OccurrenceExtractor`). */
data class Occurrence(
  val word: WordRef,
  val shortFirstSegment: Int,
  val wordText: String,
  val dictIpa: String?,
  val realizedIpa: String?,
  val startMs: Long,
  val endMs: Long,
  val change: ChangeLabel,
  val diffCost: Double,
  val advice: Advice,
  val family: Family,
  val crossesBoundary: Boolean,
  val nextWordText: String?,
  val clipTitle: String,
  // WP-12 refinement (additive):
  /**
   * The word's other labels (its other `occurrence` rows: same job, segment and word). The quiz's "Which change?"
   * never offers a label the word also has (web `quiz.ts` `changeOptions`, `present`). No new column: WP-10 reads
   * them from the same table.
   */
  val otherChanges: List<ChangeLabel> = emptyList(),
)

data class WordVariant(val realizedIpa: String, val count: Int, val examples: List<Occurrence>)

data class CorpusTotals(val clips: Int, val shorts: Int, val words: Int, val occurrences: Int)

/**
 * `OccurrenceRepository.changeCounts()`, spec D-48: [shorts] = playable shorts with a spotlight candidate of the
 * change = what its change mode plays (Home chips, mode picker, Learn cards); [occurrences] = its eligible
 * occurrences in playable clips (the phenomenon page only).
 */
data class ChangeCount(val shorts: Int, val occurrences: Int)

data class FeedSequence(val mode: FeedMode, val items: List<ShortInfo>, val startIndex: Int)
