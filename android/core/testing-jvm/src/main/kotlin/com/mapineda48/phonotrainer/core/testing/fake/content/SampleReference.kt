package com.mapineda48.phonotrainer.core.testing.fake.content

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FamilyInfo
import com.mapineda48.phonotrainer.core.domain.model.MetricReference
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Register

/**
 * A reference snapshot for tests and previews, transcribed from the bundled `reference.json` (API 6): all sixteen
 * labels with their names, descriptions and practice advice, the four families, two lexical forms, every IPA token,
 * two metric references and every metric label. `:core:content`'s `ContentFakesTest` keeps its facts (labels,
 * families, badges and registers, IPA tokens, link types, metric labels) equal to the bundled snapshot; the
 * descriptions and the reasons are samples and may lag behind it.
 */
object SampleReference {
  const val API_VERSION = 6

  /** The sixteen labels, in the server's order. */
  val labels: Map<ChangeLabel, String> = linkedMapOf(
    ChangeLabel("vowel_reduction") to "vowel reduction",
    ChangeLabel("monophthongization") to "monophthongization",
    ChangeLabel("elision_syllable") to "elided syllable",
    ChangeLabel("word_elision") to "elided word",
    ChangeLabel("flapping") to "flapping",
    ChangeLabel("t_deletion") to "t/d deletion",
    ChangeLabel("t_unreleased") to "unreleased t/d",
    ChangeLabel("glottalization") to "glottalization",
    ChangeLabel("th_stopping") to "th-stopping",
    ChangeLabel("palatalization") to "palatalization",
    ChangeLabel("linking") to "linking",
    ChangeLabel("h_dropping") to "h-dropping",
    ChangeLabel("contraction_lex") to "lexical contraction",
    ChangeLabel("place_assimilation") to "place assimilation",
    ChangeLabel("nt_reduction") to "nt → n",
    ChangeLabel("function_elision") to "function-word elision",
  )

  /** The four families, in the server's order. */
  val families: List<FamilyInfo> = listOf(
    FamilyInfo(
      Family.REDUCTION,
      "Reduction",
      listOf(
        ChangeLabel("vowel_reduction"),
        ChangeLabel("monophthongization"),
        ChangeLabel("elision_syllable"),
        ChangeLabel("function_elision"),
      ),
      listOf("vowel reduction", "monophthongization", "elided syllable", "function-word elision"),
    ),
    FamilyInfo(
      Family.TD,
      "t/d processes",
      listOf(
        ChangeLabel("flapping"),
        ChangeLabel("t_deletion"),
        ChangeLabel("t_unreleased"),
        ChangeLabel("glottalization"),
        ChangeLabel("nt_reduction"),
      ),
      listOf("flapping", "t/d deletion", "unreleased t/d", "glottalization", "nt → n"),
    ),
    FamilyInfo(
      Family.ASSIMILATION,
      "Assimilation",
      listOf(
        ChangeLabel("th_stopping"),
        ChangeLabel("palatalization"),
        ChangeLabel("place_assimilation"),
      ),
      listOf("th-stopping", "palatalization", "place assimilation"),
    ),
    FamilyInfo(
      Family.BOUNDARY,
      "Word boundary",
      listOf(
        ChangeLabel("linking"),
        ChangeLabel("h_dropping"),
      ),
      listOf("linking", "h-dropping"),
    ),
  )

  val descriptions: Map<ChangeLabel, String> = linkedMapOf(
    ChangeLabel("vowel_reduction") to
      "A full vowel reduces to schwa in an unstressed syllable. does → dəz",
    ChangeLabel("monophthongization") to
      "A diphthong is realized as a plain vowel. my → ma",
    ChangeLabel("elision_syllable") to
      "A whole syllable is lost. probably → pɹɑbli, camera → kæmɹə",
    ChangeLabel("word_elision") to
      "The word leaves no acoustic trace: fast speech swallows it.",
    ChangeLabel("flapping") to
      "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ",
    ChangeLabel("t_deletion") to
      "A final /t/ or /d/ drops out of a consonant cluster. next day → nɛks deɪ",
    ChangeLabel("t_unreleased") to
      "A final /t/ or /d/ closes but is never released: an unreleased [t̚]. (The espeak engine cannot hear the " +
      "closure and infers it from a /t/ missing after a vowel.) that → ðæt̚",
    ChangeLabel("glottalization") to
      "/t/ closes into a glottal stop. button → bʌʔn̩",
    ChangeLabel("th_stopping") to
      "θ and ð are pronounced as the stops t/d. that → dat",
    ChangeLabel("palatalization") to
      "t+j and d+j merge into tʃ/dʒ at the end of a word. got you → gotcha",
    ChangeLabel("linking") to
      "The word runs into the next vowel: a final consonant (does it → dʌ‿zɪt), an r (far away → fɑɹ‿əweɪ) or a " +
      "w/j glide (go on → ɡoʊ‿wɑn).",
    ChangeLabel("h_dropping") to
      "The unstressed h disappears. tell him → tell im",
    ChangeLabel("contraction_lex") to
      "A lexicalized reduced form. want to → wanna",
    ChangeLabel("place_assimilation") to
      "A final n/t/d takes the place of the next consonant. ten bucks → tɛm bʌks, in case → ɪŋ keɪs",
    ChangeLabel("nt_reduction") to
      "/nt/ before a vowel loses its /t/: a plain n or a nasal flap. twenty → twɛni, winter → wɪɾ̃ɚ",
    ChangeLabel("function_elision") to
      "A function word loses a consonant as well as its vowel. of → ə, them → əm",
  )

