package com.mapineda48.phonotrainer.core.testing.fixtures

import com.mapineda48.phonotrainer.core.domain.model.AnalysisModels
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.F0Stats
import com.mapineda48.phonotrainer.core.domain.model.F0Track
import com.mapineda48.phonotrainer.core.domain.model.IntonationUnit
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Word
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Synthetic clip analyses for every test that needs one (architecture 21.3; delivery review D-17): the one Kotlin
 * port of the server's `mk_analysis` / `mk_analysis_word` (`tests/conftest.py`), the analysis variants WP-12's parser
 * is tested on (rules 1 to 3, the espeak engine, unknown values, malformed files), a realistic 1,500-word clip for
 * the parse-time budget, phrase-only timelines for the shorts rule, and domain twins ([clipData], [word], [phrase],
 * [clip]) for tests that need a [ClipData] without parsing (feature modules cannot depend on `:core:analysis`).
 *
 * JSON comes as [JsonObject] (to edit) or as bytes and text (to serve or parse). Times follow `mk_analysis_word`:
 * phone `k` of a word starting at `t0` spans `t0 + 0.06·k` to `+ 0.05`, rounded to the millisecond.
 */
object AnalysisFixtures {
  /** The job id the domain twins use by default. */
  val JOB: JobId = JobId("20261004-133519-a77c")

  /** The clip-data revision the domain twins use by default (16 hex digits, server-contract 6.4.2). */
  const val REVISION: String = "0123456789abcdef"

  // ---------------------------------------------------------------------------------------------------------------
  // The port of tests/conftest.py

  /**
   * `mk_analysis_word`: a word shaped exactly the way `pipeline.analyze` writes it. [canonical] and [realized] are
   * space-separated phones ("d ʌ z"); [extra] overrides or adds keys, like the Python keyword arguments
   * (`"phenomena" to listOf("t_deletion")`, `"diff_cost" to 0.8`); values may be null, Boolean, Number, String, a
   * list, a map or a [JsonElement].
   */
  fun mkAnalysisWord(
    word: String,
    t0: Double,
    canonical: String,
    realized: String,
    vararg extra: Pair<String, Any?>,
  ): JsonObject {
    val can = aligned(canonical, t0)
    val real = aligned(realized, t0)
    val end = (can + real).maxOfOrNull { it.endS } ?: (t0 + PHONE_S)
    val entry = linkedMapOf<String, Any?>(
      "word" to word, "start" to round3(t0), "end" to round3(end),
      "canonical_ipa" to canonical.replace(" ", ""), "canonical_aligned" to can.map(::row),
      "realized_ipa" to realized.replace(" ", ""), "realized_aligned" to real.map(::row),
      "realized_raw_ipa" to "", "attracted_count" to 0, "diff_cost" to 0.0,
      "dict_ipa" to canonical.replace(" ", ""), "oov" to false, "phenomena" to emptyList<String>(),
      "low_confidence" to false, "boundary_link_next" to false,
      "lexical_form" to null, "alignment_fallback" to false,
    )
    extra.forEach { (key, value) -> entry[key] = value }
    return obj(entry)
  }

