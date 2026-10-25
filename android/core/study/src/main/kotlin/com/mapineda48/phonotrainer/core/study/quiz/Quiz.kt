package com.mapineda48.phonotrainer.core.study.quiz

import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.PhraseWord
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordVariant

// Ear-training questions: a Kotlin port of web/src/features/practice/quiz.ts (architecture 10; spec F-08 item 5,
// F-17), pure logic. Items are built from corpus occurrences, word variants and analysis phrases. Three kinds:
//   form   — hear a word; which pronunciation was it? (what was said vs the dictionary form vs other ways the same
//            word was said in the corpus)
//   change — hear a word; which change happened?
//   count  — hear a short phrase; how many words were in it?
// Differences from the web, each forced by the phone's types: a `ClipSpan` carries no padding, so the web's
// `padBefore`/`padAfter` are folded into its bounds (milliseconds, the start never below 0); an occurrence's
// `phenomena` are its `change` plus its `otherChanges`; the pool holds only eligible occurrences of playable clips
// (WP-10), so `isPlayable` keeps its one remaining test, the 60 ms rule.

/** An occurrence a quiz can use, tagged with the change it was fetched for (web `PoolEntry`). */
data class PoolEntry(val change: ChangeLabel, val occurrence: Occurrence)

/** The web's `{seen, correct}` per change (`PhenomenonStats`). */
data class Tally(val seen: Int, val correct: Int)

object Quiz {
  /** Questions in a Practice session (web `SESSION_LENGTH`). */
  const val SESSION_LENGTH = 10

  /** The phrase sizes a "How many words?" item accepts (web `MIN_PHRASE`, `MAX_PHRASE`). */
  const val MIN_PHRASE = 3
  const val MAX_PHRASE = 9

  private const val ELIDED_WORD = "word_elision"
  private const val TOO_SHORT_MS = 60L

  /** Changes that make sense in a quiz: every labelled change except "elided word" (nothing to hear). */
  fun quizChanges(reference: Reference): List<ChangeLabel> = reference.labels.keys.filter { it.value != ELIDED_WORD }

  /** The web's `buildPool`: every occurrence that can be played and has a heard form, tagged with its list's change. */
  fun buildPool(byChange: Map<ChangeLabel, List<Occurrence>>): List<PoolEntry> = byChange.flatMap { (change, list) ->
    list.filter { isPlayable(it) && !it.realizedIpa.isNullOrEmpty() }.map { PoolEntry(change, it) }
  }

  /** The web's `isPlayable` on the phone's eligible rows: at least 60 ms long. */
  fun isPlayable(occurrence: Occurrence): Boolean = occurrence.endMs - occurrence.startMs >= TOO_SHORT_MS

  // ---- Stats ---------------------------------------------------------------------------------------------------

  fun recordAnswer(stats: Map<ChangeLabel, Tally>, change: ChangeLabel, correct: Boolean): Map<ChangeLabel, Tally> {
    val before = stats[change] ?: Tally(0, 0)
    return stats + (change to Tally(before.seen + 1, before.correct + if (correct) 1 else 0))
  }

  /** Accuracy 0–1, or null before the first answer. */
  fun accuracy(tally: Tally?): Double? = if (tally != null && tally.seen > 0) tally.correct.toDouble() / tally.seen else null

  // ---- Drawing an occurrence -----------------------------------------------------------------------------------

  /**
   * Picks from the pool: a change first (so a change with 200 examples does not drown one with 3), then an occurrence
   * inside it. With [weakSpots], changes with lower accuracy (and unseen ones) are drawn more often: weight =
   * 1.5 − accuracy (unseen counts as 0.5). [avoid] holds entry keys already used, left out while anything else is left.
   */
  fun drawEntry(
    pool: List<PoolEntry>,
    rng: Rng,
    stats: Map<ChangeLabel, Tally> = emptyMap(),
    weakSpots: Boolean = false,
    focus: ChangeLabel? = null,
    avoid: Set<String> = emptySet(),
  ): PoolEntry? {
    var candidates = if (focus != null) pool.filter { it.change == focus } else pool
    if (avoid.isNotEmpty() && candidates.any { entryKey(it) !in avoid }) {
      candidates = candidates.filter { entryKey(it) !in avoid }
    }
    if (candidates.isEmpty()) return null

    val names = candidates.map { it.change }.distinct()
    val weights = names.map { name -> if (!weakSpots) 1.0 else 1.5 - (accuracy(stats[name]) ?: 0.5) }
    var roll = rng.next() * weights.sum()
    var chosen = names.last()
    for (i in names.indices) {
      roll -= weights[i]
      if (roll < 0) {
        chosen = names[i]
        break
      }
    }
    val inside = candidates.filter { it.change == chosen }
    return inside.getOrNull((rng.next() * inside.size).toInt())
  }

