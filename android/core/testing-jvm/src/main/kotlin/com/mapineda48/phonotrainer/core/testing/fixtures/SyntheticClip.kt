package com.mapineda48.phonotrainer.core.testing.fixtures

import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.json
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.obj
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.round1
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.round3
import kotlinx.serialization.json.JsonObject
import java.util.Random

/**
 * [AnalysisFixtures.synthetic]: a long rules-3 analysis with the proportions of the owner's largest real one (about
 * 6.5 words a phrase, phrases of 1 to 4 s with some of 10 s and more, pauses from overlaps to several seconds, pitch
 * every 10 ms, a third of the words labelled, a few low-confidence words), so the parse budget (NFR-02) and the
 * shorts rule meet realistic sizes. Not phonetically meaningful.
 */
internal class SyntheticClip(private val wordCount: Int, private val phraseCount: Int, seed: Long) {
  private val random = Random(seed)

  init {
    require(phraseCount > 0 && wordCount >= phraseCount) { "every phrase needs at least one word" }
  }

  fun build(): JsonObject {
    val counts = wordsPerPhrase()
    val segments = ArrayList<JsonObject>(phraseCount)
    val labelCounts = LinkedHashMap<String, Int>()
    var cursor = 0.4
    for (i in 0 until phraseCount) {
      val segment = segment(cursor, counts[i], labelCounts)
      segments += segment.json
      cursor = segment.end + pause()
    }
    val duration = round3(cursor + 1.0)
    return obj(
      "meta" to mapOf(
        "source" to "Synthetic clip [synthetic].webm", "duration" to duration, "language" to "en",
        "phonotrainer_version" to "0.1.0", "rules_version" to 3, "phone_engine" to "timit61",
        "models" to mapOf(
          "asr" to "faster-whisper small (int8)", "phones" to "timit61", "alignment" to "forced_align",
        ),
        "attraction" to false,
        "dialogue_separation" to mapOf("applied" to true, "model" to "htdemucs", "audio" to "audio_dialogue.wav"),
        "form_scoring" to null,
        "phone_cleanup" to mapOf("normalized_phones" to 0, "attracted_phones" to 0),
      ),
      "segments" to segments,
      "summary" to mapOf(
        "phenomena_counts" to labelCounts,
        "metrics" to AnalysisFixtures.metrics(labelCounts, espeak = false),
      ),
    )
  }

  /** Words per phrase: an even split, then words moved at random so a few phrases are long. */
  private fun wordsPerPhrase(): IntArray {
    val counts = IntArray(phraseCount) { wordCount / phraseCount + if (it < wordCount % phraseCount) 1 else 0 }
    repeat(phraseCount * 2) {
      val from = random.nextInt(phraseCount)
      val to = random.nextInt(phraseCount)
      if (counts[from] > 1 && counts[to] < MAX_WORDS) {
        counts[from] -= 1
        counts[to] += 1
      }
    }
    return counts
  }

  private class Segment(val json: JsonObject, val end: Double)

