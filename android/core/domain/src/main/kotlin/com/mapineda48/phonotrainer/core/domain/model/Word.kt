package com.mapineda48.phonotrainer.core.domain.model

/**
 * One word: an `analysis.json` `segments[i].words[j]`. Times are absolute seconds. An optional string that is
 * absent, null, not a string or empty reads as null; an optional flag or number that is absent or of another type
 * reads as its default.
 */
data class Word(
  /** Position in the phrase's `words`; the `word` of a [WordRef]. */
  val index: Int,
  /** `word`, as transcribed (Whisper text, already trimmed by the server); "" when absent. */
  val text: String,
  /** `start` (required). */
  val startS: Double,
  /** `end` (required; raised to [startS] if smaller). */
  val endS: Double,
  /** `dict_ipa`: the dictionary (citation) form, with stress marks. */
  val dictIpa: String?,
  /** `canonical_ipa`: the expected form the aligner placed in time (the dictionary form with timit61). */
  val canonicalIpa: String?,
  /** `realized_ipa`: what was said. Null: nothing heard. */
  val realizedIpa: String?,
  /** `canonical_aligned` (`[phone, start, end]` rows); entries that are not such a row are left out. */
  val canonicalPhones: List<TimedPhone>,
  /** `realized_aligned`; empty when nothing was heard. */
  val realizedPhones: List<TimedPhone>,
  /** `phenomena`, in order, without repeats; includes [variantLabels]. Unknown labels are kept. */
  val changes: List<ChangeLabel>,
  /** `diff_cost`: divergence from the canonical form, 0 = identical; 0 when absent. */
  val diffCost: Double,
  /** `low_confidence`; false when absent. */
  val lowConfidence: Boolean,
  /** `boundary_link_next`: runs into the next word (‿); false when absent. */
  val boundaryLinkNext: Boolean,
  /** `lexical_form`: the reduced form actually spoken ("wanna"). */
  val lexicalForm: String?,
  /** `oov`: not in the dictionary, its form was guessed from the spelling; false when absent. */
  val oov: Boolean,
  // WP-12 refinement (additive):
  /** `boundary_link_type` as written ("consonant", "r", "glide_w", "glide_j"); typed by [linkType]. Absent before
   *  rules 2; null when the word does not link. */
  val boundaryLinkType: String? = null,
  /** `lexical_expansion`: the full form of [lexicalForm] ("want to"); absent in older analyses. */
  val lexicalExpansion: String? = null,
  /** `no_canonical`: nothing to compare against (a bare symbol, a numeral fragment); absent before rules 3. */
  val noCanonical: Boolean = false,
  /** `canonical_text`: how a numeral was said ("9.30" → "nine thirty"); absent before rules 3. */
  val canonicalText: String? = null,
  /** `attracted_count`: heard sounds kept as expected by attraction (a footer fact of lesson step 3). */
  val attractedCount: Int = 0,
  /** `alignment_fallback`: approximate timing, placed with the transcript's word times. */
  val alignmentFallback: Boolean = false,
  /** `form`: strong vs weak form scoring, espeak engine only (rules 2 and later); null otherwise. */
  val form: WordForm? = null,
  /** `variant_labels`: the labels of [changes] that came from form scoring, not from the heard sounds (rules 2+). */
  val variantLabels: List<ChangeLabel> = emptyList(),
) {
  /** The web's playable rule (`too_short`): shorter than 60 ms is almost always an alignment failure. */
  val tooShort: Boolean get() = endS - startS < 0.06

  /** [boundaryLinkType] typed: null when the word does not link, [LinkType.UNKNOWN] for a value unknown here. */
  val linkType: LinkType? get() = LinkType.of(boundaryLinkType)

  /** The web's `hasNoCanonical`: no dictionary form to compare against (lesson step 2 notice, no labels). */
  val noDictionaryForm: Boolean get() = noCanonical || canonicalPhones.isEmpty()

  /** The web's "no-phones" reliability: the recognizer heard nothing for this word. */
  val nothingHeard: Boolean get() = realizedPhones.isEmpty()

  /**
   * The web's `crossesBoundary` without its `next` check: the change happens at the boundary with the following word
   * (`boundary_link_next`, linking, palatalization), so it is heard, played and compared together with the next word
   * on the timeline ([ClipData.nextWord]) when there is one.
   */
  val crossesBoundary: Boolean get() = boundaryLinkNext || changes.any { it.value in BOUNDARY_CHANGES }

  private companion object {
    /** h-dropping is word-internal (its context is the word before), so it is not here (web `lib/words.ts`). */
    val BOUNDARY_CHANGES = setOf("linking", "palatalization")
  }
}

/** One `[phone, start, end]` row of `canonical_aligned` or `realized_aligned`: a CTC peak, not a segmentation. */
data class TimedPhone(val symbol: String, val startS: Double, val endS: Double)

/** How a word links onto the next (`phenomena.LINK_TYPES`; web `LINK_TYPE_LABEL`). */
enum class LinkType {
  CONSONANT,
  R,
  GLIDE_W,
  GLIDE_J,
  UNKNOWN;

  companion object {
    fun of(raw: String?): LinkType? = when (raw) {
      null -> null
      "consonant" -> CONSONANT
      "r" -> R
      "glide_w" -> GLIDE_W
      "glide_j" -> GLIDE_J
      else -> UNKNOWN
    }
  }
}

/**
 * `form` (espeak engine only, `variants.py`): the strong vs weak pronunciation scored against the recognizer.
 * Read only when `ipa` and `strong_ipa` are strings and `weak` is a boolean; its `scores` are not read.
 */
data class WordForm(
  /** `ipa`: the form that scored best. */
  val ipa: String,
  /** `strong_ipa`: the citation (strong) form. */
  val strongIpa: String,
  /** `weak`: the best form is the weak one. */
  val weak: Boolean,
  /** `weak_margin`: how far the weak form scored ahead (positive) or behind. */
  val weakMargin: Double?,
  /** `h_dropped`: only for h-words. */
  val hDropped: Boolean?,
  /** `h_drop_margin`. */
  val hDropMargin: Double?,
)