  val practice: Map<ChangeLabel, PracticeAdvice> = linkedMapOf(
    ChangeLabel("vowel_reduction") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "Unstressed vowels turning to schwa is the engine of English rhythm, and the first thing the report says " +
      "to produce (§4, stage 2).",
    ),
    ChangeLabel("monophthongization") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.MARKED,
      "Diphthongs flattened into one vowel (Southern /aɪ/ → [aː]) are regional (§5): recognize them, don't " +
      "imitate them.",
    ),
    ChangeLabel("elision_syllable") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.CASUAL,
      "Schwa deletion (famly, prob'ly, camra) is very common but casual: understanding it comes first (§3E, §5).",
    ),
    ChangeLabel("word_elision") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.CASUAL,
      "A word swallowed by fast speech: something to hear, not to aim for.",
    ),
    ChangeLabel("flapping") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "Safe to produce and one of the most rewarding things to learn (§3A).",
    ),
    ChangeLabel("t_deletion") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.CASUAL,
      "Dropping the t/d of a cluster (next day, old man) belongs to casual speech; all dialects do it before a " +
      "consonant (§3E, §5).",
    ),
    ChangeLabel("t_unreleased") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "A final /t/ held without a burst (what, that) is the everyday American form; releasing it fully sounds " +
      "over-careful (§3B).",
    ),
    ChangeLabel("glottalization") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "Safe before a syllabic n (button, important — stage 3); before a vowel it is optional and led by young " +
      "Western speakers (§3B).",
    ),
    ChangeLabel("th_stopping") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.MARKED,
      "th → d/t is an AAVE and regional feature (§5): recognize it; a learner should keep working on θ/ð instead (§7).",
    ),
    ChangeLabel("palatalization") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "didja, gotcha, doncha are universal and safe to produce (§3D).",
    ),
    ChangeLabel("linking") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "Consonant–vowel linking, linking r and the w/j glides are fundamental to fluency and safe to produce (§3G).",
    ),
    ChangeLabel("h_dropping") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "Safe in unstressed pronouns and auxiliaries — but never at the start of a sentence (§3C).",
    ),
    ChangeLabel("contraction_lex") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "gonna, wanna, gotta, gimme, lemme, kinda are universal and safe; some forms are marked — see each form (§1).",
    ),
    ChangeLabel("place_assimilation") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.CASUAL,
      "tem bucks, ing case happen only in fast, natural speech; forced, they sound odd (§3F, §7).",
    ),
    ChangeLabel("nt_reduction") to PracticeAdvice(
      Advice.UNDERSTAND,
      Register.MARKED,
      "winter → winner is very American and widespread but variable; the report files it under understand only (§5).",
    ),
    ChangeLabel("function_elision") to PracticeAdvice(
      Advice.PRODUCE,
      Register.UNIVERSAL,
      "of → ə and them → əm are the weak forms the report says to produce (§2, stage 2): lotsa, kinda, tell 'em.",
    ),
  )

  val lexicalPractice: Map<String, PracticeAdvice> = linkedMapOf(
    "gonna" to PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Universal informal form, safe to produce (§1)."),
    "wanna" to PracticeAdvice(Advice.PRODUCE, Register.UNIVERSAL, "Universal informal form, safe to produce (§1)."),
  )

  val ipaTokens: List<String> = listOf(
    "aɪə", "aɪɚ", "oːɹ", "ɑːɹ", "ɔːɹ", "aɪ", "aʊ", "b̚", "dʒ", "d̚", "eə", "eɪ", "iə", "iː", "ju", "k̚", "l̩", "m̩",
    "n̩", "oʊ", "p̚", "tʃ", "t̚", "uː", "ŋ̍", "ɑː", "ɔɪ", "ɔː", "əl", "ɛɹ", "ɜː", "ɡ̚", "ɪɹ", "ɾ̃", "ʊɹ",
  )

  val metricsReference: Map<String, MetricReference> = linkedMapOf(
    "deviate" to MetricReference(
      low = 60.0,
      high = null,
      display = "> 60 %",
      cite = "Johnson 2004",
      source = "Johnson 2004, Massive reduction in conversational American English (ViC/Buckeye, ~88,000 words)",
      note = "words that depart from their citation form in at least one segment",
    ),
    "flapping" to MetricReference(
      low = 74.8,
      high = 81.6,
      display = "74.8–81.6 %",
      cite = "Patterson & Connine 2001",
      source = "Patterson & Connine 2001, Phonetica 58:254–275 (Switchboard: Northern 74.8 %, North Midland 81.6 %); " +
        "99 % in read speech (Zue & Laferriere 1979)",
      note = "flapped /t, d/ in the flapping context (after a vowel or /r/, before an unstressed vowel)",
    ),
  )

  val metricLabels: Map<String, String> = linkedMapOf(
    "deviate" to "words that differ from the citation form",
    "segment_loss" to "words that lose a whole segment",
    "syllable_loss" to "words that lose a syllable",
    "schwa_share" to "reduced vowels (schwa) among all vowels",
    "function_words" to "function words among all words",
    "weak_forms" to "function words in their weak form",
    "flapping" to "t/d flapped where flapping can happen",
    "glottal_before_syllabic_n" to "glottal t before a syllabic n (button)",
    "glottal_prevocalic" to "final t before a vowel as a glottal stop",
  )

  val reference: Reference = Reference(
    apiVersion = API_VERSION,
    families = families,
    familyOf = families.flatMap { family -> family.members.map { it to family.family } }.toMap(),
    labels = labels,
    descriptions = descriptions,
    practice = practice,
    lexicalPractice = lexicalPractice,
    linkTypes = listOf("consonant", "r", "glide_w", "glide_j"),
    ipaTokens = ipaTokens,
    metricsReference = metricsReference,
    metricLabels = metricLabels,
  )
}
