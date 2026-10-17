package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.obj
import kotlinx.serialization.json.JsonObject

/**
 * Analyses for the corpus tests, written with [AnalysisFixtures.mkAnalysisWord] (the port of the server's
 * `mk_analysis_word`): phrases of labelled words, each phrase placed where the test wants its short.
 */
object TestClips {
  /** A phrase from [startS] to [endS] holding [words]. */
  class PhraseSpec(val startS: Double, val endS: Double, val words: List<JsonObject>)

  fun phrase(startS: Double, endS: Double, vararg words: JsonObject): PhraseSpec = PhraseSpec(startS, endS, words.toList())

  /** An analysis (rules 3, timit61) of [phrases]; its duration is one second after the last phrase. */
  fun analysis(vararg phrases: PhraseSpec): JsonObject = obj(
    "meta" to mapOf(
      "duration" to (phrases.maxOfOrNull { it.endS } ?: 0.0) + 1.0,
      "language" to "en",
      "rules_version" to 3,
      "phone_engine" to "timit61",
    ),
    "segments" to phrases.map { phrase ->
      obj(
        "start" to phrase.startS,
        "end" to phrase.endS,
        "text" to phrase.words.joinToString(" ") { it["word"].toString().trim('"') },
        "words" to phrase.words,
      )
    },
  )

  /**
   * A word at [t0] with [changes]; heard as [realized] against the dictionary's [canonical] (space-separated phones).
   * [tooShort] makes it last 40 ms (under the web's 60 ms playable rule).
   */
  fun word(
    text: String,
    t0: Double,
    vararg changes: String,
    canonical: String = "w ɔ t ɚ",
    realized: String = "w ɔ ɾ ɚ",
    diffCost: Double = 0.3,
    lowConfidence: Boolean = false,
    tooShort: Boolean = false,
    boundaryLinkNext: Boolean = false,
  ): JsonObject {
    val extra = mutableListOf<Pair<String, Any?>>(
      "phenomena" to changes.toList(),
      "diff_cost" to diffCost,
      "low_confidence" to lowConfidence,
      "boundary_link_next" to boundaryLinkNext,
    )
    if (tooShort) extra += "end" to t0 + TOO_SHORT_S
    return AnalysisFixtures.mkAnalysisWord(text, t0, canonical, realized, *extra.toTypedArray())
  }

  /** A word with no change. */
  fun plain(text: String, t0: Double): JsonObject = word(text, t0, canonical = "ð ə", realized = "ð ə", diffCost = 0.0)

  /**
   * Spec F-15-AC1's corpus: nine phrases of 6 s, 4 s apart (so shorts v1 gives nine shorts), holding 12 eligible
   * flapping occurrences (two in each of the first three shorts, one in each other), one low-confidence and one
   * too-short flapping word (not eligible), two linking words and a t/d deletion.
   */
  fun flappingCorpus(): JsonObject {
    val flaps = listOf("water", "better", "city", "little", "matter", "getting", "party", "pretty", "butter")
    val phrases = flaps.mapIndexed { i, text ->
      val start = i * PHRASE_STEP_S
      val words = mutableListOf(
        plain("the", start + 0.1),
        word(text, start + 0.5, "flapping", diffCost = 0.1 * (i + 1)),
      )
      if (i < 3) words += word("${text}s", start + 1.5, "flapping", diffCost = 0.05 + 0.1 * i)
      when (i) {
        3 -> words += word("butter", start + 2.5, "flapping", diffCost = 0.01, lowConfidence = true)
        4 -> words += word("got", start + 2.5, "flapping", diffCost = 0.01, tooShort = true)
        5 -> words += word("an", start + 2.5, "linking", canonical = "æ n", realized = "ə n", boundaryLinkNext = true)
        6 -> {
          words += word("an", start + 2.5, "linking", canonical = "æ n", realized = "ə n", boundaryLinkNext = true)
          words += word("last", start + 3.5, "t_deletion", canonical = "l æ s t", realized = "l æ s")
        }
      }
      words += plain("end", start + 5.0)
      phrase(start, start + PHRASE_S, *words.toTypedArray())
    }
    return analysis(*phrases.toTypedArray())
  }

  private const val TOO_SHORT_S = 0.04
  private const val PHRASE_S = 6.0
  private const val PHRASE_STEP_S = 10.0
}
