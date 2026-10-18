package com.mapineda48.phonotrainer.feature.watch

import com.mapineda48.phonotrainer.core.designsystem.trace.TraceSample
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * The feed's pure rules (no Android, no coroutines), so every one is a JVM test: what a short card shows from its clip
 * data (spec F-07 items 3 and 4, design 9.3 and 9.4), the word being said (the web's 50 ms / 0.35 s rule, architecture
 * 11.3), the rail's cycles (F-07 item 6), where "In order" starts (5.2, F-06-AC10) and the set summary (F-08 item 6).
 */
internal object FeedLogic {
  /** A word is filled from 50 ms before its start to 0.35 s after its end (web rule, spec F-07 item 4). */
  const val FILL_BEFORE_MS = 50L
  const val FILL_AFTER_MS = 350L

  /** "Speed" cycles 1× → 0.75× → 0.5× (F-07 item 6). */
  val SPEEDS: List<Float> = listOf(1f, 0.75f, 0.5f)

  /** The slow speed of "Slowly", "Hold to slow down" and the questions' "Slowly". */
  const val SLOW: Float = 0.5f

  private const val CONTRACTION = "contraction_lex"
  private const val TRACE_POINTS = 64
  private const val SEMITONE_RANGE = 12f

  fun nextSpeed(current: Float): Float {
    val index = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEEDS[(if (index < 0) 0 else index + 1) % SPEEDS.size]
  }

  fun nextLoop(current: LoopMode): LoopMode = when (current) {
    LoopMode.OFF -> LoopMode.PHRASE
    LoopMode.PHRASE -> LoopMode.SHORT
    LoopMode.SHORT -> LoopMode.OFF
  }

  /**
   * The index (in [phrase]'s words) of the word being said at [positionMs]: among the words whose window (start − 50 ms
   * to end + 0.35 s) holds the position, the one that started last; null when none does.
   */
  fun playingWord(phrase: CaptionPhrase, positionMs: Long): Int? {
    var found: Int? = null
    phrase.words.forEachIndexed { index, word ->
      if (positionMs >= word.startMs - FILL_BEFORE_MS && positionMs <= word.endMs + FILL_AFTER_MS) found = index
    }
    return found
  }

  /**
   * Learn's "How to hear it" shortened for the card (F-07 item 3): its first clause, up to the first colon or the end
   * of its first sentence, ending with a full stop.
   */
  fun shortCue(listenFor: String?): String? {
    val text = listenFor?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val colon = text.indexOf(": ").takeIf { it > 0 }
    val stop = Regex("""[.!?](\s|$)""").find(text)?.range?.first
    val cut = listOfNotNull(colon, stop).minOrNull() ?: return text
    val head = text.substring(0, cut).trimEnd()
    return if (head.endsWith('.') || head.endsWith('!') || head.endsWith('?')) head else "$head."
  }

  /** The reference's name of [label] ("flapping"), or the label with spaces when the reference has none. */
  fun changeName(reference: Reference, label: ChangeLabel): String =
    reference.labels[label] ?: label.value.replace('_', ' ')

  /** The advice of [word]'s first change: a contraction takes its form's own advice (`lexical_practice`). */
  private fun adviceOf(reference: Reference, word: Word, label: ChangeLabel): PracticeAdvice? =
    if (label.value == CONTRACTION) {
      word.lexicalForm?.let { reference.lexicalPractice[it.lowercase()] } ?: reference.practice[label]
    } else {
      reference.practice[label]
    }

  /** "gonna ← going to" for a contraction (design 9.4): the reduced form and its full form, when they differ. */
  fun contractionOf(word: Word): String? {
    if (word.changes.none { it.value == CONTRACTION }) return null
    val reduced = word.lexicalForm ?: word.text
    val full = word.lexicalExpansion ?: word.text.takeIf { !it.equals(reduced, ignoreCase = true) } ?: return null
    if (full.equals(reduced, ignoreCase = true)) return null
    return "$reduced ← $full"
  }

