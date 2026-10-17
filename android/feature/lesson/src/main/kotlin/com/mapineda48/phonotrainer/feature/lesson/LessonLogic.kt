package com.mapineda48.phonotrainer.feature.lesson

import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordClass
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The lesson's pure rules (spec F-10, F-12; the web's `WordLesson` and its steps): moving between words, what each
 * step shows for a word, and the spans it plays. Everything here reads a parsed [ClipData]; the Compare rows come
 * from `ClipContentRepository.compareRows` (a feature never computes them itself, delivery review D-07).
 */
internal object LessonLogic {
  /** The web's `wordSpan` padding: a little before the word and a little more after it. */
  private const val PAD_BEFORE_S = 0.02
  private const val PAD_AFTER_S = 0.06

  /** Around one sound, so it can be heard (web `PAD`). */
  private const val SOUND_PAD_S = 0.02

  /** A phrase longer than this is hard to shadow in one breath: practice starts from the word (web). */
  const val LONG_PHRASE_S: Double = 6.0

  /** At or under this gap two words run together (web `LINKED_GAP_MS`). */
  const val LINKED_GAP_MS: Int = 30

  /** A function word at its phrase's peak (web: `prominence >= 0.999`). */
  private const val PEAK = 0.999f

  private const val PERCENT = 100
  private const val MS_PER_S = 1000.0
  private const val TENTHS_PER_MINUTE = 600L
  private const val TENTHS = 10.0

  /** Changes heard at the boundary with the next word (web `lib/words.ts`). */
  private val BOUNDARY = setOf("linking", "palatalization")
  private const val LEXICAL_CONTRACTION = "contraction_lex"
  private const val LINKING = "linking"
  private const val WORD_ELISION = "word_elision"

  /** "Word 12 of 431": the word's 1-based place among every word of the clip. */
  fun position(clip: ClipData, ref: WordRef): Int =
    clip.phrases.take(ref.segment).sumOf { it.words.size } + ref.word + 1

  /** The word before [ref] on the timeline (the last word of an earlier phrase when [ref] starts its phrase). */
  fun previous(clip: ClipData, ref: WordRef): WordRef? {
    if (clip.word(ref) == null) return null
    if (ref.word > 0) return ref.copy(word = ref.word - 1)
    for (segment in ref.segment - 1 downTo 0) {
      val words = clip.phrases[segment].words
      if (words.isNotEmpty()) return WordRef(clip.jobId, segment, words.lastIndex)
    }
    return null
  }

  /** The next word on the timeline ("Word ›"). */
  fun next(clip: ClipData, ref: WordRef): WordRef? = clip.nextWord(ref)

  /** "Next change": the next word with at least one change, possibly in a later phrase (F-10-AC5). */
  fun nextChange(clip: ClipData, ref: WordRef): WordRef? {
    var at = clip.nextWord(ref)
    while (at != null) {
      if (clip.word(at)?.changes?.isNotEmpty() == true) return at
      at = clip.nextWord(at)
    }
    return null
  }

  /** The next word when the change crosses the boundary (web `crossesBoundary`), else null. */
  fun crossingNext(clip: ClipData, ref: WordRef): Word? {
    val word = clip.word(ref) ?: return null
    return if (word.crossesBoundary) clip.nextWord(ref)?.let(clip::word) else null
  }

  /** The web's `reliability`: nothing heard, an unsure recognizer (or a word elision), or fine. */
  fun reliability(word: Word): Reliability = when {
    word.nothingHeard -> Reliability.NOTHING_HEARD
    word.lowConfidence || word.changes.any { it.value == WORD_ELISION } -> Reliability.LOW_CONFIDENCE
    else -> Reliability.OK
  }

  /** The report's advice for [label] on [word]: a lexical contraction takes its own reduced form's advice (web). */
  fun advice(reference: Reference, word: Word, label: ChangeLabel): PracticeAdvice? {
    val form = word.lexicalForm
    if (label.value == LEXICAL_CONTRACTION && form != null) {
      return reference.lexicalPractice[form] ?: reference.lexicalPractice[form.lowercase(Locale.ROOT)] ?: reference.practice[label]
    }
    return reference.practice[label]
  }

  /** A change as the lesson shows it; an unknown label is named by its key. */
  fun change(reference: Reference, word: Word, label: ChangeLabel): ChangeUi = ChangeUi(
    label = label,
    name = reference.labels[label] ?: label.value.replace('_', ' '),
    family = reference.familyFor(label),
    advice = advice(reference, word, label),
  )

  /** The word without the punctuation around it ("water?" → "water"); a bare symbol stays as it is ("%"). */
  fun displayText(text: String): String {
    val trimmed = text.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
    return trimmed.ifEmpty { text.trim() }
  }

  /** "0:44.8" (web `fmtTime`: rounded to tenths before splitting into minutes). */
  fun clock(seconds: Double): String {
    val tenths = (max(0.0, seconds) * TENTHS).roundToLong()
    val minutes = tenths / TENTHS_PER_MINUTE
    val rest = (tenths - minutes * TENTHS_PER_MINUTE) / TENTHS
    return String.format(Locale.ROOT, "%d:%04.1f", minutes, rest)
  }

