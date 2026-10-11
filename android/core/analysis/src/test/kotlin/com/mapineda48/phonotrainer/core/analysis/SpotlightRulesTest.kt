package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.phrase
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.word
import org.junit.Test

/**
 * The spotlight order of spec F-07 item 3 (architecture 9.3): saved and change modes, "Safe to produce" first, the
 * clearest alignment, the earliest word, candidates only, boundary changes; deterministic (F-07-AC8).
 */
class SpotlightRulesTest {
  private val advice = ChangeAdvice(ReferenceSnapshot.reference)
  private val job = AnalysisFixtures.JOB

  private fun clipOf(vararg phrases: List<Word>): ClipData = AnalysisFixtures.clip(
    phrases.mapIndexed { i, words -> phrase(i, startS = i * 10.0, endS = i * 10.0 + 3.0, words = words) },
  )

  /** Every occurrence row of [clip], whatever its short (the tests choose among rows they pass). */
  private fun rows(clip: ClipData) = OccurrenceExtractor.extract(clip, ShortsV1.derive(clip), advice)

  private fun ref(segment: Int, word: Int) = WordRef(job, segment, word)

  private fun label(value: String) = ChangeLabel(value)

  /** A three-phone word (0.17 s: long enough to be a candidate); words start every 0.5 s. */
  private fun w(
    index: Int,
    text: String,
    changes: List<String>,
    diff: Double,
    low: Boolean = false,
    link: Boolean = false,
  ) = word(
    index, text, index * 0.5, "a b c", "a b c", changes = changes, diffCost = diff, lowConfidence = low,
    boundaryLinkNext = link,
  )

  @Test
  fun safeToProduceComesBeforeRecognizeOnly() {
    // t_deletion is "Recognize only" and clearer; flapping is "Safe to produce" (the reference's advice)
    val clip = clipOf(listOf(w(0, "next", listOf("t_deletion"), 0.05), w(1, "water", listOf("flapping"), 0.3)))
    val spot = SpotlightRules.choose(rows(clip), SpotlightMode.Default)
    assertThat(spot).isEqualTo(Spotlight(ref(0, 1), label("flapping"), Advice.PRODUCE, crossesBoundary = false))
  }

  @Test
  fun thenTheClearestAlignmentThenTheEarliestWord() {
    val clip = clipOf(
      listOf(w(0, "better", listOf("flapping"), 0.4), w(1, "water", listOf("flapping"), 0.2)),
      listOf(w(0, "city", listOf("flapping"), 0.2), w(1, "little", listOf("flapping"), 0.1)),
    )
    assertThat(SpotlightRules.choose(rows(clip), SpotlightMode.Default)?.word).isEqualTo(ref(1, 1))
    // a tie on diff_cost goes to the earliest word, across phrases
    val tie = clipOf(
      listOf(w(0, "better", listOf("flapping"), 0.4), w(1, "water", listOf("flapping"), 0.2)),
      listOf(w(0, "city", listOf("flapping"), 0.2)),
    )
    assertThat(SpotlightRules.choose(rows(tie), SpotlightMode.Default)?.word).isEqualTo(ref(0, 1))
  }

  @Test
  fun twoLabelsOfOneWordWithTheSameAdviceGoInAlphabeticalOrder() {
    val clip = clipOf(listOf(w(0, "get", listOf("linking", "flapping"), 0.2)))
    assertThat(SpotlightRules.choose(rows(clip), SpotlightMode.Default)?.change).isEqualTo(label("flapping"))
  }

  @Test
  fun onlyCandidatesCanBeTheSpotlight() {
    val tooShort = word(2, "uh", 1.0, "ʌ", "ə", changes = listOf("vowel_reduction"), diffCost = 0.0)
    val nothingHeard = word(3, "and", 1.5, "æ n d", "", changes = listOf("t_deletion"), diffCost = 0.0)
    val clip = clipOf(
      listOf(
        w(0, "go", listOf("vowel_reduction"), 0.0, low = true),
        w(1, "water", listOf("flapping"), 0.9),
        tooShort,
        nothingHeard,
      ),
    )
    assertThat(tooShort.tooShort).isTrue()
    assertThat(nothingHeard.realizedIpa).isNull()
    assertThat(SpotlightRules.choose(rows(clip), SpotlightMode.Default)?.word).isEqualTo(ref(0, 1))
    // a short whose labelled words are all unusable has no spotlight: "No labelled change here"
    val none = clipOf(listOf(w(0, "go", listOf("vowel_reduction"), 0.0, low = true), w(1, "the", emptyList(), 0.0)))
    assertThat(SpotlightRules.choose(rows(none), SpotlightMode.Default)).isNull()
  }