  /**
   * Everything a card shows for [short] from its [clip] data: the phrases of the short with their words and marks,
   * the spotlight ([spotlight] when the mode forces one, F-07 rules 1 and 2, else the short's default), the trace's
   * ticks and pin, and the pitch for the drum. [cueOf] gives Learn's "How to hear it" of a change.
   */
  fun content(
    short: ShortInfo,
    spotlight: Spotlight?,
    clip: ClipData,
    reference: Reference,
    clipTitle: String?,
    cueOf: (ChangeLabel) -> String?,
  ): ShortContent {
    val spot = spotlight ?: short.spotlight
    val start = short.playStartMs
    val end = short.playEndMs.coerceAtLeast(start + 1)
    val length = (end - start).toFloat()
    val inShort = clip.phrases.filter { it.index in short.id.firstSegment..short.lastSegment }
    val phrases = inShort.map { phrase -> captionPhrase(clip.jobId, phrase, reference, spot) }
    val slip = spot?.let { slipOf(it, clip, reference, cueOf) }
    val median = medianHz(clip)
    val pitch = inShort.map { pitchOf(it, median) }
    return ShortContent(
      id = short.id,
      clipTitle = clipTitle,
      ordinal = short.ordinal,
      ofTotal = short.ofTotal,
      playStartMs = start,
      playEndMs = end,
      phrases = phrases,
      spotlight = slip,
      ticks = phrases.drop(1).map { ((it.startMs - start) / length).coerceIn(0f, 1f) },
      pin = slip?.let { ((it.startMs - start) / length).coerceIn(0f, 1f) },
      trace = traceOf(pitch, start, end),
      pitch = pitch,
    )
  }

  private fun captionPhrase(jobId: JobId, phrase: Phrase, reference: Reference, spot: Spotlight?): CaptionPhrase {
    val words = phrase.words.map { word ->
      val first = word.changes.firstOrNull()
      val family = first?.let { reference.familyFor(it) }
      CaptionWord(
        ref = WordRef(jobId, phrase.index, word.index),
        text = word.text,
        startMs = seconds(word.startS),
        endMs = seconds(word.endS),
        family = family,
        changes = word.changes.map { changeName(reference, it) },
        advice = first?.let { adviceOf(reference, word, it)?.advice },
        lowConfidence = word.lowConfidence || word.nothingHeard,
        stressed = phrase.emphasisWord == word.index,
        linksToNext = word.boundaryLinkNext,
        dictIpa = word.dictIpa,
        realizedIpa = word.realizedIpa,
        contraction = contractionOf(word),
        spotlit = spot != null && spot.word.segment == phrase.index && spot.word.word == word.index,
      )
    }
    val unit = phrase.intonationUnits.lastOrNull { it.judged }
    return CaptionPhrase(
      segment = phrase.index,
      startMs = seconds(phrase.startS),
      endMs = seconds(phrase.endS),
      words = words,
      badge = unit?.let { IntonationBadge(it.unitType, it.contour, it.expected.takeIf { _ -> it.matchesExpected == false }, it.uptalk == true) },
    )
  }

  private fun slipOf(spot: Spotlight, clip: ClipData, reference: Reference, cueOf: (ChangeLabel) -> String?): SpotlightSlip? {
    val word = clip.word(spot.word) ?: return null
    val next = if (spot.crossesBoundary) clip.nextWord(spot.word)?.let { ref -> clip.word(ref)?.let { ref to it } } else null
    val advice = adviceOf(reference, word, spot.change)
    val startMs = seconds(word.startS)
    val endMs = seconds((next?.second ?: word).endS)
    return SpotlightSlip(
      word = spot.word,
      wordText = word.text,
      nextWordText = next?.second?.text,
      label = spot.change,
      changeName = changeName(reference, spot.change),
      family = reference.familyFor(spot.change),
      advice = advice?.advice ?: spot.advice,
      register = advice?.register,
      dictIpa = listOfNotNull(word.dictIpa, next?.second?.dictIpa).joinToString(" ").ifEmpty { null },
      realizedIpa = listOfNotNull(word.realizedIpa, next?.second?.realizedIpa).joinToString(" ").ifEmpty { null },
      cue = shortCue(cueOf(spot.change)),
      contraction = contractionOf(word),
      startMs = startMs,
      endMs = endMs,
      span = ClipSpan(clip.jobId, startMs, endMs, spot.word),
    )
  }

  /** The median pitch of the clip's voiced samples (Hz), the zero of the drum's semitone scale. */
  fun medianHz(clip: ClipData): Float {
    val values = clip.phrases.flatMap { phrase -> phrase.f0?.hz?.filter { it > 0f }.orEmpty() }.sorted()
    return if (values.isEmpty()) 0f else values[values.size / 2]
  }

