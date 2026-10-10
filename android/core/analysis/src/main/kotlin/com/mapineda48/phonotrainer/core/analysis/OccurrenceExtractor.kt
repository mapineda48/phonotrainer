package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.util.Locale

/**
 * One word × change of a clip: a row of the `occurrence` table (architecture 4.2, 9.4), field for field, so WP-10's
 * indexer stores it as is and its queries read it back. [eligible] rows are exactly the spotlight candidates of
 * architecture 9.3 (spec D-48: "Flapping · 34" opens 34 shorts).
 */
data class OccurrenceRecord(
  val word: WordRef,
  val change: ChangeLabel,
  /** The first phrase of the short (shorts v1) the word belongs to. */
  val shortFirstSegment: Int,
  /** The word as transcribed. */
  val wordText: String,
  /** [WordKey] of [wordText]: "one per word" in examples. */
  val wordKey: String,
  val dictIpa: String?,
  val realizedIpa: String?,
  /** The word's bounds (ms on the analysed timeline). */
  val startMs: Long,
  val endMs: Long,
  val diffCost: Double,
  val lowConfidence: Boolean,
  /** The web's playable rule: not low confidence, lasting at least 60 ms, with a heard form. */
  val eligible: Boolean,
  /** The change happens at the boundary with the next word on the timeline, and there is one (`crossesBoundary`). */
  val crossesBoundary: Boolean,
  /** The next word on the timeline ("did‿you"), or null at the end of the clip. */
  val nextWordText: String?,
  val advice: Advice,
  val family: Family,
) {
  /** The domain view of this row ([Occurrence]); [otherChanges] are the word's other labels (the quiz needs them). */
  fun toOccurrence(clipTitle: String, otherChanges: List<ChangeLabel> = emptyList()): Occurrence = Occurrence(
    word = word,
    shortFirstSegment = shortFirstSegment,
    wordText = wordText,
    dictIpa = dictIpa,
    realizedIpa = realizedIpa,
    startMs = startMs,
    endMs = endMs,
    change = change,
    diffCost = diffCost,
    advice = advice,
    family = family,
    crossesBoundary = crossesBoundary,
    nextWordText = nextWordText,
    clipTitle = clipTitle,
    otherChanges = otherChanges,
  )
}

/**
 * Derives the `occurrence` rows of a clip (architecture 9.4): one per word and change label, in clip order (phrase,
 * word, then the word's label order), with eligibility, advice and family from the reference.
 */
object OccurrenceExtractor {
  /**
   * The rows of [clip], each tagged with the short of [shorts] (from [ShortsV1.derive] on the same clip) that holds
   * its phrase.
   *
   * @throws IllegalArgumentException when a phrase with a labelled word is in none of [shorts].
   */
  fun extract(clip: ClipData, shorts: List<ShortBounds>, advice: ChangeAdvice): List<OccurrenceRecord> {
    val shortOf = IntArray(clip.phrases.size) { NO_SHORT }
    for (short in shorts) {
      for (segment in short.firstSegment..short.lastSegment) {
        if (segment in shortOf.indices) shortOf[segment] = short.firstSegment
      }
    }
    val out = ArrayList<OccurrenceRecord>()
    for (phrase in clip.phrases) {
      for (word in phrase.words) {
        if (word.changes.isEmpty()) continue
        val shortFirst = shortOf[phrase.index]
        require(shortFirst != NO_SHORT) { "phrase ${phrase.index} is in no short" }
        val ref = WordRef(clip.jobId, phrase.index, word.index)
        val next = clip.nextWord(ref)?.let(clip::word)
        val eligible = isEligible(word)
        for (label in word.changes) {
          out += OccurrenceRecord(
            word = ref,
            change = label,
            shortFirstSegment = shortFirst,
            wordText = word.text,
            wordKey = WordKey.of(word.text),
            dictIpa = word.dictIpa,
            realizedIpa = word.realizedIpa,
            startMs = toMs(word.startS),
            endMs = toMs(word.endS),
            diffCost = word.diffCost,
            lowConfidence = word.lowConfidence,
            eligible = eligible,
            crossesBoundary = word.crossesBoundary && next != null,
            nextWordText = next?.text,
            advice = advice.advice(word, label),
            family = advice.family(label),
          )
        }
      }
    }
    return out
  }

  /**
   * The web's playable rule, which makes a word a spotlight candidate, an example and a quiz item: not low
   * confidence, at least 60 ms long ([Word.tooShort]), and something was heard ([Word.realizedIpa]).
   */
  fun isEligible(word: Word): Boolean = !word.lowConfidence && !word.tooShort && word.realizedIpa != null

  /**
   * [records] as domain [Occurrence]s of the clip [clipTitle], each with [Occurrence.otherChanges] filled from the
   * other rows of its word (the quiz never offers a label the word also has as a wrong answer).
   */
  fun occurrences(records: List<OccurrenceRecord>, clipTitle: String): List<Occurrence> {
    val labels = records.groupBy({ it.word }, { it.change })
    return records.map { record ->
      record.toOccurrence(clipTitle, labels.getValue(record.word).filter { it != record.change })
    }
  }

  private const val NO_SHORT = -1
}

/** "That,", "that" and "THAT" are one word: lower case, letters a–z and apostrophes only (web `pickExamples`). */
object WordKey {
  fun of(text: String): String = text.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it == '\'' }
}