  /**
   * `mk_analysis`: a minimal yet complete analysis (two segments, five words, three changes, prosody). With
   * [dialogueSeparation] it is `fake_analyze`'s output: `meta.dialogue_separation` is `{"applied": true, "model":
   * "htdemucs", "audio": "audio_dialogue.wav"}` or `{"applied": false}`.
   */
  fun mkAnalysis(source: String = "clip.wav", dialogueSeparation: Boolean? = null): JsonObject {
    val seg0 = obj(
      "start" to 0.0, "end" to 1.2, "text" to "does that work",
      "f0_stats" to mapOf("mean" to 118.0, "range" to 62.0, "final_contour" to "rising"),
      "f0_track" to (0 until 12).map { i -> listOf(round3(0.1 * i), 110.0 + i) },
      "emphasis_word_idx" to 2,
      "prominence" to listOf(0.4, 0.2, 1.0),
      "word_classes" to listOf("function", "function", "content"),
      "intonation_units" to listOf(
        mapOf(
          "start" to 0.0, "end" to 1.1, "words" to listOf(0, 2), "text" to "does that work",
          "type" to "incomplete", "final_contour" to "rising", "final_slope_st" to 5.2,
          "expected_contour" to null, "matches_expected" to null, "uptalk" to false,
        ),
      ),
      "rhythm" to null,
      "words" to listOf(
        mkAnalysisWord(
          "does", 0.0, "d ʌ z", "d ə z",
          "phenomena" to listOf("vowel_reduction"), "boundary_link_next" to true,
        ),
        mkAnalysisWord("that", 0.4, "ð æ t", "ð æ", "phenomena" to listOf("t_deletion"), "diff_cost" to 0.8),
        mkAnalysisWord("work", 0.8, "w ɝ k", "w ɝ k"),
      ),
    )
    val seg1 = obj(
      "start" to 2.0, "end" to 3.0, "text" to "wanna go",
      "f0_stats" to mapOf("mean" to 130.0, "range" to 20.0, "final_contour" to "falling"),
      "f0_track" to (0 until 10).map { i -> listOf(round3(2.0 + 0.1 * i), 130.0 - i) },
      "emphasis_word_idx" to 0,
      "prominence" to listOf(1.0, 0.6),
      "word_classes" to listOf("function", "content"),
      "intonation_units" to listOf(
        mapOf(
          "start" to 2.0, "end" to 2.9, "words" to listOf(0, 1), "text" to "wanna go",
          "type" to "incomplete", "final_contour" to "falling", "final_slope_st" to -4.1,
          "expected_contour" to null, "matches_expected" to null, "uptalk" to false,
        ),
      ),
      "rhythm" to null,
      "words" to listOf(
        mkAnalysisWord(
          "wanna", 2.0, "w ɑ n ə", "w ɑ n ə",
          "phenomena" to listOf("contraction_lex"), "lexical_form" to "want to",
          "attracted_count" to 1, "realized_raw_ipa" to "wɑnə",
        ),
        mkAnalysisWord("go", 2.6, "ɡ oʊ", "ɡ oʊ", "low_confidence" to true),
      ),
    )
    val meta = linkedMapOf<String, Any?>(
      "source" to source, "duration" to 3.0, "language" to "en",
      "phonotrainer_version" to "0.1.0",
      "models" to mapOf(
        "asr" to "faster-whisper small (int8)",
        "phones" to "facebook/wav2vec2-lv-60-espeak-cv-ft",
        "alignment" to "torchaudio forced_align",
      ),
      "attraction" to true,
      "phone_cleanup" to mapOf("normalized_phones" to 1, "attracted_phones" to 1),
    )
    if (dialogueSeparation != null) {
      meta["dialogue_separation"] = if (dialogueSeparation) {
        mapOf("applied" to true, "model" to "htdemucs", "audio" to "audio_dialogue.wav")
      } else {
        mapOf("applied" to false)
      }
    }
    return obj(
      "meta" to meta,
      "segments" to listOf(seg0, seg1),
      "summary" to mapOf(
        "phenomena_counts" to mapOf("vowel_reduction" to 1, "t_deletion" to 1, "contraction_lex" to 1),
      ),
    )
  }

  /** `mk_analysis`'s duration, phrase, word and change counts (what a listing's summary says about it). */
  object MkAnalysis {
    const val DURATION_S: Double = 3.0
    const val SEGMENTS: Int = 2
    const val WORDS: Int = 5
    val CHANGE_COUNTS: Map<String, Int> = mapOf("vowel_reduction" to 1, "t_deletion" to 1, "contraction_lex" to 1)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The variants of the parser's test matrix (WP-12 shape notes, section 3)

  /** What each kind of analysis on disk looks like. */
  enum class Variant {
    /** `mk_analysis()`: no rules version, an espeak model id, no metrics, prominence and units present. */
    MK_ANALYSIS,

    /** Every analysis today: rules 3, timit61, metrics 4, dialogue separated, numerals spoken out. */
    RULES_3,

    /** Rules 2 (2026-10-03 to 04): no `canonical_text`, no `no_canonical`. */
    RULES_2,

    /** Rules 1 (before 2026-10-03): no rules version, engine, separation, prominence, classes, units, rhythm,
     *  link type, form, variant labels or metrics; short `f0_stats`. */
    RULES_1,

    /** The first analyses (2026-07-26): rules 1 without `lexical_expansion` either. */
    EARLIEST,

    /** The espeak engine (rules 2): `form` objects, `variant_labels`, `meta.form_scoring`, variant metrics. */
    ESPEAK,

    /** Rules 3 with values this app does not know (a future server): every one must parse and be kept raw. */
    UNKNOWN_ENUMS,
  }

  fun analysis(variant: Variant): JsonObject = when (variant) {
    Variant.MK_ANALYSIS -> mkAnalysis()
    Variant.RULES_3 -> rules3()
    Variant.RULES_2 -> rules2()
    Variant.RULES_1 -> rules1()
    Variant.EARLIEST -> editWords(rules1()) { it - "lexical_expansion" }
    Variant.ESPEAK -> espeak()
    Variant.UNKNOWN_ENUMS -> unknownEnums()
  }

  /** The variant as JSON text, compact like `fake_analyze` writes it. */
  fun text(variant: Variant = Variant.MK_ANALYSIS): String = analysis(variant).toString()

