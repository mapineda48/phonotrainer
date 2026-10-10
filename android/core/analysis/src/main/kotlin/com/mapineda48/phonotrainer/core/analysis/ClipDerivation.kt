package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.Spotlight

/**
 * One short ready for the `short` table (architecture 4.2): its bounds, its 1-based [ordinal] in the clip ("Short 4
 * of 15"), the [defaultSpotlight] of rules 3 to 5 (null: "No labelled change here", the short plays only In order)
 * and its [markedWords] (words with at least one change).
 */
data class DerivedShort(
  val bounds: ShortBounds,
  val ordinal: Int,
  val defaultSpotlight: Spotlight?,
  val markedWords: Int,
)

/** Everything the indexer stores for one clip: [shorts] in order and the [occurrences] rows. */
data class DerivedClip(val shorts: List<DerivedShort>, val occurrences: List<OccurrenceRecord>) {
  /** The rule version the [shorts] were derived with (`index_rule_version`). */
  val ruleVersion: Int get() = ShortsV1.RULE_VERSION
}

/**
 * The derivation step of the indexer (architecture 9.5: parse → derive shorts → derive occurrences), as one pure
 * function so WP-10's `ClipIndexer` only stores the result in one transaction.
 */
object ClipDerivation {
  fun derive(clip: ClipData, advice: ChangeAdvice): DerivedClip {
    val shorts = ShortsV1.derive(clip)
    val records = OccurrenceExtractor.extract(clip, shorts, advice)
    val byShort = records.groupBy { it.shortFirstSegment }
    val derived = shorts.mapIndexed { i, bounds ->
      val rows = byShort[bounds.firstSegment].orEmpty()
      DerivedShort(
        bounds = bounds,
        ordinal = i + 1,
        defaultSpotlight = SpotlightRules.choose(rows, SpotlightMode.Default),
        markedWords = rows.mapTo(HashSet()) { it.word }.size,
      )
    }
    return DerivedClip(derived, records)
  }
}
