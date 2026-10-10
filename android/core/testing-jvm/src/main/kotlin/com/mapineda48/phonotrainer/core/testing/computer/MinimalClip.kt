package com.mapineda48.phonotrainer.core.testing.computer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/**
 * A minimal, complete clip: `analysis.json` shaped exactly like `mk_analysis()` of the server's `tests/conftest.py`
 * (two segments, five words, three changes, prosody) and silent 16 kHz mono PCM16 WAV audio.
 *
 * Temporary: WP-12's `AnalysisFixtures` (`testing/fixtures/`) is the one Kotlin port of `mk_analysis` (delivery review
 * D-17). Until it is merged the fake computer serves this clip by default; afterwards a [ClipContentProvider] built on
 * `AnalysisFixtures` replaces [provider] and this file goes away.
 */
object MinimalClip {
  const val DURATION_S: Double = 3.0
  const val SEGMENTS: Int = 2
  const val WORDS: Int = 5
  val CHANGE_COUNTS: Map<String, Int> = mapOf("vowel_reduction" to 1, "t_deletion" to 1, "contraction_lex" to 1)

  /** Serves [content] for every finished job. */
  val provider = ClipContentProvider { title, separateDialogue -> content(title ?: "clip", separateDialogue) }

  fun content(source: String, separateDialogue: Boolean): ClipContent = ClipContent(
    analysisJson = analysis(source).toString().toByteArray(Charsets.UTF_8),
    audioMix = silentWav(DURATION_S),
    audioDialogue = if (separateDialogue) silentWav(DURATION_S, level = 1) else null,
    durationS = DURATION_S,
    segments = SEGMENTS,
    words = WORDS,
    changeCounts = CHANGE_COUNTS,
  )

  /** The `analysis.json` of `mk_analysis(source)`. */
  fun analysis(source: String): JsonObject = buildJsonObject {
    putJsonObject("meta") {
      put("source", source)
      put("duration", DURATION_S)
      put("language", "en")
      put("phonotrainer_version", "0.1.0")
      putJsonObject("models") {
        put("asr", "faster-whisper small (int8)")
        put("phones", "facebook/wav2vec2-lv-60-espeak-cv-ft")
        put("alignment", "torchaudio forced_align")
      }
      put("attraction", true)
      putJsonObject("phone_cleanup") {
        put("normalized_phones", 1)
        put("attracted_phones", 1)
      }
    }
    putJsonArray("segments") {
      add(firstSegment())
      add(secondSegment())
    }
    putJsonObject("summary") {
      putJsonObject("phenomena_counts") { CHANGE_COUNTS.forEach { (label, count) -> put(label, count) } }
    }
  }

  private fun firstSegment() = segment(
    start = 0.0, end = 1.2, text = "does that work",
    stats = Triple(118.0, 62.0, "rising"),
    track = (0 until 12).map { i -> round3(0.1 * i) to 110.0 + i },
    emphasis = 2, prominence = listOf(0.4, 0.2, 1.0), classes = listOf("function", "function", "content"),
    unit = IntonationUnit(0.0, 1.1, listOf(0, 2), "does that work", "rising", 5.2),
    words = listOf(
      word("does", 0.0, "d ʌ z", "d ə z", phenomena = listOf("vowel_reduction"), linkNext = true),
      word("that", 0.4, "ð æ t", "ð æ", phenomena = listOf("t_deletion"), diffCost = 0.8),
      word("work", 0.8, "w ɝ k", "w ɝ k"),
    ),
  )

  private fun secondSegment() = segment(
    start = 2.0, end = 3.0, text = "wanna go",
    stats = Triple(130.0, 20.0, "falling"),
    track = (0 until 10).map { i -> round3(2.0 + 0.1 * i) to 130.0 - i },
    emphasis = 0, prominence = listOf(1.0, 0.6), classes = listOf("function", "content"),
    unit = IntonationUnit(2.0, 2.9, listOf(0, 1), "wanna go", "falling", -4.1),
    words = listOf(
      word(
        "wanna", 2.0, "w ɑ n ə", "w ɑ n ə", phenomena = listOf("contraction_lex"), lexicalForm = "want to",
        attracted = 1, rawIpa = "wɑnə",
      ),
      word("go", 2.6, "ɡ oʊ", "ɡ oʊ", lowConfidence = true),
    ),
  )