  /** One occurrence of one word (web `entryKey`: analysis, segment, word index). */
  fun entryKey(entry: PoolEntry): String = entry.occurrence.word.let { "${it.jobId.value}:${it.segment}:${it.word}" }

  // ---- Clips ---------------------------------------------------------------------------------------------------

  /** The word with 0.1 s before and 0.12 s after (0.45 s after a boundary change, which needs the next word). */
  fun wordClip(occurrence: Occurrence, family: Family?): ClipSpan =
    span(occurrence, before = 100, after = if (family == Family.BOUNDARY) 450 else 120)

  /** The word with 1.2 s around it ("With context"). */
  fun contextClip(occurrence: Occurrence): ClipSpan = span(occurrence, before = 1_200, after = 1_200)

  private fun span(occurrence: Occurrence, before: Long, after: Long): ClipSpan =
    ClipSpan(occurrence.word.jobId, (occurrence.startMs - before).coerceAtLeast(0), occurrence.endMs + after, occurrence.word)

  // ---- Item: which change? -------------------------------------------------------------------------------------

  /**
   * The right change plus up to three others the word does NOT have, preferring other families so the choice is about
   * hearing, not about fine labels (spec F-17-AC3). A change without a family counts as lexical, as on the web.
   */
  fun changeOptions(
    entry: PoolEntry,
    reference: Reference,
    rng: Rng,
    candidates: List<ChangeLabel> = quizChanges(reference),
  ): List<QuestionOption> {
    val present = (entry.occurrence.otherChanges + entry.occurrence.change + entry.change).toSet()
    fun family(name: ChangeLabel): Family = reference.familyOf[name] ?: Family.LEXICAL
    val others = shuffle(candidates.filter { it !in present }, rng)
    val picked = ArrayList<ChangeLabel>()
    val usedFamilies = mutableSetOf(family(entry.change))
    for (name in others) {
      if (picked.size >= 3) break
      if (family(name) in usedFamilies) continue
      picked += name
      usedFamilies += family(name)
    }
    for (name in others) {
      if (picked.size >= 3) break
      if (name !in picked) picked += name
    }
    return shuffle(listOf(entry.change) + picked, rng).map { name ->
      QuestionOption(id = name.value, label = reference.labels[name] ?: name.value, ipa = null, change = name)
    }
  }

  fun changeItem(entry: PoolEntry, reference: Reference, rng: Rng): Question = Question(
    kind = QuestionKind.CHANGE,
    key = "change:${entryKey(entry)}",
    prompt = "Listen to “${displayWord(entry.occurrence.wordText)}”. Which change did you hear?",
    clip = wordClip(entry.occurrence, reference.familyOf[entry.change]),
    context = contextClip(entry.occurrence),
    options = changeOptions(entry, reference, rng),
    answer = entry.change.value,
    change = entry.change,
    occurrence = entry.occurrence,
    phrase = null,
  )

  // ---- Item: which pronunciation? ------------------------------------------------------------------------------

  /**
   * Length marks, ties and white space. JavaScript's `\s` is Unicode-aware: the separators of `\p{Z}` and U+FEFF
   * besides ASCII white space. Spelled out because Android's ICU regex engine rejects the `(?U)` flag.
   */
  private val NOT_COMPARED = Regex("[ː‿\\s\\p{Z}\\uFEFF]")

  private fun normalize(ipa: String): String = IpaTokenizer.stripStress(ipa).replace(NOT_COMPARED, "")

  /**
   * What was said, the dictionary form, and up to two other attested pronunciations of the same word (2 to 4 options,
   * spec F-17 item 2). Null when there is nothing to choose between.
   */
  fun formOptions(occurrence: Occurrence, variants: List<WordVariant>, rng: Rng): List<QuestionOption>? {
    val said = occurrence.realizedIpa?.takeIf { it.isNotEmpty() } ?: return null
    val seen = mutableSetOf(normalize(said))
    val options = mutableListOf(QuestionOption(id = SAID, label = "[$said]", ipa = said, change = null))
    val dictionary = occurrence.dictIpa?.takeIf { it.isNotEmpty() }?.let(IpaTokenizer::stripStress)
    if (dictionary != null && normalize(dictionary) !in seen) {
      seen += normalize(dictionary)
      options += QuestionOption(id = "dictionary", label = "[$dictionary]", ipa = dictionary, change = null)
    }
    val others = variants
      .filter { it.realizedIpa.isNotEmpty() && normalize(it.realizedIpa) !in seen }
      .sortedByDescending { it.count }
    for (variant in others) {
      if (options.size >= 4) break
      val ipa = variant.realizedIpa
      if (normalize(ipa) in seen) continue
      seen += normalize(ipa)
      options += QuestionOption(id = "variant:${options.size}", label = "[$ipa]", ipa = ipa, change = null)
    }
    if (options.size < 2) return null
    return shuffle(options, rng)
  }