  private fun ms(seconds: Double): Long = (max(0.0, seconds) * MS_PER_S).roundToLong()

  /** The word, padded as the web pads it (the player stretches anything under 0.25 s). */
  fun wordSpan(clip: ClipData, ref: WordRef, word: Word): ClipSpan =
    ClipSpan(clip.jobId, ms(word.startS - PAD_BEFORE_S), ms(word.endS + PAD_AFTER_S), ref)

  /** From the word's start to the end of [last] ("Play with “you”", "The word and the next"). */
  fun spanThrough(clip: ClipData, ref: WordRef, word: Word, last: Word): ClipSpan =
    ClipSpan(clip.jobId, ms(word.startS - PAD_BEFORE_S), ms(max(word.endS, last.endS) + PAD_AFTER_S), ref)

  /** The whole phrase, unpadded (web: `segment.start`–`segment.end`). */
  fun phraseSpan(clip: ClipData, ref: WordRef): ClipSpan {
    val phrase = clip.phrases[ref.segment]
    return ClipSpan(clip.jobId, ms(phrase.startS), ms(phrase.endS), null)
  }

  /** One sound, with a little room around it (web `PAD`). */
  fun soundSpan(clip: ClipData, phone: TimedPhone, word: WordRef?): ClipSpan =
    ClipSpan(clip.jobId, ms(phone.startS - SOUND_PAD_S), ms(phone.endS + SOUND_PAD_S), word)

  /**
   * The next word shadowing adds to the word: the one across the boundary when the change crosses it, otherwise the
   * next word of the same phrase (never a word after a pause into the next phrase).
   */
  fun practiceNext(clip: ClipData, ref: WordRef): Word? =
    crossingNext(clip, ref) ?: clip.phrases[ref.segment].words.getOrNull(ref.word + 1)

  /** The practice target a lesson starts with: the phrase when it is 6 s or shorter, else the word (web). */
  fun defaultTarget(clip: ClipData, ref: WordRef): ShadowingTarget {
    val phrase = clip.phrases[ref.segment]
    return if (phrase.endS - phrase.startS <= LONG_PHRASE_S) ShadowingTarget.PHRASE else ShadowingTarget.WORD
  }

  /** Step 2's sorts from the repository's rows; each sort plays its heard sound, or its expected one. */
  fun sorts(rows: List<CompareRow>): List<SortUi> = rows.mapIndexed { index, row ->
    val phone = row.saidPhone ?: row.dictionaryPhone
    SortUi(
      index = index,
      dictionary = row.dictionary,
      said = row.said,
      status = row.status,
      nextWord = row.nextWord,
      startMs = phone?.let { ms(it.startS - SOUND_PAD_S) } ?: 0L,
      endMs = phone?.let { ms(it.endS + SOUND_PAD_S) } ?: 0L,
    )
  }

  /** The sound card of [sort] (design 9.10 item 5), named from the articulation table and the glossary. */
  fun sound(sort: SortUi, didactic: DidacticContent): SoundUi {
    val symbol = sort.said ?: sort.dictionary.orEmpty()
    val phone = didactic.phone(symbol)
    val key = listOf("ipa:$symbol", "ipa:${IpaReading.bare(symbol)}").firstOrNull { didactic.glossary(it) != null }
    return SoundUi(sort, symbol, phone?.name, phone?.example, phone?.cue, key)
  }

  /** Step 2's forms and sorts for [ref]. */
  fun compare(
    clip: ClipData,
    ref: WordRef,
    rows: List<CompareRow>,
    reference: Reference,
    didactic: DidacticContent,
    selected: Int?,
  ): CompareUi {
    val word = checkNotNull(clip.word(ref))
    val next = crossingNext(clip, ref)
    val sorts = sorts(rows)
    val narrow = clip.meta.narrowEngine
    val dictionary = if (word.noDictionaryForm) null else joinForms(word.dictIpa ?: word.canonicalIpa, next?.dictIpa, " ")
    val said = joinForms(word.realizedIpa, next?.realizedIpa, if (word.boundaryLinkNext) "‿" else " ")
    val aligner = if (narrow) null else joinForms(word.canonicalIpa, next?.canonicalIpa, " ")
    val spoken = word.canonicalText?.trim()?.takeIf { it.isNotEmpty() && !it.equals(word.text.trim(), ignoreCase = true) }
    val gap = if (next == null) null else clip.boundaryGapMs(ref)?.let { GapUi(it, boundaryFamily(reference, word)) }
    return CompareUi(
      dictionary = dictionary,
      said = said,
      aligner = aligner,
      narrow = narrow,
      spoken = spoken,
      noDictionaryForm = word.noDictionaryForm,
      sorts = sorts,
      wordText = displayText(word.text),
      nextText = next?.let { displayText(it.text) },
      gap = gap,
      selected = selected?.let(sorts::getOrNull)?.let { sound(it, didactic) },
    )
  }