  private fun segment(start: Double, words: Int, labelCounts: MutableMap<String, Int>): Segment {
    var cursor = start + 0.05
    var lastEnd = cursor
    val wordJson = ArrayList<JsonObject>(words)
    val texts = ArrayList<String>(words)
    repeat(words) {
      val text = VOCABULARY[random.nextInt(VOCABULARY.size)]
      val phones = 2 + random.nextInt(5)
      val canonical = (0 until phones).map { CONSONANTS_AND_VOWELS[random.nextInt(CONSONANTS_AND_VOWELS.size)] }
      val labels = labels()
      labels.forEach { labelCounts[it] = (labelCounts[it] ?: 0) + 1 }
      val realized = if (labels.isEmpty()) canonical else canonical.mapIndexed { k, p -> if (k == 1) "ɾ" else p }
      val linkNext = "linking" in labels || "palatalization" in labels
      wordJson += AnalysisFixtures.mkAnalysisWord(
        text, cursor, canonical.joinToString(" "), realized.joinToString(" "),
        "dict_ipa" to "ˈ" + canonical.joinToString(""),
        "phenomena" to labels,
        "diff_cost" to if (labels.isEmpty()) 0.0 else round3(random.nextDouble()),
        "low_confidence" to (random.nextInt(100) < LOW_CONFIDENCE_PCT),
        "boundary_link_next" to linkNext,
        "boundary_link_type" to if (linkNext) "consonant" else null,
        "canonical_text" to null, "no_canonical" to false, "lexical_expansion" to null, "form" to null,
        "variant_labels" to emptyList<String>(),
      )
      texts += text
      lastEnd = round3(cursor + (phones - 1) * 0.06 + 0.05)
      cursor = round3(lastEnd + 0.02 + random.nextInt(14) / 100.0)
    }
    val end = round3(lastEnd + 0.1)
    val track = ArrayList<List<Double>>()
    var t = start
    var hz = 120.0 + random.nextInt(120)
    while (t <= end) {
      track += listOf(round3(t), round1(hz))
      hz = (hz + random.nextGaussian() * 2.0).coerceIn(70.0, 400.0)
      t += 0.01
    }
    val prominence = (0 until words).map { round3(random.nextDouble()) }
    val json = obj(
      "start" to round3(start), "end" to end, "text" to texts.joinToString(" "),
      "f0_stats" to mapOf(
        "mean" to round1(hz), "range" to round1(20.0 + random.nextInt(100)),
        "final_contour" to CONTOURS[random.nextInt(CONTOURS.size)],
        "range_st" to round1(random.nextDouble() * 10), "final_slope_st" to round1(random.nextGaussian() * 10),
        "f0_floor" to 75.0, "f0_ceiling" to 450.0,
      ),
      "f0_track" to track,
      "emphasis_word_idx" to prominence.indices.maxByOrNull { prominence[it] },
      "prominence" to prominence,
      "word_classes" to (0 until words).map { if (random.nextBoolean()) "content" else "function" },
      "intonation_units" to listOf(
        mapOf(
          "start" to round3(start), "end" to end, "words" to listOf(0, words - 1),
          "text" to texts.joinToString(" "),
          "type" to "statement", "final_contour" to "falling", "final_slope_st" to -3.0,
          "expected_contour" to "falling", "matches_expected" to true, "uptalk" to false,
        ),
      ),
      "rhythm" to if (words < 3) {
        null
      } else {
        mapOf(
          "npvi" to 45.0, "varco" to 38.0, "n_intervals" to words, "n_pairs" to words - 1, "mean_ms" to 180.0,
          "sd_ms" to 60.0, "approximate" to true, "method" to "inter-nucleus intervals (CTC peaks)",
        )
      },
      "words" to json(wordJson),
    )
    return Segment(json, end)
  }

  /** About a third of the words carry one label, a few two. */
  private fun labels(): List<String> {
    val roll = random.nextInt(100)
    return when {
      roll < 28 -> listOf(LABELS[random.nextInt(LABELS.size)])
      roll < 34 -> listOf(LABELS[random.nextInt(LABELS.size)], LABELS[random.nextInt(LABELS.size)]).distinct()
      else -> emptyList()
    }
  }

  /** Overlaps, short pauses, long pauses and a few very long ones (the shorts rule's three thresholds). */
  private fun pause(): Double {
    val roll = random.nextInt(100)
    return when {
      roll < 10 -> -0.1
      roll < 55 -> 0.1 + random.nextInt(45) / 100.0
      roll < 90 -> 0.6 + random.nextInt(190) / 100.0
      else -> 3.0 + random.nextInt(200) / 100.0
    }
  }

  private companion object {
    const val MAX_WORDS = 35
    const val LOW_CONFIDENCE_PCT = 4
    val CONTOURS = listOf("rising", "falling", "flat")
    val LABELS = listOf(
      "vowel_reduction", "monophthongization", "elision_syllable", "function_elision", "flapping", "t_deletion",
      "t_unreleased", "glottalization", "nt_reduction", "th_stopping", "palatalization", "place_assimilation",
      "linking", "h_dropping", "contraction_lex", "word_elision",
    )
    val CONSONANTS_AND_VOWELS = listOf(
      "p", "b", "t", "d", "k", "ɡ", "m", "n", "ŋ", "f", "v", "θ", "ð", "s", "z", "ʃ", "h", "l", "ɹ", "w", "j",
      "tʃ", "dʒ", "i", "ɪ", "ɛ", "æ", "ɑ", "ʌ", "ə", "ɚ", "ʊ", "u", "aɪ", "aʊ", "eɪ", "oʊ", "ɔɪ",
    )
    val VOCABULARY = listOf(
      "the", "a", "to", "and", "you", "it", "that", "what", "is", "going", "but", "traffic", "pretty", "much",
      "water", "better", "little", "get", "got", "about", "really", "know", "think", "people", "right", "just",
      "gonna", "wanna", "kind", "of", "want", "need", "time", "day", "said", "let's", "okay", "yeah", "button",
    )
  }
}
