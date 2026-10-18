package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaColumn
import com.mapineda48.phonotrainer.core.domain.model.IpaGridRow
import com.mapineda48.phonotrainer.core.domain.model.IpaGroup
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowState

/**
 * Learn states for previews and screen tests, after the design's renders `12` and `13`: the texts follow the bundled
 * reference and Learn content; the clips and words are invented examples (no personal data).
 */
internal object LearnPreviewData {
  private val JOB = JobId("20261004-133519-a77c")
  private val OTHER_JOB = JobId("20261006-091200-b31e")

  val FLAPPING = ChangeLabel("flapping")
  val T_DELETION = ChangeLabel("t_deletion")
  val VOWEL_REDUCTION = ChangeLabel("vowel_reduction")
  val PALATALIZATION = ChangeLabel("palatalization")
  val LINKING = ChangeLabel("linking")
  val CONTRACTION = ChangeLabel("contraction_lex")
  val WORD_ELISION = ChangeLabel("word_elision")

  private val produce = PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Safe to produce and one of the most rewarding things to learn (§3A).")
  private val casual = PracticeAdvice(
    Advice.UNDERSTAND,
    Register.CASUAL,
    "Dropping the t/d of a cluster (next day, old man) belongs to casual speech; all dialects do it before a consonant (§3E, §5).",
  )