  fun pitchOf(phrase: Phrase, medianHz: Float): PhrasePitch {
    val track = phrase.f0
    if (track == null || medianHz <= 0f) return PhrasePitch(phrase.index, emptyList(), emptyList())
    val times = ArrayList<Long>(track.size)
    val semis = ArrayList<Float>(track.size)
    for (i in 0 until track.size) {
      val hz = track.hz[i]
      if (hz <= 0f) continue
      times += (track.timesS[i] * 1000.0).roundToLong()
      semis += (12.0 * ln(hz / medianHz.toDouble()) / ln(2.0)).toFloat()
    }
    return PhrasePitch(phrase.index, times, semis)
  }

  /** The progress trace: the short's pitch across its play bounds, broken between phrases (design 9.7). */
  fun traceOf(pitch: List<PhrasePitch>, startMs: Long, endMs: Long): List<TraceSample> {
    if (pitch.none { it.measurable }) return emptyList()
    val length = (endMs - startMs).toFloat()
    return List(TRACE_POINTS) { i ->
      val x = i / (TRACE_POINTS - 1).toFloat()
      val t = startMs + (x * length).toLong()
      val y = pitch.firstNotNullOfOrNull { p -> semitoneAt(p, t) }?.let { 0.5f - it.coerceIn(-SEMITONE_RANGE, SEMITONE_RANGE) / (2 * SEMITONE_RANGE) }
      TraceSample(x, y, weight = 0.5f)
    }
  }

  /** The semitone value of [pitch] nearest to [timeMs], or null outside its samples. */
  fun semitoneAt(pitch: PhrasePitch, timeMs: Long): Float? {
    if (!pitch.measurable || timeMs < pitch.timesMs.first() || timeMs > pitch.timesMs.last()) return null
    var lo = 0
    var hi = pitch.timesMs.lastIndex
    while (lo < hi) {
      val mid = (lo + hi) / 2
      if (pitch.timesMs[mid] < timeMs) lo = mid + 1 else hi = mid
    }
    return pitch.semitones[lo]
  }

  /** The page of [items] holding [segment]: the short whose phrases contain it, else the last one starting before it. */
  fun indexContaining(items: List<ShortInfo>, segment: Int): Int {
    val exact = items.indexOfFirst { segment in it.id.firstSegment..it.lastSegment }
    if (exact >= 0) return exact
    return items.indexOfLast { it.id.firstSegment <= segment }.coerceAtLeast(0)
  }

  /**
   * Where "In order" starts (spec 5.2, F-06-AC10): at [firstSegment]'s short when one is named (Clip page, a word
   * target); at short 1 for "Watch from the start"; else where the learner left the clip ([resume]); else short 1.
   */
  fun inOrderStart(items: List<ShortInfo>, firstSegment: Int?, fromStart: Boolean, resume: ShortId?): Int = when {
    items.isEmpty() -> 0
    firstSegment != null -> indexContaining(items, firstSegment)
    fromStart -> 0
    resume != null -> items.indexOfFirst { it.id == resume }.takeIf { it >= 0 } ?: indexContaining(items, resume.firstSegment)
    else -> 0
  }

  /** The set summary of [set] (F-08 item 6): its shorts (dropped ones left out), questions, right answers and the
   *  changes met, most met first. */
  fun setSummary(set: StudySet, reference: Reference, questionsOn: Boolean): SetSummary {
    val shorts = set.items.filterIsInstance<StudySetItem.Short>().filterNot { it.dropped }
    val questions = set.items.filterIsInstance<StudySetItem.QuestionItem>()
    val counts = LinkedHashMap<ChangeLabel, Int>()
    shorts.mapNotNull { (it.spotlight ?: it.short.spotlight)?.change }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    val met = counts.entries
      .sortedByDescending { it.value }
      .map { (label, count) -> MetChange(label, changeName(reference, label), reference.familyFor(label), count) }
    val right = if (questionsOn) questions.count { it.correct == true }.coerceAtLeast(set.right) else 0
    return SetSummary(
      today = set.kind == StudySetKind.TODAY,
      shorts = shorts.size,
      questions = if (questionsOn) questions.size else 0,
      right = right,
      met = met,
      noQuestions = questionsOn && questions.isEmpty(),
    )
  }

  /** The number of distinct changes heard in [clip] (the "End of {clip}" card: "with 14 kinds of change"). */
  fun kindsOfChange(clip: ClipData): Int = clip.summary.changeCounts.count { it.value > 0 }

  private fun seconds(s: Double): Long = (s * 1000.0).roundToLong()
}
