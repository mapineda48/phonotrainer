package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.LinkType
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.WordClass
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget

// The lesson's UI state (architecture 3.1: an immutable state per screen, built by LessonViewModel and drawn by the
// stateless LessonScreen). Words that need resources are chosen by the screen; the state carries the facts.

/** The five steps of spec F-10 item 3, by number. */
enum class LessonStep(val number: Int) {
  LISTEN(1),
  COMPARE(2),
  WHY(3),
  SEE(4),
  PRACTICE(5);

  companion object {
    fun of(number: Int): LessonStep? = entries.firstOrNull { it.number == number }
  }
}

/** Whether the word's labels can be trusted (web `reliability`): the header badge and step 3's notices. */
enum class Reliability { OK, NOTHING_HEARD, LOW_CONFIDENCE }

/** What step 1 plays. */
enum class ListenTarget { WORD, WITH_NEXT, PHRASE }

/** Which row step 4 shows the mouth for: "What was said" or "Dictionary form". */
enum class MouthStream { SAID, DICTIONARY }

/** Why the lesson cannot show its word. */
enum class Unavailable {
  /** The clip is not on this phone (any more). */
  CLIP_GONE,

  /** The clip's analysis is on the phone but cannot be read: it needs downloading again. */
  CLIP_DAMAGED,

  /** The clip is here but has no such word (a newer analysis changed it). */
  WORD_GONE,
}

/** Why step 3 has no change card. */
enum class NoChange {
  /** "With no dictionary form to compare against, no change can be labeled here." */
  NO_DICTIONARY,

  /** "Nothing changed: this word was said the way the dictionary says it." */
  SAME,

  /** "No labeled change. The sounds may still differ slightly…" */
  UNLABELED,
}

/** One change on the word: its label, its name from the reference, its family and the report's advice. */
@Immutable
data class ChangeUi(val label: ChangeLabel, val name: String, val family: Family, val advice: PracticeAdvice?)

/** The header (design 9.9). */
@Immutable
data class HeaderUi(
  val ref: WordRef,
  /** The word as written, without the punctuation around it ("water"). */
  val text: String,
  /** The next word when the change crosses the boundary ("“did” + “you”"). */
  val nextText: String?,
  /** "Word 12 of 431". */
  val position: Int,
  val total: Int,
  /** "phrase 4" (1-based). */
  val phrase: Int,
  val startS: Double,
  val endS: Double,
  val changes: List<ChangeUi>,
  /** The badge beside the chips: the advice of the first change that has one. */
  val advice: PracticeAdvice?,
  val reliability: Reliability,
  val canPrevious: Boolean,
  val canNext: Boolean,
  val canNextChange: Boolean,
  val saved: Boolean,
)

/** Step 1, Listen. */
@Immutable
data class ListenUi(
  val hasAudio: Boolean,
  /** "Play with “you”": the next word, only when the change crosses the boundary. */
  val nextText: String?,
  /** How many times something of this word was played ("✓ played 2×"). */
  val played: Int,
  val speed: Float,
  val loop: Boolean,
  val track: Track,
  /** A dialogue track is on the phone: the track choice is offered. */
  val dialogue: Boolean,
  /** What is playing now, or null. */
  val playing: ListenTarget?,
)

/** One sort of step 2: an expected sound, a heard one, or both, with its mark. */
@Immutable
data class SortUi(
  val index: Int,
  val dictionary: String?,
  val said: String?,
  val status: CompareStatus,
  /** A sort of the next word (the divider and its header go before the first one). */
  val nextWord: Boolean,
  /** The sound's time (the heard one when there is one), for "Play this sound". */
  val startMs: Long,
  val endMs: Long,
)

/** The sound card of the selected sort (design 9.10 item 5). */
@Immutable
data class SoundUi(
  val sort: SortUi,
  /** The symbol the card is about: what was said, else the expected sound. */
  val symbol: String,
  /** From the articulation table; null for a sound outside the drawable inventory. */
  val name: String?,
  val example: String?,
  val cue: String?,
  /** The glossary key of the symbol ("ipa:ɾ") when the glossary has it. */
  val explainKey: String?,
)

/** The boundary gap of step 2 (web `compareWords` `gapMs`). */
@Immutable
data class GapUi(val ms: Int, val family: Family)

