package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A phrase as shorts v1 sees it: its position in the clip and its bounds in seconds. */
data class PhraseBounds(val index: Int, val startS: Double, val endS: Double)

/**
 * One short of shorts v1: the phrases [firstSegment]..[lastSegment] (inclusive, consecutive), their bounds
 * ([startMs] = the first phrase's start, [endMs] = the last phrase's end) and the play bounds, in milliseconds on the
 * analysed timeline (the `short` table's columns).
 */
data class ShortBounds(
  val firstSegment: Int,
  val lastSegment: Int,
  val startMs: Long,
  val endMs: Long,
  val playStartMs: Long,
  val playEndMs: Long,
) {
  /** Spec F-06 item 1.0: from the first phrase's start to the last phrase's end. */
  val lengthMs: Long get() = endMs - startMs

  val phraseCount: Int get() = lastSegment - firstSegment + 1

  operator fun contains(segment: Int): Boolean = segment in firstSegment..lastSegment

  /** Its identity (spec F-06 item 1.5), together with [ShortsV1.RULE_VERSION]. */
  fun id(jobId: JobId): ShortId = ShortId(jobId, firstSegment)
}

/**
 * The rule "shorts v1" (spec F-06 item 1, architecture 9.2): a clip's phrases grouped into swipeable shorts of about
 * 5 to 30 s, deterministic and versioned; a phrase is never split.
 *
 * Pass 1 walks the phrases and closes the current short before the next phrase when adding it would make the short
 * longer than [MAX_MS], or the short is already [LONG_ENOUGH_MS] long and the pause before the next phrase is at least
 * [SHORT_PAUSE_MS], or the pause is at least [LONG_PAUSE_MS]. Pass 2, from first to last, merges a short under
 * [MIN_MS] into the previous short when the union is at most [MAX_MS], else into the next one under the same
 * condition. A short's length runs from its first phrase's start to its last phrase's end; a pause is
 * `max(0, next.start - current.end)` (Whisper segments may overlap). Play bounds add [PAD_BEFORE_MS] before (not
 * before 0) and [PAD_AFTER_MS] after (not past the clip's end).
 *
 * Times are compared in whole milliseconds, the precision `analysis.json` writes them in, so a pause from 10.0 s to
 * 10.6 s is 0.6 s exactly (in binary floating point it would be a hair shorter and miss the threshold).
 *
 * Raising [RULE_VERSION] re-indexes every clip (architecture 9.5); everything the learner keeps is anchored to job +
 * segment (+ word), never to a short, so a rule change loses nothing.
 */
object ShortsV1 {
  const val RULE_VERSION: Int = 1

  const val MAX_MS: Long = 30_000
  const val LONG_ENOUGH_MS: Long = 10_000
  const val SHORT_PAUSE_MS: Long = 600
  const val LONG_PAUSE_MS: Long = 3_000
  const val MIN_MS: Long = 5_000
  const val PAD_BEFORE_MS: Long = 250
  const val PAD_AFTER_MS: Long = 400

  /** The shorts of [clip]: its phrases, ending at the clip's duration. */
  fun derive(clip: ClipData): List<ShortBounds> =
    derive(clip.phrases.map { PhraseBounds(it.index, it.startS, it.endS) }, clip.meta.durationS)

  /**
   * The shorts of [phrases] (in clip order, `index` = position) for a clip ending at [clipEndS]. A clip end earlier
   * than the end of the speech is a data error and never cuts a phrase: the play end is at least the last phrase's
   * end. No phrase, no short.
   */
  fun derive(phrases: List<PhraseBounds>, clipEndS: Double): List<ShortBounds> {
    if (phrases.isEmpty()) return emptyList()
    val timed = phrases.map { Timed(it.index, toMs(it.startS), toMs(it.endS)) }
    val clipEndMs = toMs(clipEndS)
    return merge(split(timed)).map { group ->
      val first = group.first()
      val last = group.last()
      ShortBounds(
        firstSegment = first.index,
        lastSegment = last.index,
        startMs = first.startMs,
        endMs = last.endMs,
        playStartMs = max(0L, first.startMs - PAD_BEFORE_MS),
        playEndMs = max(last.endMs, min(clipEndMs, last.endMs + PAD_AFTER_MS)),
      )
    }
  }

  private class Timed(val index: Int, val startMs: Long, val endMs: Long)

  /** Pass 1: close the current short when any of the three rules holds. */
  private fun split(phrases: List<Timed>): MutableList<List<Timed>> {
    val shorts = ArrayList<List<Timed>>()
    var current = arrayListOf(phrases.first())
    for (phrase in phrases.subList(1, phrases.size)) {
      val lengthIfAdded = phrase.endMs - current.first().startMs
      val gap = max(0L, phrase.startMs - current.last().endMs)
      val close = lengthIfAdded > MAX_MS ||
        (length(current) >= LONG_ENOUGH_MS && gap >= SHORT_PAUSE_MS) ||
        gap >= LONG_PAUSE_MS
      if (close) {
        shorts += current
        current = arrayListOf(phrase)
      } else {
        current += phrase
      }
    }
    shorts += current
    return shorts
  }

  /** Pass 2: a short under [MIN_MS] joins the previous one, else the next one, when the union fits in [MAX_MS]. */
  private fun merge(shorts: MutableList<List<Timed>>): List<List<Timed>> {
    var i = 0
    while (i < shorts.size) {
      val short = shorts[i]
      if (length(short) < MIN_MS) {
        if (i > 0 && length(shorts[i - 1] + short) <= MAX_MS) {
          shorts[i - 1] = shorts[i - 1] + short
          shorts.removeAt(i)
          continue
        }
        if (i + 1 < shorts.size && length(short + shorts[i + 1]) <= MAX_MS) {
          shorts[i + 1] = short + shorts[i + 1]
          shorts.removeAt(i)
          continue
        }
      }
      i += 1
    }
    return shorts
  }

  private fun length(group: List<Timed>): Long = group.last().endMs - group.first().startMs
}

/** Seconds to milliseconds, halves up (the web's `Math.round`). */
internal fun toMs(seconds: Double): Long = (seconds * 1000.0).roundToLong()
