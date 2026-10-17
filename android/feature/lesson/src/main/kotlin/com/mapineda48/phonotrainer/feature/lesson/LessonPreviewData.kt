package com.mapineda48.phonotrainer.feature.lesson

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.LinkType
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.WordClass
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget

/**
 * Lesson states for previews and screen tests, after the design's renders: “water” with flapping (render `10`) and
 * “did” + “you” across a boundary (render `11`), plus every partial state of spec F-10. The words are invented
 * examples; the advice and descriptions follow the bundled reference.
 */
internal object LessonPreviewData {
  val JOB = JobId("20261004-133519-a77c")
  val WATER = WordRef(JOB, 3, 4)
  val DID = WordRef(JOB, 8, 2)

  private val produceFlapping = PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Safe to produce and one of the most rewarding things to learn (§3A).")
  private val producePalatalization = PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "didja, gotcha, doncha are universal and safe to produce (§3D).")
  private val recognizeTd = PracticeAdvice(
    Advice.UNDERSTAND,
    Register.CASUAL,
    "Dropping the t/d of a cluster (next day, old man) belongs to casual speech; all dialects do it before a consonant (§3E, §5).",
  )

  val flapping = ChangeUi(ChangeLabel("flapping"), "Flapping", Family.TD, produceFlapping)
  val palatalization = ChangeUi(ChangeLabel("palatalization"), "Palatalization", Family.ASSIMILATION, producePalatalization)
  val tDeletion = ChangeUi(ChangeLabel("t_deletion"), "t/d deletion", Family.TD, recognizeTd)

  /** The glossary the previews answer "?" with. */
  val glossary: Map<String, GlossaryEntry> = listOf(
    GlossaryEntry("what-was-said", "What was said", "The sounds the recognizer heard, in square brackets: [ˈwɔɾɚ].", "water → [ˈwɔɾɚ]", LinkTarget.Ipa(null), "Open the IPA chart"),
    GlossaryEntry("dictionary-form", "Dictionary form", "How the dictionary spells the word in sounds, between slashes: /ˈwɔtɚ/.", "water → /ˈwɔtɚ/", null, null),
    GlossaryEntry("ipa:ɾ", "[ɾ] alveolar tap", "A quick tap of the tongue tip on the gum ridge: the American t in water.", "water → [ˈwɔɾɚ]", LinkTarget.Ipa("ɾ"), null),
    GlossaryEntry("low-confidence", "Low confidence", "The recognizer was unsure over this stretch.", null, null, null),
    GlossaryEntry("prominence", "Prominence", "How much a word stands out in its phrase.", null, null, null),
    GlossaryEntry("divergence", "Divergence", "How far what was said is from the dictionary form; 0 is identical.", null, null, null),
  ).associateBy { it.key }

  private fun sort(index: Int, dictionary: String?, said: String?, status: CompareStatus, next: Boolean = false) =
    SortUi(index, dictionary, said, status, next, 44_800L + index * 90L, 44_880L + index * 90L)

  private val waterSorts = listOf(
    sort(0, "w", "w", CompareStatus.SAME),
    sort(1, "ɔ", "ɔ", CompareStatus.SAME),
    sort(2, "t", "ɾ", CompareStatus.CHANGED),
    sort(3, "ɚ", "ɚ", CompareStatus.SAME),
  )

  private val didSorts = listOf(
    sort(0, "d", "d", CompareStatus.SAME),
    sort(1, "ɪ", "ɪ", CompareStatus.SAME),
    sort(2, "d", "dʒ", CompareStatus.CHANGED),
    sort(3, "j", null, CompareStatus.NOT_HEARD, next = true),
    sort(4, "uː", "ə", CompareStatus.CHANGED, next = true),
  )

  private val tapCard = SoundUi(
    sort = waterSorts[2],
    symbol = "ɾ",
    name = "voiced alveolar tap",
    example = "water",
    cue = "The tongue tip flicks once against the gum ridge.",
    explainKey = "ipa:ɾ",
  )

  private fun sounds(symbols: List<Pair<String, String?>>) = symbols.mapIndexed { index, (symbol, name) ->
    MouthSoundUi(
      index = index,
      symbol = symbol,
      name = name,
      cue = name?.let { "What the mouth does for [$symbol]." },
      example = name?.let { "water" },
      startMs = 44_800L + index * 90L,
      endMs = 44_880L + index * 90L,
      drawable = name != null,
    )
  }

  private val waterSpan = ClipSpan(JOB, 43_900, 46_200, null)

  /** “water”, flapping, step 2 with the changed sort selected (render `10`). */
  fun water(
    open: Set<LessonStep> = setOf(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.WHY, LessonStep.SEE),
    selected: Boolean = true,
    presentation: LessonPresentation = LessonPresentation.SHEET,
    saved: Boolean = false,
    hasAudio: Boolean = true,
    shadowing: ShadowingState = ShadowingState.Idle,
    at: LessonStep? = null,
  ): LessonUiState.Ready = LessonUiState.Ready(
    presentation = presentation,
    header = HeaderUi(
      ref = WATER, text = "water", nextText = null, position = 12, total = 431, phrase = 4, startS = 44.8, endS = 45.2,
      changes = listOf(flapping), advice = produceFlapping, reliability = Reliability.OK, canPrevious = true,
      canNext = true, canNextChange = true, saved = saved,
    ),
    open = open,
    at = at,
    reveal = 0,
    listen = ListenUi(hasAudio, nextText = null, played = 2, speed = 1f, loop = false, track = Track.MIX, dialogue = true, playing = null),
    compare = CompareUi(
      dictionary = "ˈwɔtɚ", said = "ˈwɔɾɚ", aligner = null, narrow = true, spoken = null, noDictionaryForm = false,
      sorts = waterSorts, wordText = "water", nextText = null, gap = null, selected = if (selected) tapCard else null,
    ),
    why = WhyUi(
      reliability = Reliability.OK,
      cards = listOf(ChangeCardUi(flapping, "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ", null, null, null, false)),
      noChange = null, formScoring = null, formScoringOnCards = false, prominence = 100, wordClass = WordClass.CONTENT,
      functionPeak = false, divergence = 0.1, oov = false, alignmentFallback = false, attracted = 0,
    ),
    see = SeeUi(
      stream = MouthStream.SAID,
      sounds = sounds(listOf("w" to "voiced labial-velar approximant", "ɔ" to "open-mid back rounded vowel", "ɾ" to "voiced alveolar tap", "ɚ" to null)),
      held = null,
      hasAudio = hasAudio,
      track = listOf(TimedPhone("w", 44.8, 44.88), TimedPhone("ɔ", 44.89, 44.97), TimedPhone("ɾ", 44.98, 45.06)),
    ),
    practice = PracticeUi(
      target = ShadowingTarget.PHRASE, wordAndNext = true, slowSpeed = 0.75f, hasAudio = hasAudio, recognizeOnly = false,
      shadowing = shadowing, span = waterSpan, recordTarget = RecordTarget(WATER, waterSpan, phrase = true),
    ),
    paused = if (presentation == LessonPresentation.SHEET) PausedUi("Two friends at the lake") else null,
  )

  /** “did” + “you”: palatalization across the boundary, the dropped [j], the gap box, shadowing running (render `11`). */
  fun didYou(
    open: Set<LessonStep> = setOf(LessonStep.COMPARE, LessonStep.WHY, LessonStep.PRACTICE),
    shadowing: ShadowingState = ShadowingState.Running(round = 2, of = 4, phase = ShadowingPhase.YOUR_TURN, speed = 0.75f),
  ): LessonUiState.Ready {
    val span = ClipSpan(JOB, 78_380, 78_760, DID)
    return LessonUiState.Ready(
      presentation = LessonPresentation.FULL,
      header = HeaderUi(
        ref = DID, text = "did", nextText = "you", position = 37, total = 431, phrase = 9, startS = 78.4, endS = 78.7,
        changes = listOf(palatalization), advice = producePalatalization, reliability = Reliability.OK,
        canPrevious = true, canNext = true, canNextChange = true, saved = true,
      ),
      open = open,
      at = null,
      reveal = 0,
      listen = ListenUi(true, nextText = "you", played = 0, speed = 0.75f, loop = true, track = Track.DIALOGUE, dialogue = true, playing = null),
      compare = CompareUi(
        dictionary = "dɪd ju", said = "ˈdɪdʒ‿ə", aligner = null, narrow = true, spoken = null, noDictionaryForm = false,
        sorts = didSorts, wordText = "did", nextText = "you", gap = GapUi(12, Family.ASSIMILATION),
        selected = SoundUi(didSorts[3], "j", "voiced palatal approximant", "yes", "The tongue body rises toward the hard palate.", null),
      ),
      why = WhyUi(
        reliability = Reliability.OK,
        cards = listOf(
          ChangeCardUi(
            palatalization,
            "A final t, d, s or z melts into a following “you”: “did you” → “didja”. /dɪd ju/ → [ˈdɪdʒə]",
            null, null, null, false,
          ),
        ),
        noChange = null, formScoring = null, formScoringOnCards = false, prominence = 30, wordClass = WordClass.CONTENT,
        functionPeak = false, divergence = 0.3, oov = false, alignmentFallback = false, attracted = 0,
      ),
      see = SeeUi(MouthStream.SAID, sounds(listOf("d" to "voiced alveolar stop", "ɪ" to "near-close front vowel", "dʒ" to null)), null, true, emptyList()),
      practice = PracticeUi(
        target = ShadowingTarget.WORD, wordAndNext = true, slowSpeed = 0.75f, hasAudio = true, recognizeOnly = false,
        shadowing = shadowing, span = span, recordTarget = RecordTarget(DID, span, phrase = false),
      ),
      paused = null,
    )
  }

  /** A word the recognizer was unsure about, with a "Recognize only" change, a linking card and the footer facts. */
  fun lowConfidence(): LessonUiState.Ready {
    val base = water(open = setOf(LessonStep.WHY, LessonStep.PRACTICE), selected = false)
    val linking = ChangeUi(ChangeLabel("linking"), "Linking", Family.BOUNDARY, PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Linking is fundamental to fluency."))
    return base.copy(
      header = base.header.copy(text = "next", changes = listOf(tDeletion, linking), advice = recognizeTd, reliability = Reliability.LOW_CONFIDENCE),
      why = base.why.copy(
        reliability = Reliability.LOW_CONFIDENCE,
        cards = listOf(
          ChangeCardUi(tDeletion, "A final /t/ or /d/ drops out of a consonant cluster. next day → nɛks deɪ", null, null, null, false),
          ChangeCardUi(linking, "The word runs into the next vowel.", LinkType.CONSONANT, null, null, false),
        ),
        prominence = 100, wordClass = WordClass.FUNCTION, functionPeak = true, oov = true, alignmentFallback = true, attracted = 2,
      ),
      practice = base.practice.copy(recognizeOnly = true),
    )
  }

  /** Nothing was heard for the word: the header badge, the step 3 notice, a dropped sort for every expected sound. */
  fun nothingHeard(): LessonUiState.Ready {
    val base = water(open = setOf(LessonStep.COMPARE, LessonStep.WHY), selected = false)
    return base.copy(
      header = base.header.copy(reliability = Reliability.NOTHING_HEARD, changes = emptyList(), advice = null),
      compare = base.compare.copy(
        said = null,
        sorts = listOf(sort(0, "w", null, CompareStatus.NOT_HEARD), sort(1, "ɔ", null, CompareStatus.NOT_HEARD)),
        selected = null,
      ),
      why = base.why.copy(reliability = Reliability.NOTHING_HEARD, cards = emptyList(), noChange = NoChange.UNLABELED),
    )
  }

  /** A numeral the dictionary cannot spell out: the notice instead of the sorts, and no change can be labeled. */
  fun noDictionaryForm(): LessonUiState.Ready {
    val base = water(open = setOf(LessonStep.COMPARE, LessonStep.WHY), selected = false)
    return base.copy(
      header = base.header.copy(text = "%", changes = emptyList(), advice = null),
      compare = base.compare.copy(dictionary = null, said = "pɚsɛnt", sorts = emptyList(), noDictionaryForm = true, wordText = "%", selected = null, spoken = "percent"),
      why = base.why.copy(cards = emptyList(), noChange = NoChange.NO_DICTIONARY, divergence = null),
    )
  }

  /** A clip imported without its audio: steps 1 and 5 disabled with the reason, 2 to 4 readable. */
  fun noAudio(): LessonUiState.Ready =
    water(open = setOf(LessonStep.LISTEN, LessonStep.COMPARE, LessonStep.PRACTICE), hasAudio = false).let {
      it.copy(listen = it.listen.copy(played = 0, dialogue = false))
    }

  /** An older analysis made with espeak: the "Expected by the aligner" row and form scoring on the card. */
  fun espeak(): LessonUiState.Ready {
    val base = water(open = setOf(LessonStep.COMPARE, LessonStep.WHY), selected = false)
    val reduction = ChangeUi(ChangeLabel("vowel_reduction"), "Vowel reduction", Family.REDUCTION, PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Unstressed vowels turning to schwa is the engine of English rhythm."))
    return base.copy(
      header = base.header.copy(text = "to", changes = listOf(reduction), advice = reduction.advice),
      compare = base.compare.copy(dictionary = "tˈuː", said = "tə", aligner = "tə", narrow = false, sorts = listOf(sort(0, "t", "t", CompareStatus.SAME), sort(1, "ə", "ə", CompareStatus.SAME))),
      why = base.why.copy(
        cards = listOf(ChangeCardUi(reduction, "A full vowel reduces to schwa in an unstressed syllable. does → dəz", null, null, null, fromScoring = true)),
        formScoring = FormScoringUi("tə", "tuː", weak = true, weakMargin = 1.4, hDropped = false),
        formScoringOnCards = true,
      ),
    )
  }

  /** A lexical contraction (“gonna” ← “going to”) said with an added sound. */
  fun contraction(): LessonUiState.Ready {
    val base = water(open = setOf(LessonStep.COMPARE, LessonStep.WHY), selected = false)
    val lexical = ChangeUi(ChangeLabel("contraction_lex"), "Lexical contraction", Family.LEXICAL, PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "gonna is universal and safe."))
    return base.copy(
      header = base.header.copy(text = "gonna", changes = listOf(lexical), advice = lexical.advice),
      compare = base.compare.copy(
        dictionary = "ɡʌnə", said = "ɡʌnəʔ",
        sorts = listOf(sort(0, "ɡ", "ɡ", CompareStatus.SAME), sort(1, "ʌ", "ʌ", CompareStatus.SAME), sort(2, null, "ʔ", CompareStatus.ADDED)),
        selected = SoundUi(sort(2, null, "ʔ", CompareStatus.ADDED), "ʔ", null, null, null, null),
      ),
      why = base.why.copy(cards = listOf(ChangeCardUi(lexical, "A lexicalized reduced form. want to → wanna", null, "gonna", "going to", false))),
    )
  }
}
