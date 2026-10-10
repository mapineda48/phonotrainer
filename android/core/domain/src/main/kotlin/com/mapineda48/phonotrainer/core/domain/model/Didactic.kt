// Didactic content and the reference snapshot: work-plan Appendix A.8 as delivered by WP-04 (didactic.json schema 1,
// android/tools/didactic/schema.mjs; the /api/reference body). Differences from A.8, each additive or a correction the
// data requires, are WP-04's shape notes: IpaGroup.detail, PracticeAdvice + Register, MetricReference,
// Reference.familyFor. ChangeLabel, Family and Advice are the types of architecture 3.4.
package com.mapineda48.phonotrainer.core.domain.model

// --- didactic.json schema 1: glossary, Learn lessons, IPA chart layout, articulation table ---

/** One "What's this?" entry. `key` is the glossary key: "weak-form" (a term), "ipa:ɾ" (a
 *  symbol, also "ipa:iː" for the length-marked spellings), "metric:deviate" (a measure). */
data class GlossaryEntry(
  val key: String,
  val title: String,
  /** One to three sentences. IPA inside /…/ or […], or after "→", is set in the IPA font. */
  val body: String,
  /** An example line ("to → tə"); IPA after "→" is set in the IPA font. */
  val example: String?,
  val learnMore: LinkTarget?,
  /** The link's own text when the web gives one ("Open the IPA chart"); null: the UI's default. */
  val learnMoreLabel: String?,
)

/** Where "Learn more" goes inside the app: the extractor's href table (architecture 15.1). */
sealed interface LinkTarget {
  data object Learn : LinkTarget
  data class Phenomenon(val label: ChangeLabel) : LinkTarget
  /** null: the chart with nothing selected. */
  data class Ipa(val symbol: String?) : LinkTarget
  /** null: a session over every change. */
  data class Practice(val focus: ChangeLabel?) : LinkTarget
  data object Insights : LinkTarget
}

/** A row of "Words we use" or a search result. */
data class GlossaryHit(val key: String, val title: String, val snippet: String)

data class LessonContent(
  val change: ChangeLabel,
  /** How to recognise it by ear. */
  val listenFor: String,
  val demo: MouthDemo?,
  /** "For Spanish speakers" (F-15-AC3): only where the report gives one. */
  val spanishTip: String?,
  /** A usage limit or caveat. */
  val note: String?,
)

data class MouthDemo(
  val word: String,
  /** Dictionary form, one symbol per phone. */
  val dictionary: List<String>,
  /** What a native speaker typically says. */
  val said: List<String>,
  /** The phone that changes, in `dictionary`… */
  val from: String,
  /** …and what it becomes in `said`; null: it is dropped. */
  val to: String?,
)

data class IpaLayout(
  val places: List<IpaColumn>,
  val consonantRows: List<IpaGridRow>,
  /** The columns of the "sounds that replace t and d" grid, a subset of `places`. */
  val allophonePlaces: List<IpaColumn>,
  val allophoneRows: List<IpaGridRow>,
  val syllabics: List<String>,
  val vowelColumns: List<IpaColumn>,
  val vowelRows: List<IpaGridRow>,
  /** The weak vowels unstressed syllables reduce to. */
  val reducedVowels: List<String>,
  val glidingGroups: List<IpaGroup>,
  /** Every symbol the layout places. */
  val placed: List<String>,
  /** Symbols of the articulation table the layout does not place (shown under "Other symbols"). */
  val unplaced: List<String>,
)

/** A chart column. `detail` is "" when the web gives no description (the vowel columns). */
data class IpaColumn(val key: String, val label: String, val detail: String)

/** A chart row. `detail` may be ""; `cells` maps a column key to its symbols, and a column
 *  with no symbol in this row is absent. */
data class IpaGridRow(val label: String, val detail: String, val cells: Map<String, List<String>>)

data class IpaGroup(val label: String, val detail: String, val symbols: List<String>)

data class PhoneArticulation(
  val symbol: String,
  /** The phonetic name, read by TalkBack (F-16-AC2): "voiced alveolar tap". */
  val name: String,
  /** An English word or phrase where it can be heard. */
  val example: String,
  /** What the mouth does, in one or two sentences. */
  val cue: String,
  /** vowel, diphthong, stop, unreleased, nasal, fricative, affricate, approximant, lateral, tap or glottal. */
  val manner: String,
  val voiced: Boolean,
  /** Empty for /h/ and /ʔ/, which keep the shape around them. */
  val gestures: List<Gesture>,
  /** /h/ and /ʔ/ only: the articulators they impose on that inherited shape; null otherwise. */
  val carry: Map<String, Double>?,
)

/** One articulatory target, reached at `at` (0..1) of the phone's span. `pose` holds all
 *  eleven articulators, each 0..1: jaw, lipOpen, lipRound, lipTuck, body, height, tip,
 *  tipFront, root, velum, voice. */
data class Gesture(val at: Double, val pose: Map<String, Double>)

// --- the /api/reference snapshot (reference.json), replaced when the computer's api_version differs ---

data class Reference(
  val apiVersion: Int,
  val families: List<FamilyInfo>,
  /** The 14 labels that belong to a family; see `familyFor`. */
  val familyOf: Map<ChangeLabel, Family>,
  val labels: Map<ChangeLabel, String>,
  val descriptions: Map<ChangeLabel, String>,
  val practice: Map<ChangeLabel, PracticeAdvice>,
  /** Per lexical reduced form: "gonna", "wanna"… */
  val lexicalPractice: Map<String, PracticeAdvice>,
  val linkTypes: List<String>,
  /** Multi-character IPA symbols, longest first (the IPA tokenizer's table). */
  val ipaTokens: List<String>,
  val metricsReference: Map<String, MetricReference>,
  val metricLabels: Map<String, String>,
)

data class FamilyInfo(val family: Family, val label: String, val members: List<ChangeLabel>, val memberLabels: List<String>)

/** The report's advice for a change or a lexical form: the badge ("Safe to produce" /
 *  "Recognize only"), how widely it is used, and why (spec 4 "Practice advice", 9.5 row 5). */
data class PracticeAdvice(val advice: Advice, val register: Register, val why: String)

/** "universal": every speaker, every situation; "casual": relaxed speech; "marked": a region or group. */
enum class Register { UNIVERSAL, CASUAL, MARKED }

/** A published figure a measure is compared with (Insights, the clip summary); `low` and
 *  `high` are percentages, either may be null (an open range). */
data class MetricReference(
  val low: Double?,
  val high: Double?,
  /** "> 60 %", "74.8–81.6 %". */
  val display: String,
  /** "Johnson 2004". */
  val cite: String,
  val source: String,
  val note: String,
)

/** The family whose mark a label wears: its family when it has one; LEXICAL for the lexical
 *  contraction; OTHER (no hue, no mark) for the rest (web `ui/families.tsx`). */
fun Reference.familyFor(label: ChangeLabel): Family =
  familyOf[label] ?: if (label.value == "contraction_lex") Family.LEXICAL else Family.OTHER