/** Step 2, Compare. */
@Immutable
data class CompareUi(
  /** The dictionary form without its slashes ("ˈwɔtɚ", "dɪd ju" across a boundary); null when there is none. */
  val dictionary: String?,
  /** What was said without its brackets; null when nothing was heard. */
  val said: String?,
  /** espeak analyses: the form "Expected by the aligner"; null with a narrow engine. */
  val aligner: String?,
  val narrow: Boolean,
  /** How a numeral was said when it differs from what was written ("nine thirty"). */
  val spoken: String?,
  val noDictionaryForm: Boolean,
  val sorts: List<SortUi>,
  val wordText: String,
  val nextText: String?,
  val gap: GapUi?,
  val selected: SoundUi?,
)

/** One card of step 3. */
@Immutable
data class ChangeCardUi(
  val change: ChangeUi,
  /** `reference.descriptions`, with its IPA example. */
  val description: String?,
  /** Linking only: the kind of link. */
  val linkType: LinkType?,
  /** The lexical contraction: "wanna" ← "want to". */
  val lexicalForm: String?,
  val lexicalExpansion: String?,
  /** The label came from form scoring, not from the heard sounds (espeak, rules 2+). */
  val fromScoring: Boolean,
)

/** espeak's strong vs weak form scoring (web `FormScoring`). */
@Immutable
data class FormScoringUi(val ipa: String, val strongIpa: String, val weak: Boolean, val weakMargin: Double?, val hDropped: Boolean)

/** Step 3, Why it changes. */
@Immutable
data class WhyUi(
  val reliability: Reliability,
  val cards: List<ChangeCardUi>,
  val noChange: NoChange?,
  /** Shown under the cards that came from scoring (espeak), or once for the word when no label did. */
  val formScoring: FormScoringUi?,
  val formScoringOnCards: Boolean,
  /** Percent of the phrase's peak; null when the analysis has no prominence. */
  val prominence: Int?,
  val wordClass: WordClass?,
  /** The peak fell on a function word: contrast or emphasis. */
  val functionPeak: Boolean,
  /** `diff_cost`; null when there is no dictionary form. */
  val divergence: Double?,
  val oov: Boolean,
  val alignmentFallback: Boolean,
  val attracted: Int,
)

/** One sound button of step 4. */
@Immutable
data class MouthSoundUi(
  val index: Int,
  val symbol: String,
  val name: String?,
  val cue: String?,
  val example: String?,
  val startMs: Long,
  val endMs: Long,
  /** The articulation table has it: it can be drawn and named. */
  val drawable: Boolean,
)

/** Step 4, See the mouth (v1-complete; its v1-core fallback names each sound and says what the mouth does). */
@Immutable
data class SeeUi(
  val stream: MouthStream,
  val sounds: List<MouthSoundUi>,
  /** The held sound's index in [sounds], or null to follow the playback. */
  val held: Int?,
  val hasAudio: Boolean,
  /** The phrase's sounds in [stream] (plus the next word's when it starts the next phrase), for the drawing. */
  val track: List<TimedPhone>,
)

/** Step 5, Practice. */
@Immutable
data class PracticeUi(
  val target: ShadowingTarget,
  val wordAndNext: Boolean,
  val slowSpeed: Float,
  val hasAudio: Boolean,
  val recognizeOnly: Boolean,
  /** Running only when this lesson started it. */
  val shadowing: ShadowingState,
  /** What shadowing and recording repeat. */
  val span: ClipSpan,
  val recordTarget: RecordTarget,
)

/** "Paused · Two friends at the lake" over the feed (design 9.9). */
@Immutable
data class PausedUi(val title: String)

/** The lesson screen. */
sealed interface LessonUiState {
  data object Loading : LessonUiState

  @Immutable
  data class Unavailable(val reason: com.mapineda48.phonotrainer.feature.lesson.Unavailable) : LessonUiState

  @Immutable
  data class Ready(
    val presentation: LessonPresentation,
    val header: HeaderUi,
    /** The open steps. */
    val open: Set<LessonStep>,
    /** The step the learner last opened, brought into view when the lesson comes back (F-10-AC6). */
    val at: LessonStep?,
    /** Changes when [at] should be brought into view again ("See it in the mouth" opens step 4). */
    val reveal: Int,
    val listen: ListenUi,
    val compare: CompareUi,
    val why: WhyUi,
    val see: SeeUi,
    val practice: PracticeUi,
    val paused: PausedUi?,
  ) : LessonUiState
}