  private fun joinForms(first: String?, second: String?, joiner: String): String? = when {
    first.isNullOrEmpty() -> second?.takeIf { it.isNotEmpty() }
    second.isNullOrEmpty() -> first
    else -> first + joiner + second
  }

  /** The family whose tint the gap box wears: the word's boundary change, else its first change with a family. */
  private fun boundaryFamily(reference: Reference, word: Word): Family {
    val boundary = word.changes.firstOrNull { it.value in BOUNDARY }
    return boundary?.let(reference::familyFor)
      ?: word.changes.map(reference::familyFor).firstOrNull { it != Family.OTHER }
      ?: Family.BOUNDARY
  }

  /** Step 3 for [ref]. */
  fun why(clip: ClipData, ref: WordRef, reference: Reference): WhyUi {
    val word = checkNotNull(clip.word(ref))
    val phrase = clip.phrases[ref.segment]
    val narrow = clip.meta.narrowEngine
    val cards = word.changes.map { label ->
      ChangeCardUi(
        change = change(reference, word, label),
        description = reference.descriptions[label],
        linkType = if (label.value == LINKING) word.linkType else null,
        lexicalForm = if (label.value == LEXICAL_CONTRACTION) word.lexicalForm else null,
        lexicalExpansion = if (label.value == LEXICAL_CONTRACTION) word.lexicalExpansion else null,
        fromScoring = label in word.variantLabels,
      )
    }
    val noChange = when {
      word.changes.isNotEmpty() -> null
      word.noDictionaryForm -> NoChange.NO_DICTIONARY
      word.realizedIpa == word.dictIpa -> NoChange.SAME
      else -> NoChange.UNLABELED
    }
    val form = word.form?.let { FormScoringUi(it.ipa, it.strongIpa, it.weak, it.weakMargin, it.hDropped == true) }
    val onCards = word.variantLabels.isNotEmpty()
    val prominence = phrase.prominence?.getOrNull(ref.word)
    val wordClass = phrase.wordClass(ref.word)
    return WhyUi(
      reliability = reliability(word),
      cards = cards,
      noChange = noChange,
      formScoring = if (onCards && narrow) null else form,
      formScoringOnCards = onCards,
      prominence = prominence?.let { (it * PERCENT).roundToInt() },
      wordClass = wordClass,
      functionPeak = wordClass == WordClass.FUNCTION && prominence != null && prominence >= PEAK,
      divergence = if (word.noDictionaryForm) null else word.diffCost,
      oov = word.oov,
      alignmentFallback = word.alignmentFallback,
      attracted = word.attractedCount,
    )
  }

  private fun phonesOf(word: Word, stream: MouthStream): List<TimedPhone> =
    if (stream == MouthStream.SAID) word.realizedPhones else word.canonicalPhones

  /** Step 4 for [ref]: one button per sound of the word (and the next one across a boundary) in [stream]. */
  fun see(clip: ClipData, ref: WordRef, stream: MouthStream, held: Int?, hasAudio: Boolean, didactic: DidacticContent): SeeUi {
    val word = checkNotNull(clip.word(ref))
    val next = crossingNext(clip, ref)
    val phones = phonesOf(word, stream) + (next?.let { phonesOf(it, stream) }.orEmpty())
    val sounds = phones.mapIndexed { index, phone ->
      val articulation = didactic.phone(phone.symbol)
      MouthSoundUi(
        index = index,
        symbol = phone.symbol,
        name = articulation?.name,
        cue = articulation?.cue,
        example = articulation?.example,
        startMs = ms(phone.startS - SOUND_PAD_S),
        endMs = ms(phone.endS + SOUND_PAD_S),
        drawable = articulation != null,
      )
    }
    // The movement belongs to the phrase (coarticulation); a word linking into the next phrase brings its sounds.
    val phrase = clip.phrases[ref.segment]
    val track = phrase.words.flatMap { phonesOf(it, stream) } +
      (next?.takeIf { clip.nextWord(ref)?.segment != ref.segment }?.let { phonesOf(it, stream) }.orEmpty())
    return SeeUi(stream, sounds, held?.takeIf { it in sounds.indices }, hasAudio, track)
  }

  /** The index in [see]'s sounds of the sound [sort] stands for, and the row to show it in. */
  fun mouthTarget(sort: SortUi, see: (MouthStream) -> SeeUi): Pair<MouthStream, Int?> {
    val stream = if (sort.said != null) MouthStream.SAID else MouthStream.DICTIONARY
    val sounds = see(stream).sounds
    val symbol = if (stream == MouthStream.SAID) sort.said else sort.dictionary
    val index = sounds.firstOrNull { it.symbol == symbol && it.startMs <= sort.endMs && it.endMs >= sort.startMs }?.index
      ?: sounds.firstOrNull { it.symbol == symbol }?.index
    return stream to index
  }
}
