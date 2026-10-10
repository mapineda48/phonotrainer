package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordRef

/**
 * The lesson's Compare step, sound by sound (spec F-10 step 2; a port of the web's
 * `features/analysis/lib/phones.ts` `compareWords`): every expected sound (`canonical_aligned`) paired with the sound
 * heard at the same moment (`realized_aligned`), each column marked = same, ≠ changed, – not heard, + added.
 *
 * The times are CTC peaks (one ~20 ms frame), not segmentations, so "the same moment" allows [FRAME_S] of tolerance
 * either side. Pairing runs in two passes: first the same symbol (ignoring stress and length marks) at the same
 * moment, then any other sound at the same moment (a change); expected sounds left over were not heard, heard sounds
 * left over were added. When the word's change crosses into the next word, the next word is compared on its own and
 * its rows follow (the next word's /t/ must never stand in for a /t/ this word dropped).
 */
object CompareRows {
  /** The same phone detected a frame apart in each row still counts as the same moment (web `FRAME`). */
  const val FRAME_S: Double = 0.04

  /**
   * The rows of the word [ref] of [clip] (`ClipContentRepository.compareRows`): its own, then, when its change
   * crosses the boundary ([Word.crossesBoundary]) and a next word exists on the timeline ([ClipData.nextWord]), the
   * next word's rows with [CompareRow.nextWord] set. Empty when [ref] is not a word of [clip].
   */
  fun of(clip: ClipData, ref: WordRef): List<CompareRow> {
    val word = clip.word(ref) ?: return emptyList()
    val next = if (word.crossesBoundary) clip.nextWord(ref)?.let(clip::word) else null
    return compare(word, next)
  }

  /** [word]'s rows, then [next]'s (each word paired on its own). */
  fun compare(word: Word, next: Word? = null): List<CompareRow> {
    val own = compareOne(word.canonicalPhones, word.realizedPhones, nextWord = false)
    if (next == null) return own
    return own + compareOne(next.canonicalPhones, next.realizedPhones, nextWord = true)
  }

  private fun compareOne(expected: List<TimedPhone>, heard: List<TimedPhone>, nextWord: Boolean): List<CompareRow> {
    val usedHeard = BooleanArray(heard.size)
    val pairOf = arrayOfNulls<Pair<Int, CompareStatus>>(expected.size)

    // 1. the same symbol at the same moment
    expected.forEachIndexed { e, phone ->
      val h = heard.indices.firstOrNull { index ->
        !usedHeard[index] && bare(heard[index]) == bare(phone) && overlaps(phone, heard[index])
      }
      if (h != null) {
        usedHeard[h] = true
        pairOf[e] = h to CompareStatus.SAME
      }
    }
    // 2. something else at the same moment: a change
    expected.forEachIndexed { e, phone ->
      if (pairOf[e] != null) return@forEachIndexed
      val h = heard.indices.firstOrNull { index -> !usedHeard[index] && overlaps(phone, heard[index]) }
      if (h != null) {
        usedHeard[h] = true
        pairOf[e] = h to CompareStatus.CHANGED
      }
    }

    val columns = ArrayList<Column>(expected.size + heard.size)
    expected.forEachIndexed { e, phone ->
      val pair = pairOf[e]
      val row = if (pair != null) {
        row(phone, heard[pair.first], pair.second, nextWord)
      } else {
        row(phone, null, CompareStatus.NOT_HEARD, nextWord)
      }
      columns += Column(row, at = phone.startS)
    }
    // 3. whatever was heard and expected nowhere
    heard.forEachIndexed { h, phone ->
      if (!usedHeard[h]) columns += Column(row(null, phone, CompareStatus.ADDED, nextWord), at = phone.startS)
    }
    // Time order; at one instant the expected sound comes first. The sort is stable, so equal columns keep their
    // order (the web's comparator returns -1 for two expected sounds at one instant, which depends on the engine).
    return columns.sortedWith(compareBy<Column> { it.at }.thenBy { if (it.row.dictionaryPhone != null) 0 else 1 })
      .map { it.row }
  }

  private fun row(expected: TimedPhone?, heard: TimedPhone?, status: CompareStatus, nextWord: Boolean) = CompareRow(
    dictionary = expected?.symbol,
    said = heard?.symbol,
    status = status,
    change = null,
    dictionaryPhone = expected,
    saidPhone = heard,
    nextWord = nextWord,
  )

  private fun overlaps(a: TimedPhone, b: TimedPhone): Boolean =
    a.startS < b.endS + FRAME_S && b.startS < a.endS + FRAME_S

  private fun bare(phone: TimedPhone): String = IpaTokenizer.bareSymbol(phone.symbol)

  private class Column(val row: CompareRow, val at: Double)
}
