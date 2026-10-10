package com.mapineda48.phonotrainer.core.domain.model

import kotlin.math.roundToInt

/**
 * One clip's analysis, decoded from its clip-data artifact (`analysis.json`, or the gzip `analysis_compact`
 * rendition) by `:core:analysis`'s `AnalysisParser` (architecture 9.1). Immutable; shared by the feed, the lesson,
 * the Clip page and the indexer through `ClipContentRepository.clipData`, which caches three of them keyed by
 * [jobId] and [revision].
 *
 * Indices are positions in `analysis.json`: `phrases[i].index == i` is the `segment` of a [WordRef] and
 * `phrases[i].words[j].index == j` its `word`. The parser never drops, merges or reorders a phrase or a word, so a
 * [WordRef] means the same word on the phone, on the server and in the web client.
 *
 * Not a data class: two parses of the same file are the same clip, compared by [jobId] and [revision].
 */
class ClipData(
  val jobId: JobId,
  /** Revision of the clip-data artifact file that was parsed (server-contract 6.4.2: first 16 hex of its sha256). */
  val revision: String,
  val meta: ClipMeta,
  val phrases: List<Phrase>,
  val summary: ClipDataSummary,
) {
  /** Every word of every phrase. */
  val wordCount: Int = phrases.sumOf { it.words.size }

  fun phrase(segment: Int): Phrase? = phrases.getOrNull(segment)

  /** The word [ref] points to, or null when it belongs to another clip or is out of range. */
  fun word(ref: WordRef): Word? =
    if (ref.jobId != jobId) null else phrases.getOrNull(ref.segment)?.words?.getOrNull(ref.word)

  /**
   * The next word on the timeline: the next one in the phrase, else the first word of the next phrase that has
   * words (web `Workspace.tsx` `following`: linking also happens on a phrase's last word). Null at the end of the
   * clip, or when [ref] is not a word of this clip.
   */
  fun nextWord(ref: WordRef): WordRef? {
    if (word(ref) == null) return null
    if (ref.word + 1 < phrases[ref.segment].words.size) return ref.copy(word = ref.word + 1)
    for (segment in ref.segment + 1 until phrases.size) {
      if (phrases[segment].words.isNotEmpty()) return WordRef(jobId, segment, 0)
    }
    return null
  }

  /**
   * Silence between the last heard sound of [ref] and the first heard sound of the next word, in ms, rounded half
   * up (web `compareWords` `gapMs`, the measure of linking: an unlinked boundary sits nearer 60 ms). Negative when
   * they overlap. Null when there is no next word or either word has no heard sound. The lesson shows it only when
   * the word's change crosses the boundary ([Word.crossesBoundary]).
   */
  fun boundaryGapMs(ref: WordRef): Int? {
    val last = word(ref)?.realizedPhones?.lastOrNull() ?: return null
    val first = nextWord(ref)?.let(::word)?.realizedPhones?.firstOrNull() ?: return null
    return ((first.startS - last.endS) * 1000).roundToInt()
  }
}

/** `analysis.json` `meta`. Every field but [durationS] may be absent in older analyses. */
data class ClipMeta(
  /** `meta.duration` (s); when absent, negative or not finite: the latest phrase end, else 0. */
  val durationS: Double,
  /** `meta.language` ("en"). */
  val language: String?,
  /** `meta.rules_version` (labeling rules; 3 today). Null: made before the field existed (rules 1). */
  val rulesVersion: Int?,
  /** `meta.phone_engine` as written ("timit61", "espeak"; "wav2vec2" on old jobs). Null: absent, i.e. espeak. */
  val phoneEngine: String?,
  /** `meta.dialogue_separation.applied`. Null: the key is absent (made before separation existed). */
  val dialogueSeparation: Boolean?,
  /** `meta.phonotrainer_version` ("0.1.0"). */
  val phonotrainerVersion: String?,
  // WP-12 refinement (additive):
  /** `meta.models`: the "About this clip" speech model and sounds model (spec F-05 Summary). */
  val models: AnalysisModels? = null,
  /** `meta.dialogue_separation` in full, for the "About this clip" dialogue line. */
  val dialogue: DialogueSeparation? = null,
) {
  /** [phoneEngine] read the web's way (`engineOf`): absent means espeak. */
  val engine: PhoneEngine get() = PhoneEngine.of(phoneEngine)

  /**
   * The web's `isNarrowEngine`: anything but espeak. With a narrow engine the expected row of the lesson's Compare
   * step IS the dictionary form placed in time; with espeak it is "Expected by the aligner".
   */
  val narrowEngine: Boolean get() = engine != PhoneEngine.ESPEAK
}