  /** The variant's bytes; gzip for the `analysis_compact` rendition. */
  fun bytes(variant: Variant = Variant.MK_ANALYSIS, gzip: Boolean = false): ByteArray =
    encode(analysis(variant)).let { if (gzip) gzip(it) else it }

  /** Files that are not an analysis: each must be refused as corrupt, never crash. */
  enum class Malformed {
    NOT_JSON,
    TRUNCATED,
    TOP_LEVEL_ARRAY,
    NO_SEGMENTS,
    SEGMENTS_NOT_AN_ARRAY,
    SEGMENT_NOT_AN_OBJECT,
    SEGMENT_WITHOUT_START,
    SEGMENT_END_A_STRING,
    SEGMENT_START_NAN,
    WORDS_NOT_AN_ARRAY,
    WORD_NOT_AN_OBJECT,
    WORD_WITHOUT_END,
    WORD_START_INFINITE,

    /** Gzip bytes that are not a gzip stream (pass `gzip = true` to the parser). */
    BAD_GZIP,

    /** A gzip stream cut in the middle (pass `gzip = true`). */
    TRUNCATED_GZIP,
  }

  /** The bytes of [case], built from `mk_analysis`. */
  fun malformed(case: Malformed): ByteArray {
    val good = mkAnalysis()
    val text: String = when (case) {
      Malformed.NOT_JSON -> "this is not an analysis"
      Malformed.TRUNCATED -> good.toString().let { it.substring(0, it.length / 2) }
      Malformed.TOP_LEVEL_ARRAY -> JsonArray(listOf(good)).toString()
      Malformed.NO_SEGMENTS -> JsonObject(good - "segments").toString()
      Malformed.SEGMENTS_NOT_AN_ARRAY -> JsonObject(good + ("segments" to obj("0" to good["segments"]))).toString()
      Malformed.SEGMENT_NOT_AN_OBJECT -> editSegments(good) { index, segment ->
        if (index == 1) JsonPrimitive("segment") else segment
      }.toString()
      Malformed.SEGMENT_WITHOUT_START -> editSegment(good, 1) { it - "start" }.toString()
      Malformed.SEGMENT_END_A_STRING -> editSegment(good, 0) { it + ("end" to JsonPrimitive("1.2")) }.toString()
      Malformed.SEGMENT_START_NAN -> editSegment(good, 1) { it + ("start" to JsonPrimitive(MARK)) }.toString()
        .replace("\"$MARK\"", "NaN")
      Malformed.WORDS_NOT_AN_ARRAY -> editSegment(good, 0) { it + ("words" to obj("w" to "does")) }.toString()
      Malformed.WORD_NOT_AN_OBJECT -> editSegment(good, 0) {
        val words = (it["words"] as JsonArray).toMutableList().apply { set(1, JsonPrimitive("that")) }
        it + ("words" to JsonArray(words))
      }.toString()
      Malformed.WORD_WITHOUT_END -> editWords(good) { word ->
        if (word["word"] == JsonPrimitive("go")) word - "end" else word
      }.toString()
      Malformed.WORD_START_INFINITE -> editWords(good) { word ->
        if (word["word"] == JsonPrimitive("work")) word + ("start" to JsonPrimitive(MARK)) else word
      }.toString().replace("\"$MARK\"", "Infinity")
      Malformed.BAD_GZIP -> return encode(good)
      Malformed.TRUNCATED_GZIP -> return gzip(encode(good)).let { it.copyOf(it.size / 2) }
    }
    return text.toByteArray(Charsets.UTF_8)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Timelines and the large synthetic clip

  /**
   * An analysis with only phrase bounds (each `[start, end]`, no words): the input of the shorts rule. [durationS]
   * defaults to the last end.
   */
  fun timeline(phrases: List<Pair<Double, Double>>, durationS: Double? = null): JsonObject = obj(
    "meta" to mapOf("duration" to (durationS ?: phrases.maxOfOrNull { it.second } ?: 0.0), "language" to "en"),
    "segments" to phrases.map { (start, end) ->
      mapOf("start" to start, "end" to end, "text" to "", "words" to listOf<Any>())
    },
    "summary" to mapOf("phenomena_counts" to mapOf<String, Int>()),
  )

  /**
   * A rules-3, timit61 analysis shaped like the largest real one (1,489 words, 232 phrases, 42,749 pitch points,
   * 4.8 MB pretty-printed): [words] words over [phrases] phrases, every key a real analysis has, pitch every 10 ms,
   * about a third of the words labelled. Deterministic for a [seed].
   */
  fun synthetic(words: Int = 1_500, phrases: Int = 230, seed: Long = 7): JsonObject =
    SyntheticClip(words, phrases, seed).build()

  /** [synthetic] pretty-printed with two-space indents, like `pipeline.analyze` writes `analysis.json`. */
  fun syntheticBytes(words: Int = 1_500, phrases: Int = 230, seed: Long = 7): ByteArray =
    encode(synthetic(words, phrases, seed), pretty = true)

  // ---------------------------------------------------------------------------------------------------------------
  // Encoding

  /** UTF-8 JSON; [pretty] uses two-space indents (Python's `json.dumps(…, indent=2)`). */
  fun encode(json: JsonElement, pretty: Boolean = false): ByteArray =
    (if (pretty) PRETTY.encodeToString(JsonElement.serializer(), json) else json.toString()).toByteArray(Charsets.UTF_8)

  /** One gzip member, like the `analysis_compact` rendition. */
  fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Domain twins (no parsing)

  /**
   * The [ClipData] the parser makes of [mkAnalysis] (`:core:analysis` proves the two equal), built directly: for
   * tests of modules that may not depend on `:core:analysis`.
   */
  fun clipData(jobId: JobId = JOB, revision: String = REVISION): ClipData {
    val phrase0 = phrase(
      index = 0, startS = 0.0, endS = 1.2, text = "does that work",
      words = listOf(
        word(0, "does", 0.0, "d ʌ z", "d ə z", changes = listOf("vowel_reduction"), boundaryLinkNext = true),
        word(1, "that", 0.4, "ð æ t", "ð æ", changes = listOf("t_deletion"), diffCost = 0.8),
        word(2, "work", 0.8, "w ɝ k", "w ɝ k"),
      ),
      f0 = (0 until 12).map { i -> round3(0.1 * i) to 110.0 + i },
      f0Stats = F0Stats(118.0, 62.0, "rising", null, null, null, null),
      emphasisWord = 2, prominence = listOf(0.4, 0.2, 1.0), wordClasses = listOf("function", "function", "content"),
      intonationUnits = listOf(
        IntonationUnit(0.0, 1.1, 0, 2, "incomplete", "rising", 5.2, uptalk = false, text = "does that work"),
      ),
    )
    val phrase1 = phrase(
      index = 1, startS = 2.0, endS = 3.0, text = "wanna go",
      words = listOf(
        word(
          0, "wanna", 2.0, "w ɑ n ə", "w ɑ n ə", changes = listOf("contraction_lex"), lexicalForm = "want to",
          attractedCount = 1,
        ),
        word(1, "go", 2.6, "ɡ oʊ", "ɡ oʊ", lowConfidence = true),
      ),
      f0 = (0 until 10).map { i -> round3(2.0 + 0.1 * i) to 130.0 - i },
      f0Stats = F0Stats(130.0, 20.0, "falling", null, null, null, null),
      emphasisWord = 0, prominence = listOf(1.0, 0.6), wordClasses = listOf("function", "content"),
      intonationUnits = listOf(
        IntonationUnit(2.0, 2.9, 0, 1, "incomplete", "falling", -4.1, uptalk = false, text = "wanna go"),
      ),
    )
    val meta = ClipMeta(
      durationS = MkAnalysis.DURATION_S, language = "en", rulesVersion = null, phoneEngine = null,
      dialogueSeparation = null, phonotrainerVersion = "0.1.0",
      models = AnalysisModels(
        asr = "faster-whisper small (int8)", phones = "facebook/wav2vec2-lv-60-espeak-cv-ft",
        alignment = "torchaudio forced_align",
      ),
    )
    val counts = MkAnalysis.CHANGE_COUNTS.mapKeys { ChangeLabel(it.key) }
    return ClipData(jobId, revision, meta, listOf(phrase0, phrase1), ClipDataSummary(counts, emptyMap()))
  }

  /**
   * A domain [Word] timed like `mk_analysis_word` ([canonical] and [realized] are space-separated phones); the IPA
   * strings are the phones joined, and the dictionary form is the canonical one.
   */
  fun word(
    index: Int,
    text: String,
    t0: Double,
    canonical: String,
    realized: String,
    changes: List<String> = emptyList(),
    diffCost: Double = 0.0,
    lowConfidence: Boolean = false,
    boundaryLinkNext: Boolean = false,
    lexicalForm: String? = null,
    attractedCount: Int = 0,
    boundaryLinkType: String? = null,
  ): Word {
    val can = aligned(canonical, t0)
    val real = aligned(realized, t0)
    val end = (can + real).maxOfOrNull { it.endS } ?: (t0 + PHONE_S)
    return Word(
      index = index, text = text, startS = round3(t0), endS = round3(end),
      dictIpa = canonical.replace(" ", "").ifEmpty { null },
      canonicalIpa = canonical.replace(" ", "").ifEmpty { null },
      realizedIpa = realized.replace(" ", "").ifEmpty { null },
      canonicalPhones = can, realizedPhones = real,
      changes = changes.distinct().map(::ChangeLabel), diffCost = diffCost, lowConfidence = lowConfidence,
      boundaryLinkNext = boundaryLinkNext, lexicalForm = lexicalForm, oov = false,
      boundaryLinkType = boundaryLinkType, attractedCount = attractedCount,
    )
  }

  /** A domain [Phrase]; [f0] holds `(time, Hz)` pairs. */
  fun phrase(
    index: Int,
    startS: Double,
    endS: Double,
    words: List<Word> = emptyList(),
    text: String = words.joinToString(" ") { it.text },
    f0: List<Pair<Double, Double>> = emptyList(),
    f0Stats: F0Stats? = null,
    emphasisWord: Int? = null,
    prominence: List<Double>? = null,
    wordClasses: List<String>? = null,
    intonationUnits: List<IntonationUnit> = emptyList(),
  ): Phrase = Phrase(
    index = index, startS = startS, endS = endS, text = text, words = words,
    f0 = if (f0.isEmpty()) {
      null
    } else {
      F0Track(FloatArray(f0.size) { f0[it].first.toFloat() }, FloatArray(f0.size) { f0[it].second.toFloat() })
    },
    finalContour = f0Stats?.finalContour, emphasisWord = emphasisWord,
    prominence = prominence?.let { values -> FloatArray(values.size) { values[it].toFloat() } },
    wordClasses = wordClasses, intonationUnits = intonationUnits, f0Stats = f0Stats,
  )

  /**
   * A domain [ClipData] of [phrases] (their `index` must be their position), with change counts taken from the
   * words and no metrics; [durationS] defaults to the last phrase end.
   */
  fun clip(
    phrases: List<Phrase>,
    jobId: JobId = JOB,
    revision: String = REVISION,
    durationS: Double = phrases.maxOfOrNull { it.endS } ?: 0.0,
    phoneEngine: String? = "timit61",
  ): ClipData {
    require(phrases.withIndex().all { (i, phrase) -> phrase.index == i }) { "phrase indices must be their positions" }
    val counts = LinkedHashMap<ChangeLabel, Int>()
    phrases.flatMap { it.words }.forEach { word -> word.changes.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    val meta = ClipMeta(
      durationS = durationS, language = "en", rulesVersion = 3, phoneEngine = phoneEngine,
      dialogueSeparation = true, phonotrainerVersion = "0.1.0",
    )
    return ClipData(jobId, revision, meta, phrases, ClipDataSummary(counts, emptyMap()))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Variant builders

  /** Rules 3, timit61: three phrases that exercise every key a current analysis has. */
  private fun rules3(): JsonObject {
    val seg0 = obj(
      "start" to 0.0, "end" to 1.1, "text" to "Did you get the water?",
      "f0_stats" to mapOf(
        "mean" to 212.4, "range" to 96.1, "final_contour" to "rising", "range_st" to 7.8,
        "final_slope_st" to 6.1, "f0_floor" to 150.0, "f0_ceiling" to 330.5,
      ),
      "f0_track" to (0 until 100).map { i -> listOf(round3(0.01 * i), round1(200.0 + i * 0.5)) },
      "emphasis_word_idx" to 4,
      "prominence" to listOf(0.3, 0.1, 0.6, 0.0, 1.0),
      "word_classes" to listOf("content", "function", "content", "function", "content"),
      "intonation_units" to listOf(
        mapOf(
          "start" to 0.0, "end" to 1.0, "words" to listOf(0, 4), "text" to "Did you get the water?",
          "type" to "yes_no_question", "final_contour" to "rising", "final_slope_st" to 6.1,
          "expected_contour" to "rising", "matches_expected" to true, "uptalk" to false,
        ),
      ),
      "rhythm" to mapOf(
        "npvi" to 47.6, "varco" to 36.8, "n_intervals" to 8, "n_pairs" to 7, "mean_ms" to 169.0, "sd_ms" to 62.2,
        "approximate" to true, "method" to "inter-nucleus intervals (CTC peaks)",
      ),
      "words" to listOf(
        current(
          "Did", 0.0, "d ɪ d", "d ɪ dʒ", "dˈɪd", "phenomena" to listOf("palatalization"),
          "boundary_link_next" to true, "boundary_link_type" to "glide_j", "diff_cost" to 0.3,
        ),
        current("you", 0.2, "j uː", "j ə", "jˈuː", "phenomena" to listOf("vowel_reduction"), "diff_cost" to 0.4),
        current(
          "get", 0.4, "ɡ ɛ t", "ɡ ɛ ɾ", "ɡˈɛt", "phenomena" to listOf("flapping", "linking"),
          "boundary_link_next" to true, "boundary_link_type" to "consonant", "diff_cost" to 0.2,
        ),
        current("the", 0.6, "ð ə", "ð ə", "ðə"),
        current("water?", 0.75, "w ɔ t ɚ", "w ɔ ɾ ɚ", "wˈɔtɚ", "phenomena" to listOf("flapping"), "diff_cost" to 0.1),
      ),
    )
    val seg1 = obj(
      "start" to 2.5, "end" to 3.7, "text" to "I'm gonna go at 9.30.",
      "f0_stats" to mapOf(
        "mean" to 180.2, "range" to 40.0, "final_contour" to "falling", "range_st" to 3.9,
        "final_slope_st" to -3.2, "f0_floor" to 140.0, "f0_ceiling" to 260.0,
      ),
      "f0_track" to (0 until 120).map { i -> listOf(round3(2.5 + 0.01 * i), round1(190.0 - i * 0.2)) },
      "emphasis_word_idx" to 4,
      "prominence" to listOf(0.2, 0.5, 0.4, 0.1, 1.0),
      "word_classes" to listOf("function", "content", "content", "function", "content"),
      "intonation_units" to listOf(
        mapOf(
          "start" to 2.5, "end" to 3.7, "words" to listOf(0, 4), "text" to "I'm gonna go at 9.30.",
          "type" to "statement", "final_contour" to "falling", "final_slope_st" to -3.2,
          "expected_contour" to "falling", "matches_expected" to true, "uptalk" to false,
        ),
      ),
      "rhythm" to null,
      "words" to listOf(
        current("I'm", 2.5, "aɪ m", "aɪ m", "ˈaɪm"),
        current(
          "gonna", 2.65, "ɡ ʌ n ə", "ɡ ʌ n ə", "ɡˈʌnə", "phenomena" to listOf("contraction_lex"),
          "lexical_form" to "gonna", "lexical_expansion" to "going to",
        ),
        current(
          "go", 2.95, "ɡ oʊ", "ɡ ə", "ɡˈoʊ", "phenomena" to listOf("vowel_reduction"), "low_confidence" to true,
          "diff_cost" to 0.5,
        ),
        current("at", 3.1, "æ t", "æ t̚", "ˈæt", "phenomena" to listOf("t_unreleased"), "diff_cost" to 0.15),
        current(
          "9.30.", 3.25, "n aɪ n θ ɝ t i", "n aɪ n θ ɝ ɾ i", "nˈaɪnθˈɝti", "phenomena" to listOf("flapping"),
          "canonical_text" to "nine thirty", "diff_cost" to 0.25,
        ),
      ),
    )
    val seg2 = obj(
      "start" to 4.5, "end" to 5.5, "text" to "% uh",
      "f0_stats" to mapOf(
        "mean" to null, "range" to null, "final_contour" to null, "range_st" to null, "final_slope_st" to null,
        "f0_floor" to 75.0, "f0_ceiling" to 600.0,
      ),
      "f0_track" to listOf<Any>(),
      "emphasis_word_idx" to null,
      "prominence" to listOf(0.0, 0.0),
      "word_classes" to listOf("function", "function"),
      "intonation_units" to listOf(
        mapOf(
          "start" to 4.5, "end" to 4.65, "words" to listOf(0, 1), "text" to "% uh",
          "type" to "incomplete", "final_contour" to null, "final_slope_st" to null,
          "expected_contour" to null, "matches_expected" to null, "uptalk" to false,
        ),
      ),
      "rhythm" to null,
      "words" to listOf(
        current("%", 4.5, "", "", "", "no_canonical" to true, "canonical_text" to "percent"),
        // 50 ms: too short to trust, and the clearest-looking alignment of the clip (it must never win a spotlight)
        current("uh", 4.6, "ʌ", "ə", "ˈʌ", "phenomena" to listOf("vowel_reduction"), "diff_cost" to 0.05),
      ),
    )
    val counts = mapOf(
      "palatalization" to 1, "vowel_reduction" to 3, "flapping" to 3, "linking" to 1, "contraction_lex" to 1,
      "t_unreleased" to 1,
    )
    return obj(
      "meta" to mapOf(
        "source" to "Clip title [abc123].webm", "duration" to 6.0, "language" to "en",
        "phonotrainer_version" to "0.1.0", "rules_version" to 3, "phone_engine" to "timit61",
        "models" to mapOf(
          "asr" to "faster-whisper small (int8)",
          "phones" to "excalibur12/wav2vec2-large-lv60_phoneme-timit_english_timit-4k",
          "alignment" to "torchaudio forced_align of the CMUdict citation form over TIMIT-61 emissions",
        ),
        "attraction" to false,
        "dialogue_separation" to mapOf(
          "applied" to true, "model" to "htdemucs", "audio" to "audio_dialogue.wav", "seconds" to 22.0,
        ),
        "form_scoring" to null,
        "phone_cleanup" to mapOf("normalized_phones" to 0, "attracted_phones" to 0),
      ),
      "segments" to listOf(seg0, seg1, seg2),
      "summary" to mapOf("phenomena_counts" to counts, "metrics" to metrics(counts, espeak = false)),
    )
  }

  private fun rules2(): JsonObject {
    val withoutNumerals = editWords(rules3()) { it - "canonical_text" - "no_canonical" }
    return editMeta(withoutNumerals) { it + ("rules_version" to JsonPrimitive(2)) }
  }

  private fun rules1(): JsonObject {
    var json = rules2()
    json = editMeta(json) { it - "rules_version" - "phone_engine" - "dialogue_separation" - "form_scoring" }
    json = editSegments(json) { _, segment ->
      val stats = (segment as JsonObject)["f0_stats"] as JsonObject
      JsonObject(
        segment - "prominence" - "word_classes" - "intonation_units" - "rhythm" +
          ("f0_stats" to JsonObject(stats.filterKeys { it in setOf("mean", "range", "final_contour") })),
      )
    }
    json = editWords(json) { it - "boundary_link_type" - "variant_labels" - "form" }
    val summary = json["summary"] as JsonObject
    return JsonObject(json + ("summary" to JsonObject(summary - "metrics")))
  }

  private fun espeak(): JsonObject {
    var json = rules2()
    json = editMeta(json) {
      it + mapOf(
        "phone_engine" to JsonPrimitive("espeak"),
        "models" to obj("asr" to "faster-whisper small (int8)", "phones" to "facebook/wav2vec2-lv-60-espeak-cv-ft"),
        "form_scoring" to obj("scored" to 3, "weak" to 2),
      )
    }
    json = editWords(json) { word ->
      when ((word["word"] as JsonPrimitive).content) {
        "you" -> word + mapOf(
          "form" to obj(
            "ipa" to "jə", "strong_ipa" to "juː", "weak" to true, "weak_margin" to 0.42,
            "scores" to mapOf("jə" to -1.2, "juː" to -1.62),
          ),
          "variant_labels" to JsonArray(listOf(JsonPrimitive("vowel_reduction"))),
        )
        "at" -> word + ("form" to obj("ipa" to "æt", "strong_ipa" to "æt", "weak" to false, "weak_margin" to -0.3))
        "the" -> word + ("form" to obj("ipa" to "ðə", "strong_ipa" to "ðiː", "weak" to true, "h_dropped" to null))
        else -> word
      }
    }
    val summary = json["summary"] as JsonObject
    val counts = (summary["phenomena_counts"] as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() }
    return JsonObject(json + ("summary" to JsonObject(summary + ("metrics" to metrics(counts, espeak = true)))))
  }

  private fun unknownEnums(): JsonObject {
    var json = editMeta(rules3()) { it + ("phone_engine" to JsonPrimitive("x-engine")) }
    json = editSegment(json, 0) { segment ->
      val stats = segment["f0_stats"] as JsonObject
      val unit = ((segment["intonation_units"] as JsonArray)[0] as JsonObject) + mapOf(
        "type" to JsonPrimitive("question_tag"),
        "final_contour" to JsonPrimitive("rising-falling"),
        "expected_contour" to JsonPrimitive("level"),
      )
      segment + mapOf(
        "f0_stats" to JsonObject(stats + ("final_contour" to JsonPrimitive("rising-falling"))),
        "intonation_units" to JsonArray(listOf(JsonObject(unit))),
        "word_classes" to json(listOf("content", "particle", "content", "function", "content")),
        "a_new_key" to obj("anything" to listOf(1, 2, 3)),
      )
    }
    return editWords(json) { word ->
      when ((word["word"] as JsonPrimitive).content) {
        "Did" -> word + ("boundary_link_type" to JsonPrimitive("x"))
        "water?" -> word + mapOf(
          "phenomena" to JsonArray(listOf("flapping", "new_label", "flapping").map(::JsonPrimitive)),
          "a_new_word_key" to JsonPrimitive(true),
        )
        else -> word
      }
    }
  }

  /** A word of a current (rules 3, timit61) analysis: every key `pipeline.analyze` writes today. */
  private fun current(
    word: String,
    t0: Double,
    canonical: String,
    realized: String,
    dictIpa: String,
    vararg extra: Pair<String, Any?>,
  ): JsonObject = mkAnalysisWord(
    word, t0, canonical, realized,
    "dict_ipa" to dictIpa, "canonical_text" to null, "no_canonical" to false, "boundary_link_type" to null,
    "lexical_expansion" to null, "form" to null, "variant_labels" to emptyList<String>(), *extra,
  )

  /** `summary.metrics` version 4, shaped like the server's (`metrics.py`). */
  internal fun metrics(counts: Map<String, Int>, espeak: Boolean): JsonObject = obj(
    "version" to 4, "engine" to if (espeak) "espeak" else "timit61", "rules" to if (espeak) 2 else 3,
    "words" to mapOf(
      "total" to 12, "analyzed" to 10, "low_confidence" to 1, "no_canonical" to 1, "low_confidence_pct" to 8.3,
    ),
    "deviate" to ratio(6, 10), "segment_loss" to ratio(1, 10), "syllable_loss" to ratio(0, 10),
    "schwa_share" to ratio(3, 14), "function_words" to ratio(5, 12),
    "weak_forms" to mapOf("greedy" to ratio(2, 4), "variant" to if (espeak) ratio(2, 3) + ("weak" to 2) else null),
    "flapping" to ratio(3, 3), "glottal_before_syllabic_n" to ratio(0, 0, pct = null),
    "glottal_prevocalic" to ratio(0, 1),
    "final_t_prevocalic" to mapOf(
      "released" to 0, "flap" to 1, "glottal" to 0, "unreleased" to 0, "other" to 0, "of" to 1,
    ),
    "labels" to counts,
    "labels_per_100_words" to counts.mapValues { round1(it.value * 100.0 / 12) },
    "rhythm" to mapOf(
      "npvi" to 47.6, "varco" to 36.8, "n_intervals" to 8, "n_pairs" to 7, "mean_ms" to 169.0, "sd_ms" to 62.2,
      "approximate" to true, "method" to "inter-nucleus intervals (CTC peaks)",
    ),
  )

  private fun ratio(count: Int, of: Int, pct: Double? = if (of > 0) round1(100.0 * count / of) else null) =
    mapOf("count" to count, "of" to of, "pct" to pct)

  // ---------------------------------------------------------------------------------------------------------------
  // JSON helpers

  /** A JSON object of [entries], each value converted by [json]. */
  fun obj(vararg entries: Pair<String, Any?>): JsonObject = obj(linkedMapOf(*entries))

  fun obj(entries: Map<String, Any?>): JsonObject = JsonObject(entries.mapValues { json(it.value) })

  /** A Kotlin value as JSON: null, Boolean, Number, String, a list, a map (string keys), a pair or a [JsonElement]. */
  fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
    is Iterable<*> -> JsonArray(value.map(::json))
    is Pair<*, *> -> JsonArray(listOf(json(value.first), json(value.second)))
    else -> error("no JSON form for ${value::class.java.simpleName}")
  }

  /** [json] with its `meta` replaced by [edit]'s result. */
  fun editMeta(json: JsonObject, edit: (JsonObject) -> Map<String, JsonElement>): JsonObject =
    JsonObject(json + ("meta" to JsonObject(edit(json["meta"] as JsonObject))))

  /** [json] with each segment replaced by [edit]'s result (given its index). */
  fun editSegments(json: JsonObject, edit: (Int, JsonElement) -> JsonElement): JsonObject {
    val segments = (json["segments"] as JsonArray).mapIndexed(edit)
    return JsonObject(json + ("segments" to JsonArray(segments)))
  }

  /** [json] with segment [index] replaced by [edit]'s result. */
  fun editSegment(json: JsonObject, index: Int, edit: (JsonObject) -> Map<String, JsonElement>): JsonObject =
    editSegments(json) { i, segment -> if (i == index) JsonObject(edit(segment as JsonObject)) else segment }

  /** [json] with every word of every segment replaced by [edit]'s result. */
  fun editWords(json: JsonObject, edit: (JsonObject) -> Map<String, JsonElement>): JsonObject =
    editSegments(json) { _, segment ->
      val words = ((segment as JsonObject)["words"] as JsonArray).map { JsonObject(edit(it as JsonObject)) }
      JsonObject(segment + ("words" to JsonArray(words)))
    }

  internal fun aligned(spaced: String, start: Double): List<TimedPhone> {
    var t = start
    return spaced.split(' ').filter { it.isNotEmpty() }.map { phone ->
      TimedPhone(phone, round3(t), round3(t + PHONE_S)).also { t += PHONE_STEP_S }
    }
  }

  private fun row(phone: TimedPhone): List<Any> = listOf(phone.symbol, phone.startS, phone.endS)

  internal fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

  internal fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

  @OptIn(ExperimentalSerializationApi::class)
  private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  /** A placeholder replaced by a bare `NaN` or `Infinity` token, which no JSON encoder writes by itself. */
  private const val MARK = "__non_finite__"

  private const val PHONE_S = 0.05
  private const val PHONE_STEP_S = 0.06
}
