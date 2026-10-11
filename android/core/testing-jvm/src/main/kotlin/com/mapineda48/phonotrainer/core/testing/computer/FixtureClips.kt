package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What finished jobs on the fake computer serve, built on WP-12's [AnalysisFixtures], the one Kotlin port of the
 * server's `mk_analysis` (architecture 21.3, delivery review D-17): the clip data, and silent 16 kHz mono PCM16 WAVs
 * for the mix and, when the job separated its dialogue, the dialogue track.
 *
 * The listing's `summary` (duration, phrases, words, change counts) is read from the analysis itself, so whatever a
 * test serves, the summary and the file agree.
 */
object FixtureClips {
  /** The longest WAV the fake writes: a long analysis gets audio shorter than its timeline (nobody plays it here). */
  const val MAX_AUDIO_S: Double = 30.0

  /**
   * The default: `fake_analyze`'s output, i.e. `mk_analysis(source)` with `meta.dialogue_separation` saying whether
   * the dialogue was separated (the job's title is the source).
   */
  val mkAnalysis: ClipContentProvider = ClipContentProvider { title, separateDialogue ->
    content(AnalysisFixtures.mkAnalysis(source = title ?: "clip", dialogueSeparation = separateDialogue), separateDialogue)
  }

  /** Every finished job serves [variant] of the parser's test matrix (its own `meta` is kept as it is). */
  fun of(variant: AnalysisFixtures.Variant): ClipContentProvider = ClipContentProvider { _, separateDialogue ->
    content(AnalysisFixtures.analysis(variant), separateDialogue)
  }

  /** Every finished job serves the realistic long clip of [AnalysisFixtures.synthetic], pretty-printed. */
  fun synthetic(words: Int = 1_500, phrases: Int = 230, seed: Long = 7): ClipContentProvider {
    val analysis = AnalysisFixtures.synthetic(words, phrases, seed)
    val bytes = AnalysisFixtures.encode(analysis, pretty = true)
    return ClipContentProvider { _, separateDialogue -> content(analysis, separateDialogue, bytes) }
  }

  /** [analysis] served as [bytes], with silent WAVs; the dialogue WAV only when [separateDialogue]. */
  fun content(
    analysis: JsonObject,
    separateDialogue: Boolean,
    bytes: ByteArray = AnalysisFixtures.encode(analysis),
  ): ClipContent {
    val segments = (analysis["segments"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val duration = (analysis["meta"] as? JsonObject)?.number("duration")
      ?: segments.lastOrNull()?.number("end")
      ?: 0.0
    val audioSeconds = duration.coerceIn(MIN_AUDIO_S, MAX_AUDIO_S)
    return ClipContent(
      analysisJson = bytes,
      audioMix = FakeMediaBytes.wav(audioSeconds),
      audioDialogue = if (separateDialogue) FakeMediaBytes.wav(audioSeconds, level = 1) else null,
      durationS = duration,
      segments = segments.size,
      words = segments.sumOf { (it["words"] as? JsonArray)?.size ?: 0 },
      changeCounts = changeCounts(analysis),
    )
  }

  private fun changeCounts(analysis: JsonObject): Map<String, Int> {
    val counts = ((analysis["summary"] as? JsonObject)?.get("phenomena_counts") as? JsonObject) ?: return emptyMap()
    return counts.mapNotNull { (label, value) -> (value as? JsonPrimitive)?.intOrNull?.let { label to it } }.toMap()
  }

  private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

  private const val MIN_AUDIO_S = 0.1
}