/** The phone recognizer an analysis was made with. Metrics of two engines are never pooled (spec F-18). */
enum class PhoneEngine {
  TIMIT61,
  ESPEAK,
  UNKNOWN;

  companion object {
    /** Reads an analysis's `meta.phone_engine`: absent and the old name "wav2vec2" mean espeak (web `engineOf`). */
    fun of(raw: String?): PhoneEngine = when (raw) {
      null, "espeak", "wav2vec2" -> ESPEAK
      "timit61" -> TIMIT61
      else -> UNKNOWN
    }
  }
}

/** `meta.models`. */
data class AnalysisModels(
  /** `asr`: "faster-whisper small (int8)". */
  val asr: String?,
  /** `phones`: the recognizer's model id. */
  val phones: String?,
  /** `alignment`: "torchaudio forced_align". */
  val alignment: String?,
)

/** `meta.dialogue_separation`. Its `audio` file name is deliberately not read: the artifact list names the files. */
data class DialogueSeparation(
  val applied: Boolean,
  /** `model` ("htdemucs"). */
  val model: String?,
  /** `seconds` the separation took. */
  val seconds: Double?,
  /** `error`: why it was not applied, already cleaned by the server. */
  val error: String?,
)

/** `analysis.json` `summary`. */
data class ClipDataSummary(
  /**
   * `summary.phenomena_counts`: labels on words, one per word and label. When absent: counted from the words'
   * `phenomena` the server's way (`count_phenomena`). Unknown labels are kept.
   */
  val changeCounts: Map<ChangeLabel, Int>,
  /**
   * `summary.metrics` flattened: every JSON number leaf, keyed by its dotted path inside `metrics`
   * ("version", "words.total", "deviate.count", "deviate.of", "deviate.pct", "weak_forms.greedy.count",
   * "final_t_prevocalic.glottal", "labels.flapping", "rhythm.npvi", …). Strings, booleans, lists and nulls are left
   * out. Empty when `metrics` is absent or null: the clip is not measured (older analysis).
   */
  val metrics: Map<String, Double>,
) {
  /** False for an analysis without metrics ("2 clips are older and are not measured", spec F-18). */
  val measured: Boolean get() = metrics.isNotEmpty()

  /** `metrics.version` (4 today). */
  val metricsVersion: Int? get() = metrics["version"]?.toInt()

  /**
   * The `{count, of}` ratio at [key] ("deviate", "flapping", "weak_forms.greedy", "weak_forms.variant", …), or null
   * when either number is missing. Ratios are pooled by adding counts, never by averaging percentages.
   */
  fun ratio(key: String): MetricRatio? {
    val count = metrics["$key.count"] ?: return null
    val of = metrics["$key.of"] ?: return null
    return MetricRatio(count.toLong(), of.toLong())
  }
}

/** A metric ratio (`metrics.ratio` on the server): [count] out of [of]. */
data class MetricRatio(val count: Long, val of: Long) {
  /** Percentage, unrounded; null when there was nothing to count. */
  val pct: Double? get() = if (of > 0) 100.0 * count / of else null

  /** Token-by-token pooling (F-18-AC1: 30 of 40 plus 10 of 20 is 40 of 60). */
  operator fun plus(other: MetricRatio): MetricRatio = MetricRatio(count + other.count, of + other.of)
}