  val flapping = ChangeCardUi(FLAPPING, "Flapping", Family.TD, "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ", produce)
  val tDeletion = ChangeCardUi(T_DELETION, "t/d deletion", Family.TD, "A final /t/ or /d/ drops out of a consonant cluster. next day → nɛks deɪ", casual)
  private val vowelReduction = ChangeCardUi(
    VOWEL_REDUCTION, "Vowel reduction", Family.REDUCTION, "A full vowel reduces to schwa in an unstressed syllable. does → dəz",
    PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Unstressed vowels turning to schwa is the engine of English rhythm (§4, stage 2)."),
  )
  private val palatalization = ChangeCardUi(
    PALATALIZATION, "Palatalization", Family.ASSIMILATION, "t+j and d+j merge into tʃ/dʒ at the end of a word. got you → gotcha",
    PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "didja, gotcha, doncha are universal and safe to produce (§3D)."),
  )
  private val linking = ChangeCardUi(
    LINKING, "Linking", Family.BOUNDARY, "The word runs into the next vowel: a final consonant (does it → dʌ‿zɪt).",
    PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Linking is fundamental to fluency and safe to produce (§3G)."),
  )
  private val contraction = ChangeCardUi(
    CONTRACTION, "Lexical contraction", Family.LEXICAL, "A lexicalized reduced form. want to → wanna",
    PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "gonna, wanna, gotta are universal and safe (§1)."),
  )
  private val wordElision = ChangeCardUi(
    WORD_ELISION, "Elided word", Family.OTHER, "The word leaves no acoustic trace: fast speech swallows it.",
    PracticeAdvice(Advice.UNDERSTAND, Register.CASUAL, "A word swallowed by fast speech: something to hear, not to aim for."),
  )

  val sections: List<FamilySectionUi> = listOf(
    FamilySectionUi(Family.REDUCTION, "Reduction", listOf(vowelReduction)),
    FamilySectionUi(Family.TD, "t/d processes", listOf(flapping, tDeletion)),
    FamilySectionUi(Family.ASSIMILATION, "Assimilation", listOf(palatalization)),
    FamilySectionUi(Family.BOUNDARY, "Word boundary", listOf(linking)),
    FamilySectionUi(null, null, listOf(contraction, wordElision)),
  )

  /** Flapping: 12 eligible occurrences in 9 shorts (F-15-AC1); t/d deletion: none yet. */
  val counts = CountsUi.Ready(
    mapOf(
      FLAPPING to ChangeCount(shorts = 9, occurrences = 12),
      VOWEL_REDUCTION to ChangeCount(shorts = 34, occurrences = 80),
      PALATALIZATION to ChangeCount(shorts = 1, occurrences = 1),
    ),
  )

  fun learn(
    counts: CountsUi = this.counts,
    partial: Boolean = false,
    insights: InsightsEntryUi? = null,
  ): LearnUiState = LearnUiState(sections, counts, partial, insights)

  val flappingLesson = LessonContent(
    change = FLAPPING,
    listenFor = "“water” sounds like “wadder”, “get it” like “geddit”. Not before a stressed syllable: “attack” keeps a full t.",
    demo = null,
    spanishTip = "the flap is the single r of Spanish “pero”. You already have this sound.",
    note = "Only between vowels, and only when the next syllable is unstressed.",
  )

  val demo = MouthDemoUi(
    word = "water",
    dictionary = listOf("w", "ɔ", "t", "ɚ"),
    said = listOf("w", "ɔ", "ɾ", "ɚ"),
    from = SoundUi("t", "Voiceless alveolar stop", "The tip seals on the ridge, then lets the air out with a small burst."),
    to = SoundUi("ɾ", "Alveolar tap", "The tip bounces off the ridge once, too briefly to build up any pressure."),
  )

  private fun occurrence(
    job: JobId, segment: Int, word: Int, text: String, dict: String, said: String, startMs: Long, clip: String,
    crosses: Boolean = false, next: String? = null, family: Family = Family.TD, change: ChangeLabel = FLAPPING,
  ) = Occurrence(
    word = WordRef(job, segment, word),
    shortFirstSegment = segment,
    wordText = text,
    dictIpa = dict,
    realizedIpa = said,
    startMs = startMs,
    endMs = startMs + 320,
    change = change,
    diffCost = 0.1,
    advice = Advice.PRODUCE,
    family = family,
    crossesBoundary = crosses,
    nextWordText = next,
    clipTitle = clip,
  )

  val water = occurrence(JOB, 3, 4, "water?", "ˈwɔtɚ", "ˈwɔɾɚ", 45_000, "Two friends at the lake")
  val getIt = occurrence(OTHER_JOB, 7, 2, "get", "ɡɛt", "ɡɛɾ", 72_400, "Coffee shop small talk", crosses = true, next = "it")
  val better = occurrence(JOB, 9, 1, "better", "ˈbɛtɚ", "ˈbɛɾɚ", 98_100, "Two friends at the lake")

  fun examples(playing: Int? = null): ExamplesUi = ExamplesUi.Ready(
    listOf(water, getIt, better).mapIndexed { index, it -> OccurrenceRowState(it, playing = index == playing) },
  )

  fun phenomenon(
    counts: CountsUi = this.counts,
    partial: Boolean = false,
    examples: ExamplesUi = examples(),
    lesson: LessonContent? = flappingLesson,
    demo: MouthDemoUi? = this.demo,
  ): PhenomenonUiState.Ready = PhenomenonUiState.Ready(
    card = flapping,
    familyName = "t/d processes",
    lesson = lesson,
    demo = demo,
    counts = counts,
    partial = partial,
    examples = examples,
  )

  val glossary: Map<String, GlossaryEntry> = listOf(
    GlossaryEntry(
      "register", "Register",
      "Where a change belongs: universal (every speaker, every style), casual (relaxed speech) or marked (a region or group).",
      null, null, null,
    ),
    GlossaryEntry(
      "schwa", "Schwa",
      "The short, relaxed vowel [ə], as in the first sound of about. It is the most frequent vowel in spoken English: unstressed vowels turn into it.",
      "banana → bənænə", null, null,
    ),
    GlossaryEntry(
      "metric:deviate", "Words that differ from the dictionary",
      "Words where at least one sound was not the one the dictionary gives: changed, dropped or added.",
      "water → wɔɾɚ (the t became a flap)", LinkTarget.Learn, "See every change in Learn",
    ),
  ).associateBy { it.key }

  val terms = listOf(
    GlossaryHit("dictionary-form", "Dictionary form", "How a word is pronounced when it is said alone and carefully."),
    GlossaryHit("schwa", "Schwa", "The short, relaxed vowel [ə], as in the first sound of about."),
    GlossaryHit("weak-form", "Weak form", "Small grammar words (to, of, and, can, you…) have a strong form and a weak form."),
  )
  val measures = listOf(
    GlossaryHit("metric:deviate", "Words that differ from the dictionary", "Words where at least one sound was not the one the dictionary gives: changed, dropped or added."),
  )

  fun glossaryState(query: String = ""): GlossaryUiState {
    val needle = query.trim().lowercase()
    val match = { hit: GlossaryHit -> needle in "${hit.title}\n${hit.snippet}".lowercase() }
    return GlossaryUiState(query, terms.filter(match), measures.filter(match), total = terms.size + measures.size)
  }

  private val places = listOf(
    IpaColumn("bilabial", "Lips", "bilabial"),
    IpaColumn("alveolar", "Gum ridge", "alveolar"),
    IpaColumn("velar", "Soft palate", "velar"),
    IpaColumn("glottal", "Throat", "glottal"),
  )

  val layout = IpaLayout(
    places = places,
    consonantRows = listOf(
      IpaGridRow("Stop", "the air is stopped completely, then released", mapOf("bilabial" to listOf("p", "b"), "alveolar" to listOf("t", "d"), "velar" to listOf("k", "ɡ"))),
      IpaGridRow("Nasal", "the air goes out through the nose", mapOf("bilabial" to listOf("m"), "alveolar" to listOf("n"), "velar" to listOf("ŋ"))),
    ),
    allophonePlaces = places,
    allophoneRows = listOf(
      IpaGridRow("Tap", "one very quick touch of the tongue", mapOf("alveolar" to listOf("ɾ", "ɾ̃"))),
      IpaGridRow("Glottal stop", "the throat closes for an instant", mapOf("glottal" to listOf("ʔ"))),
    ),
    syllabics = listOf("n̩", "l̩"),
    vowelColumns = listOf(IpaColumn("front", "Front", ""), IpaColumn("central", "Central", ""), IpaColumn("back", "Back", "")),
    vowelRows = listOf(
      IpaGridRow("Close", "tongue high, mouth almost closed", mapOf("front" to listOf("i"), "back" to listOf("u"))),
      IpaGridRow("Mid", "the relaxed middle of the mouth", mapOf("central" to listOf("ə", "ɚ"))),
    ),
    reducedVowels = listOf("ə", "ɚ"),
    glidingGroups = listOf(IpaGroup("Diphthongs", "the tongue glides from one vowel to another", listOf("eɪ", "aɪ", "oʊ"))),
    placed = listOf("p", "b", "t", "d", "k", "ɡ", "m", "n", "ŋ", "ɾ", "ɾ̃", "ʔ", "n̩", "l̩", "i", "u", "ə", "ɚ", "eɪ", "aɪ", "oʊ"),
    unplaced = emptyList(),
  )

  private val names = mapOf(
    "p" to "voiceless bilabial stop", "b" to "voiced bilabial stop", "t" to "voiceless alveolar stop",
    "d" to "voiced alveolar stop", "k" to "voiceless velar stop", "ɡ" to "voiced velar stop", "m" to "bilabial nasal",
    "n" to "alveolar nasal", "ŋ" to "velar nasal", "ɾ" to "alveolar tap", "ɾ̃" to "nasal alveolar tap", "ʔ" to "glottal stop",
    "n̩" to "syllabic n", "l̩" to "syllabic l", "i" to "close front unrounded vowel", "u" to "close back rounded vowel",
    "ə" to "mid central vowel (schwa)", "ɚ" to "r-coloured schwa", "eɪ" to "diphthong", "aɪ" to "diphthong", "oʊ" to "diphthong",
  )

  val tapDetail = SymbolDetailUi(
    symbol = "ɾ",
    name = "Alveolar tap",
    cue = null,
    body = "The tip bounces off the ridge once, too briefly to build up any pressure. This is what American /t/ and /d/ " +
      "become between vowels. You hear it in “water”, “better” and “get it”.",
    asIn = "the -tt- of “butter”",
    change = ChangeLinkUi(FLAPPING, "flapping"),
    examples = examples(),
  )

  fun ipa(selected: String? = null, detail: SymbolDetailUi? = null): IpaChartUiState =
    IpaChartUiState(layout = layout, weak = setOf("ə", "ɚ"), names = names, selected = selected, detail = detail)
}