  fun formItem(entry: PoolEntry, variants: List<WordVariant>, reference: Reference, rng: Rng): Question? {
    val options = formOptions(entry.occurrence, variants, rng) ?: return null
    return Question(
      kind = QuestionKind.FORM,
      key = "form:${entryKey(entry)}",
      prompt = "Listen to “${displayWord(entry.occurrence.wordText)}”. Which pronunciation did you hear?",
      clip = wordClip(entry.occurrence, reference.familyOf[entry.change]),
      context = contextClip(entry.occurrence),
      options = options,
      answer = SAID,
      change = entry.change,
      occurrence = entry.occurrence,
      phrase = null,
    )
  }

  // ---- Item: how many words? -----------------------------------------------------------------------------------

  /**
   * A short phrase around word [wordIdx]: its intonation unit if that is 3 to 9 words, else the whole phrase if it is,
   * else a window of 6 words around it. Null when the phrase is too short or the index is outside it.
   */
  fun phraseAround(phrase: Phrase, wordIdx: Int): List<Word>? {
    val words = phrase.words
    if (wordIdx < 0 || wordIdx >= words.size) return null
    val unit = phrase.intonationUnits.firstOrNull { it.firstWord <= wordIdx && wordIdx <= it.lastWord }
    if (unit != null) {
      val inside = words.subList(unit.firstWord, (unit.lastWord + 1).coerceAtMost(words.size))
      if (inside.size in MIN_PHRASE..MAX_PHRASE) return inside.toList()
    }
    if (words.size in MIN_PHRASE..MAX_PHRASE) return words.toList()
    if (words.size < MIN_PHRASE) return null
    val size = 6
    val from = maxOf(0, minOf(wordIdx - 2, words.size - size))
    return words.subList(from, minOf(from + size, words.size)).toList()
  }

  /** Four plausible counts including the right one, never below 1. */
  fun countOptions(count: Int, rng: Rng): List<QuestionOption> {
    val values = linkedSetOf(count)
    for (delta in intArrayOf(-1, 1, 2, -2, 3)) {
      if (values.size >= 4) break
      if (count + delta >= 1) values += count + delta
    }
    return shuffle(values.toList(), rng).map { value ->
      QuestionOption(id = value.toString(), label = "$value ${if (value == 1) "word" else "words"}", ipa = null, change = null)
    }
  }

  fun countItem(entry: PoolEntry, phrase: Phrase, rng: Rng): Question? {
    val words = phraseAround(phrase, entry.occurrence.word.word) ?: return null
    if (words.any { it.lowConfidence }) return null
    val count = words.size
    return Question(
      kind = QuestionKind.COUNT,
      key = "count:${entryKey(entry)}",
      prompt = "Listen to the phrase. How many words did you hear?",
      clip = ClipSpan(
        entry.occurrence.word.jobId,
        (toMs(words.first().startS) - 80).coerceAtLeast(0),
        toMs(words.last().endS) + 150,
        null,
      ),
      context = null,
      options = countOptions(count, rng),
      answer = count.toString(),
      change = entry.change,
      occurrence = entry.occurrence,
      phrase = words.map { PhraseWord(it.text, changed = it.changes.isNotEmpty()) },
    )
  }

  /** The item kinds rotate through a session: form, change, count, form, … */
  fun kindForIndex(index: Int): QuestionKind = KINDS[Math.floorMod(index, KINDS.size)]

  /** A transcript word without the punctuation Whisper attaches to it ("order." → "order"; web `displayWord`). */
  fun displayWord(word: String): String = word.replace(EDGE_PUNCTUATION, "").ifEmpty { word }

  /** The answer of a form item: the option that is what was said. */
  const val SAID = "said"

  private val KINDS = listOf(QuestionKind.FORM, QuestionKind.CHANGE, QuestionKind.COUNT)
  private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$")

  private fun toMs(seconds: Double): Long = Math.round(seconds * 1000)
}
