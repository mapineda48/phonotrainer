package com.mapineda48.phonotrainer.core.ui.component

import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import kotlin.math.max

/**
 * What an example row plays and says (WP-20, design 9.25), shared by every screen that lists [Occurrence]s (Learn, the
 * IPA symbol sheet, Saved, Practice explanations), so an example sounds and reads the same everywhere.
 */
object OccurrenceRowPlayback {
  /** The web's `clipPadding`: a little before and after the word (the player stretches anything under 0.25 s). */
  const val PAD_BEFORE_MS: Long = 100
  const val PAD_AFTER_MS: Long = 120

  /** A change that happens between two words runs into the next one, so the clip plays on into it. */
  const val BOUNDARY_PAD_BEFORE_MS: Long = 120
  const val BOUNDARY_PAD_AFTER_MS: Long = 450

  /** The stretch of its clip that [occurrence] plays, padded as the web pads an example. */
  fun span(occurrence: Occurrence): ClipSpan {
    val boundary = runsIntoNext(occurrence) || occurrence.family == Family.BOUNDARY
    val before = if (boundary) BOUNDARY_PAD_BEFORE_MS else PAD_BEFORE_MS
    val after = if (boundary) BOUNDARY_PAD_AFTER_MS else PAD_AFTER_MS
    return ClipSpan(
      jobId = occurrence.word.jobId,
      startMs = max(0L, occurrence.startMs - before),
      endMs = occurrence.endMs + after,
      word = occurrence.word,
    )
  }

  /** True when the change crosses into the next word and there is one: the row shows both words, linked. */
  fun runsIntoNext(occurrence: Occurrence): Boolean = occurrence.crossesBoundary && !occurrence.nextWordText.isNullOrBlank()

  /** The words the row shows: the word without the punctuation the recognizer attached ("water?" → "water"). */
  fun displayWord(text: String): String {
    val trimmed = text.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
    return trimmed.ifEmpty { text.trim() }
  }

  /** The dictionary form without its stress marks, comparable with what was said (web `stripStress`). */
  fun dictionaryForm(occurrence: Occurrence): String? =
    occurrence.dictIpa?.replace(STRESS, "")?.trim()?.takeIf { it.isNotEmpty() }

  /** What was said; "∅" when nothing was heard (web). */
  fun saidForm(occurrence: Occurrence): String = occurrence.realizedIpa?.trim()?.takeIf { it.isNotEmpty() } ?: NOTHING

  /** The empty-set sign the web writes for a sound that was not heard. */
  const val NOTHING = "∅"

  private val STRESS = Regex("[ˈˌ]")
}
