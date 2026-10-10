package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import java.util.Locale

/**
 * What the reference says about a change on a word: its practice badge ("Safe to produce" / "Recognize only") and
 * the family whose mark it wears. Built from the reference snapshot (`/api/reference`), so the rules never hard-code a
 * label (a future label is simply unknown: no family, "Recognize only").
 */
class ChangeAdvice(private val reference: Reference) {
  /**
   * The web's `adviceFor`: a lexical contraction takes the advice of its own reduced form ("tryna" is "Recognize
   * only" while "gonna" is "Safe to produce"), else the label's advice. A label the reference does not advise on is
   * [Advice.UNDERSTAND], the cautious badge.
   */
  fun advice(word: Word, label: ChangeLabel): Advice {
    if (label.value == CONTRACTION_LEX) {
      word.lexicalForm?.let { form -> reference.lexicalPractice[form.lowercase(Locale.ROOT)]?.let { return it.advice } }
    }
    return reference.practice[label]?.advice ?: Advice.UNDERSTAND
  }

  /** The family whose mark [label] wears (web `ui/families.tsx`): OTHER for a label with none, or an unknown one. */
  fun family(label: ChangeLabel): Family = reference.familyFor(label)

  private companion object {
    const val CONTRACTION_LEX = "contraction_lex"
  }
}
