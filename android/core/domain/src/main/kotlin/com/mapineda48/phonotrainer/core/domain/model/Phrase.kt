package com.mapineda48.phonotrainer.core.domain.model

/**
 * One phrase: an `analysis.json` `segments[i]` (a Whisper segment, about one speaker turn). Times are absolute
 * seconds on the analysed audio's timeline (`audio.wav`), as everywhere in [ClipData].
 *
 * Not a data class (it holds [FloatArray]s): compare phrases by [index] within one [ClipData].
 */
class Phrase(
  /** Position in `segments`; the `segment` of a [WordRef] and of a [ShortId]'s first phrase. */
  val index: Int,
  /** `start` (required). */
  val startS: Double,
  /** `end` (required; raised to [startS] if smaller). */
  val endS: Double,
  /** `text`, trimmed; "" when absent. The words, not this text, are what the app shows and marks. */
  val text: String,
  /** `words`, in order; empty when absent. */
  val words: List<Word>,
  /** `f0_track`; null when absent or empty ("No measurable pitch in this phrase"). */
  val f0: F0Track?,
  /** `f0_stats.final_contour` as written ("rising", "falling", "flat"); typed by [contour]. */
  val finalContour: String?,
  /** `emphasis_word_idx`: the most prominent word; null when absent, null, or not an index of [words]. */
  val emphasisWord: Int?,
  /** `prominence` (0–1, one per word); null when absent or when its length differs from [words] (web rule). */
  val prominence: FloatArray?,
  /** `word_classes` as written ("content", "function"), one per word; null when absent or of another length. */
  val wordClasses: List<String>?,
  /** `intonation_units`; empty when absent. Malformed units are left out (nothing refers to them by index). */
  val intonationUnits: List<IntonationUnit>,
  // WP-12 refinement (additive):
  /** `f0_stats`: the voice line and the pitch summary of phrase details (F-11). */
  val f0Stats: F0Stats? = null,
  /** `rhythm`; null when absent, null (too few syllables), or incomplete. */
  val rhythm: Rhythm? = null,
) {
  /** [finalContour] typed: null when absent, [Contour.UNKNOWN] for a value this app does not know. */
  val contour: Contour? get() = Contour.of(finalContour)

  /** The class of word [word] of this phrase, or null when [wordClasses] is null or the index is out of range. */
  fun wordClass(word: Int): WordClass? = WordClass.of(wordClasses?.getOrNull(word))
}

/**
 * `f0_track` (`[[t, Hz], …]` every 10 ms): pitch in Hz at absolute times in seconds. The server interpolates across
 * unvoiced stretches, so the track has no gaps and no voicing flag. Both arrays have the same size, at least 1.
 */
class F0Track(val timesS: FloatArray, val hz: FloatArray) {
  val size: Int get() = timesS.size
}

/** `f0_stats`. Older analyses have only `mean`, `range` and `final_contour`. */
data class F0Stats(
  /** `mean` (Hz); null when the phrase has too little voicing. */
  val meanHz: Double?,
  /** `range` (Hz, 5th to 95th percentile). */
  val rangeHz: Double?,
  /** `final_contour` as written; the same value as [Phrase.finalContour]. */
  val finalContour: String?,
  /** `range_st` (semitones). */
  val rangeSt: Double?,
  /** `final_slope_st` (semitones per second). */
  val finalSlopeSt: Double?,
  /** `f0_floor` (Hz): the pitch range adapted to this phrase's voice. */
  val floorHz: Double?,
  /** `f0_ceiling` (Hz). */
  val ceilingHz: Double?,
) {
  val contour: Contour? get() = Contour.of(finalContour)
}

/** `rhythm`: variability of the intervals between syllable nuclei. Approximate by construction (CTC peaks). */
data class Rhythm(
  /** `npvi`. */
  val npvi: Double,
  /** `varco`; null when the mean interval is 0. */
  val varco: Double?,
  /** `n_intervals`. */
  val intervals: Int,
  /** `n_pairs`. */
  val pairs: Int,
  /** `mean_ms`. */
  val meanMs: Double,
  /** `sd_ms`. */
  val sdMs: Double,
  /** `approximate` (always true on the server today; true when absent). */
  val approximate: Boolean,
  /** `method` ("inter-nucleus intervals (CTC peaks)"). */
  val method: String?,
)

/**
 * `intonation_units[k]`: a sentence-sized stretch of a phrase, split at Whisper's punctuation. Kept only when its
 * `start`, `end` and `words` `[first, last]` are valid (0 ≤ first ≤ last < the phrase's word count).
 */
data class IntonationUnit(
  /** `start`. */
  val startS: Double,
  /** `end`. */
  val endS: Double,
  /** `words[0]`: first word index in the phrase, inclusive. */
  val firstWord: Int,
  /** `words[1]`: last word index in the phrase, inclusive. */
  val lastWord: Int,
  /** `type` as written; typed by [unitType]. */
  val type: String?,
  /** `final_contour` as written; typed by [contour]. */
  val finalContour: String?,
  /** `final_slope_st`. */
  val finalSlopeSt: Double?,
  /** `uptalk`: a statement that ends rising. Null when absent. */
  val uptalk: Boolean?,
  // WP-12 refinement (additive):
  /** `text`: the unit's words joined; "" when absent. */
  val text: String = "",
  /** `expected_contour` as written; null when the type expects nothing ("unfinished", exclamations). */
  val expectedContour: String? = null,
  /** `matches_expected`; null when nothing is expected. */
  val matchesExpected: Boolean? = null,
) {
  val unitType: UnitType get() = UnitType.of(type)
  val contour: Contour? get() = Contour.of(finalContour)
  val expected: Contour? get() = Contour.of(expectedContour)

  /** The web's `isJudged`: an unfinished unit says nothing about intonation unless it rose like uptalk. */
  val judged: Boolean get() = unitType != UnitType.INCOMPLETE || uptalk == true
}

/** A final pitch movement (`prosody.contour_label`). The web shows an unknown value as flat. */
enum class Contour {
  RISING,
  FALLING,
  FLAT,
  UNKNOWN;

  companion object {
    fun of(raw: String?): Contour? = when (raw) {
      null -> null
      "rising" -> RISING
      "falling" -> FALLING
      "flat" -> FLAT
      else -> UNKNOWN
    }
  }
}

/** An intonation unit's sentence type (web `UnitType`); "incomplete" reads "unfinished". */
enum class UnitType {
  STATEMENT,
  YES_NO_QUESTION,
  WH_QUESTION,
  EXCLAMATION,
  INCOMPLETE,
  UNKNOWN;

  companion object {
    fun of(raw: String?): UnitType = when (raw) {
      "statement" -> STATEMENT
      "yes_no_question" -> YES_NO_QUESTION
      "wh_question" -> WH_QUESTION
      "exclamation" -> EXCLAMATION
      "incomplete" -> INCOMPLETE
      else -> UNKNOWN
    }
  }
}

/** A word's class in `word_classes`: English stresses content words and reduces function words. */
enum class WordClass {
  CONTENT,
  FUNCTION,
  UNKNOWN;

  companion object {
    fun of(raw: String?): WordClass? = when (raw) {
      null -> null
      "content" -> CONTENT
      "function" -> FUNCTION
      else -> UNKNOWN
    }
  }
}