  @Test
  fun aChangeModeShowsTheClearestCandidateWithThatChange() {
    val clip = clipOf(
      listOf(
        w(0, "the", listOf("vowel_reduction"), 0.0),
        w(1, "lot", listOf("flapping"), 0.05, low = true), // F-06-AC5: never a low-confidence instance
        w(2, "better", listOf("flapping"), 0.3),
        w(3, "water", listOf("flapping"), 0.2),
      ),
    )
    val spot = SpotlightRules.choose(rows(clip), SpotlightMode.Change(label("flapping")))
    assertThat(spot).isEqualTo(Spotlight(ref(0, 3), label("flapping"), Advice.PRODUCE, crossesBoundary = false))
    assertThat(SpotlightRules.choose(rows(clip), SpotlightMode.Change(label("t_deletion")))).isNull()
    assertThat(SpotlightRules.hasCandidate(rows(clip), label("flapping"))).isTrue()
    assertThat(SpotlightRules.hasCandidate(rows(clip), label("t_deletion"))).isFalse()
  }

  @Test
  fun savedModeShowsTheSavedWord() {
    val clip = clipOf(
      listOf(
        w(0, "water", listOf("flapping"), 0.1),
        w(1, "go", listOf("vowel_reduction"), 0.6, low = true),
        w(2, "get", listOf("t_deletion", "flapping"), 0.5),
        w(3, "the", emptyList(), 0.0),
      ),
    )
    val rows = rows(clip)
    // the saved word wins over a clearer one, even when it is not a candidate (the learner chose it)
    val saved = SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 1), label("vowel_reduction")))
    assertThat(saved).isEqualTo(Spotlight(ref(0, 1), label("vowel_reduction"), Advice.PRODUCE, false))
    // with its saved change, or, when the word no longer has it, its best change by rules 3 to 5
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 2), label("t_deletion")))?.change)
      .isEqualTo(label("t_deletion"))
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 2), label("linking")))?.change)
      .isEqualTo(label("flapping"))
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 2)))?.change).isEqualTo(label("flapping"))
    // a saved word without a change falls back to the default spotlight
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 3)))?.word).isEqualTo(ref(0, 0))
  }

  @Test
  fun aChangeThatCrossesTheBoundaryPlaysWithTheNextWord() {
    val clip = clipOf(
      listOf(w(0, "did", listOf("palatalization"), 0.1), w(1, "you", emptyList(), 0.0)),
      listOf(w(0, "an", emptyList(), 0.0, link = true), w(1, "apple", listOf("linking"), 0.2)),
      listOf(w(0, "it", listOf("flapping"), 0.3, link = true)),
    )
    val rows = rows(clip)
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(0, 0)))?.crossesBoundary).isTrue()
    // linking on a phrase's last word links onto the next phrase's first word
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(1, 1)))?.crossesBoundary).isTrue()
    // boundary_link_next alone, but at the end of the clip there is no next word to play
    assertThat(SpotlightRules.choose(rows, SpotlightMode.Saved(ref(2, 0)))?.crossesBoundary).isFalse()
  }

  @Test
  fun aContractionTakesTheAdviceOfItsOwnReducedForm() {
    val contraction = listOf("contraction_lex")
    val gonna = word(0, "gonna", 0.0, "ɡ ʌ n ə", "ɡ ʌ n ə", changes = contraction, lexicalForm = "gonna")
    val tryna = word(1, "tryna", 1.0, "t ɹ aɪ n ə", "t ɹ aɪ n ə", changes = contraction, lexicalForm = "Tryna")
    val clip = clipOf(listOf(gonna, tryna))
    val byWord = rows(clip).associateBy { it.word.word }
    assertThat(byWord.getValue(0).advice).isEqualTo(Advice.PRODUCE)
    assertThat(byWord.getValue(1).advice).isEqualTo(Advice.UNDERSTAND) // "tryna": recognize only
  }

  @Test
  fun theSpotlightIsTheSameWhateverTheOrderOfTheRows() {
    // F-07-AC8: the same short shown twice (rows come back from a database in any order) has one spotlight
    val clip = AnalysisFixtures.clip(
      List(6) { p ->
        phrase(
          p, startS = p * 4.0, endS = p * 4.0 + 3.0,
          words = List(5) { i ->
            w(i, "w$p$i", listOf(listOf("flapping", "t_deletion", "linking", "vowel_reduction")[(p + i) % 4]), 0.25)
          },
        )
      },
    )
    val rows = rows(clip)
    val expected = SpotlightRules.choose(rows, SpotlightMode.Default)
    repeat(20) { seed ->
      val shuffled = rows.shuffled(java.util.Random(seed.toLong()))
      assertThat(SpotlightRules.choose(shuffled, SpotlightMode.Default)).isEqualTo(expected)
      assertThat(SpotlightRules.choose(shuffled, SpotlightMode.Change(label("linking"))))
        .isEqualTo(SpotlightRules.choose(rows, SpotlightMode.Change(label("linking"))))
    }
  }
}
