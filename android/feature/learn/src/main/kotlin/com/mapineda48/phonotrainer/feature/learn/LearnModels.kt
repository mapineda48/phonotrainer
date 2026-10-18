package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowState

// The UI states of :feature:learn (spec F-14 index, F-15, F-16; design 9.25, 10.1 T3, P-08, P-09, F-14).

/** One change as Learn shows it: its name, the family whose mark it wears, its description and the report's advice. */
@Immutable
data class ChangeCardUi(
  val label: ChangeLabel,
  /** "Flapping", "t/d deletion" (the reference's name, capitalized when it is a plain word). */
  val name: String,
  /** The family whose line style and icon mark it (`Reference.familyFor`). */
  val family: Family,
  /** `reference.descriptions`, with its IPA example; null when the reference has none. */
  val description: String?,
  /** The badge, the register and why; null when the reference has no advice for it. */
  val advice: PracticeAdvice?,
)

/** One section of Learn: a family (its icon, its name in its line style and what the line means), or the whole-word
 *  changes ([family] null) last. */
@Immutable
data class FamilySectionUi(val family: Family?, val title: String?, val cards: List<ChangeCardUi>)

/** The counts of the local index (spec F-15 item 3, D-48): loading, read, or unreadable (F-15 Error). */
sealed interface CountsUi {
  data object Loading : CountsUi

  @Immutable
  data class Ready(val counts: Map<ChangeLabel, ChangeCount>) : CountsUi {
    /** The short count of [label] (an absent key is 0). */
    fun shorts(label: ChangeLabel): Int = counts[label]?.shorts ?: 0

    /** The occurrence count of [label] (an absent key is 0). */
    fun occurrences(label: ChangeLabel): Int = counts[label]?.occurrences ?: 0
  }

  data object Failed : CountsUi
}

/** The Insights entry tile (work-plan 4.4): shown only when `:feature:insights` binds `InsightsAvailability`. */
@Immutable
data class InsightsEntryUi(val teaser: String?)

/** Learn (T3, spec F-15 item 1). */
@Immutable
data class LearnUiState(
  val sections: List<FamilySectionUi>,
  val counts: CountsUi,
  /** Some clips of the catalogue are not on this phone: "Counted in the clips on this phone." (F-15 item 3). */
  val partial: Boolean,
  val insights: InsightsEntryUi?,
)

/** Examples from the learner's clips (the phenomenon page's up to 8, the symbol sheet's up to 6). */
sealed interface ExamplesUi {
  data object Loading : ExamplesUi

  /** The local index could not be read (F-15 Error). */
  data object Failed : ExamplesUi

  @Immutable
  data class Ready(val rows: List<OccurrenceRowState>) : ExamplesUi
}

/** One sound of the mouth demo's fallback (work-plan 4.4: the name and the "what the mouth does" sentence). */
@Immutable
data class SoundUi(val symbol: String, val name: String?, val cue: String?)

/**
 * "The mouth" on a phenomenon page (spec F-15 item 2, web `MouthDemo`): a word, its dictionary form and what is said,
 * one phone each, and the phone that changes ([from] → [to]; [to] null: it is dropped).
 */
@Immutable
data class MouthDemoUi(
  val word: String,
  val dictionary: List<String>,
  val said: List<String>,
  val from: SoundUi,
  val to: SoundUi?,
)

/** A phenomenon page (P-08, spec F-15 item 2). */
sealed interface PhenomenonUiState {
  data object Loading : PhenomenonUiState

  /** The label is not a change this app knows (an old link): the web's "There is no lesson with that name". */
  data object Unknown : PhenomenonUiState

  @Immutable
  data class Ready(
    val card: ChangeCardUi,
    /** The family's own name for the chip ("t/d processes"); null for a whole-word change outside the families. */
    val familyName: String?,
    /** How to hear it, the Spanish-speaker tip and the caveat; null when the bundled content has no lesson for it. */
    val lesson: LessonContent?,
    /** The mouth demo; null for a change without one (word_elision). */
    val demo: MouthDemoUi?,
    /** The occurrence count line (D-48): loading, read or unreadable. */
    val counts: CountsUi,
    val partial: Boolean,
    val examples: ExamplesUi,
  ) : PhenomenonUiState {
    /** "12 times in your clips": eligible occurrences on this phone, or null while unknown. */
    val occurrences: Int? get() = (counts as? CountsUi.Ready)?.occurrences(card.label)
  }
}

/** The change a symbol signals ("It is the sound of flapping"). */
@Immutable
data class ChangeLinkUi(val label: ChangeLabel, val name: String)

/** The symbol sheet of the IPA chart (P-09, spec F-16). */
@Immutable
data class SymbolDetailUi(
  val symbol: String,
  /** The phonetic name ("Alveolar tap"); null when the articulation table lacks the symbol. */
  val name: String?,
  /** What the mouth does, when the glossary text does not already say it. */
  val cue: String?,
  /** The glossary text with the learner note (web). */
  val body: String?,
  /** "As in: the -tt- of “butter”". */
  val asIn: String?,
  val change: ChangeLinkUi?,
  val examples: ExamplesUi,
)

/** The IPA chart (P-09). */
@Immutable
data class IpaChartUiState(
  val layout: IpaLayout,
  /** The weak vowels: a dotted underline and the word "weak". */
  val weak: Set<String>,
  /** Each placed symbol's phonetic name, read by TalkBack (F-16-AC2) and shown at large text. */
  val names: Map<String, String>,
  val selected: String?,
  val detail: SymbolDetailUi?,
)

/** "Words we use" (spec F-14, D-47). */
@Immutable
data class GlossaryUiState(
  val query: String,
  val terms: List<GlossaryHit>,
  val measures: List<GlossaryHit>,
  /** Every row when the search is empty: the "{total}" of "{n} of {total} terms". */
  val total: Int,
) {
  val shown: Int get() = terms.size + measures.size
}