  private class IntonationUnit(
    val start: Double, val end: Double, val words: List<Int>, val text: String, val contour: String, val slope: Double,
  )

  @Suppress("LongParameterList")
  private fun segment(
    start: Double, end: Double, text: String, stats: Triple<Double, Double, String>,
    track: List<Pair<Double, Double>>, emphasis: Int, prominence: List<Double>, classes: List<String>,
    unit: IntonationUnit, words: List<JsonObject>,
  ): JsonObject = buildJsonObject {
    put("start", start)
    put("end", end)
    put("text", text)
    putJsonObject("f0_stats") {
      put("mean", stats.first)
      put("range", stats.second)
      put("final_contour", stats.third)
    }
    putJsonArray("f0_track") { track.forEach { (t, hz) -> add(buildJsonArray { add(JsonPrimitive(t)); add(JsonPrimitive(hz)) }) } }
    put("emphasis_word_idx", emphasis)
    putJsonArray("prominence") { prominence.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("word_classes") { classes.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("intonation_units") {
      add(
        buildJsonObject {
          put("start", unit.start)
          put("end", unit.end)
          putJsonArray("words") { unit.words.forEach { add(JsonPrimitive(it)) } }
          put("text", unit.text)
          put("type", "incomplete")
          put("final_contour", unit.contour)
          put("final_slope_st", unit.slope)
          put("expected_contour", JsonNull)
          put("matches_expected", JsonNull)
          put("uptalk", false)
        },
      )
    }
    put("rhythm", JsonNull)
    put("words", JsonArray(words))
  }

  /** A word shaped exactly the way `pipeline.analyze` writes it (`mk_analysis_word`). */
  @Suppress("LongParameterList")
  private fun word(
    text: String, t0: Double, canonical: String, realized: String,
    phenomena: List<String> = emptyList(), linkNext: Boolean = false, diffCost: Double = 0.0,
    lexicalForm: String? = null, attracted: Int = 0, rawIpa: String = "", lowConfidence: Boolean = false,
  ): JsonObject {
    val can = aligned(canonical, t0)
    val real = aligned(realized, t0)
    val end = (can + real).maxOfOrNull { it.third } ?: (t0 + PHONE_S)
    return buildJsonObject {
      put("word", text)
      put("start", round3(t0))
      put("end", round3(end))
      put("canonical_ipa", canonical.replace(" ", ""))
      put("canonical_aligned", phonesJson(can))
      put("realized_ipa", realized.replace(" ", ""))
      put("realized_aligned", phonesJson(real))
      put("realized_raw_ipa", rawIpa)
      put("attracted_count", attracted)
      put("diff_cost", diffCost)
      put("dict_ipa", canonical.replace(" ", ""))
      put("oov", false)
      putJsonArray("phenomena") { phenomena.forEach { add(JsonPrimitive(it)) } }
      put("low_confidence", lowConfidence)
      put("boundary_link_next", linkNext)
      put("lexical_form", lexicalForm)
      put("alignment_fallback", false)
    }
  }

  private fun aligned(ipa: String, start: Double): List<Triple<String, Double, Double>> {
    var t = start
    return ipa.split(' ').filter { it.isNotEmpty() }.map { phone ->
      Triple(phone, round3(t), round3(t + PHONE_S)).also { t += PHONE_STEP_S }
    }
  }

  private fun phonesJson(phones: List<Triple<String, Double, Double>>) = buildJsonArray {
    phones.forEach { (p, a, b) -> add(buildJsonArray { add(JsonPrimitive(p)); add(JsonPrimitive(a)); add(JsonPrimitive(b)) }) }
  }

  private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

  /**
   * A real mono PCM16 WAV of [seconds] at [rate] Hz. [level] fills every sample (0 is silence), so two tracks of
   * one length still differ in their bytes and checksums.
   */
  fun silentWav(seconds: Double, rate: Int = 16_000, level: Short = 0): ByteArray {
    val samples = (rate * seconds).toInt()
    val dataBytes = samples * 2
    val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(WAV_HEADER_BYTES - 8 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
    buffer.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
    repeat(samples) { buffer.putShort(level) }
    return buffer.array()
  }

  private const val PHONE_S = 0.05
  private const val PHONE_STEP_S = 0.06
  private const val WAV_HEADER_BYTES = 44
}
