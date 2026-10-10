package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.Variant
import org.junit.Assert.assertThrows
import org.junit.Test

/** Occurrence rows (architecture 4.2, 9.4) and the indexer's derivation step (9.5), with the D-48 agreement. */
class OccurrenceExtractorTest {
  private val advice = ChangeAdvice(ReferenceSnapshot.reference)
  private val job = AnalysisFixtures.JOB

  private fun ref(segment: Int, word: Int) = WordRef(job, segment, word)

  @Test
  fun mkAnalysisGivesOneRowPerWordAndChange() {
    val clip = AnalysisFixtures.clipData()
    val rows = OccurrenceExtractor.extract(clip, ShortsV1.derive(clip), advice)
    assertThat(rows.map { it.word to it.change.value }).containsExactly(
      ref(0, 0) to "vowel_reduction", ref(0, 1) to "t_deletion", ref(1, 0) to "contraction_lex",
    ).inOrder()
    val does = rows[0]
    assertThat(does).isEqualTo(
      OccurrenceRecord(
        word = ref(0, 0), change = ChangeLabel("vowel_reduction"), shortFirstSegment = 0, wordText = "does",
        wordKey = "does", dictIpa = "dʌz", realizedIpa = "dəz", startMs = 0, endMs = 170, diffCost = 0.0,
        lowConfidence = false, eligible = true, crossesBoundary = true, nextWordText = "that",
        advice = Advice.PRODUCE, family = Family.REDUCTION,
      ),
    )
    assertThat(rows[1].advice).isEqualTo(Advice.UNDERSTAND)
    assertThat(rows[1].family).isEqualTo(Family.TD)
    assertThat(rows[1].crossesBoundary).isFalse()
    // the lexical contraction wears the lexical mark; "want to" has no advice of its own, so the label's applies
    assertThat(rows[2].family).isEqualTo(Family.LEXICAL)
    assertThat(rows[2].advice).isEqualTo(Advice.PRODUCE)
    assertThat(rows[2].nextWordText).isEqualTo("go")
  }

  @Test
  fun eligibilityIsThePlayableRule() {
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val rows = OccurrenceExtractor.extract(clip, ShortsV1.derive(clip), advice)
    assertThat(rows).hasSize(10)
    val ineligible = rows.filterNot { it.eligible }.map { it.wordText }
    assertThat(ineligible).containsExactly("go", "uh") // low confidence; 50 ms
    assertThat(rows.single { it.wordText == "go" }.lowConfidence).isTrue()
    // a label nobody knows has no family and the cautious badge
    val unknown = parse(AnalysisFixtures.bytes(Variant.UNKNOWN_ENUMS))
    val newLabel = OccurrenceExtractor.extract(unknown, ShortsV1.derive(unknown), advice)
      .single { it.change.value == "new_label" }
    assertThat(newLabel.family).isEqualTo(Family.OTHER)
    assertThat(newLabel.advice).isEqualTo(Advice.UNDERSTAND)
  }

  @Test
  fun wordKeysAndTheNextWordOnTheTimeline() {
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val rows = OccurrenceExtractor.extract(clip, ShortsV1.derive(clip), advice)
    val water = rows.single { it.wordText == "water?" }
    assertThat(water.wordKey).isEqualTo("water")
    assertThat(water.nextWordText).isEqualTo("I'm") // the first word of the next phrase
    assertThat(water.crossesBoundary).isFalse()
    assertThat(rows.single { it.wordText == "9.30." }.wordKey).isEmpty()
    assertThat(WordKey.of("Don't,")).isEqualTo("don't")
    assertThat(WordKey.of("THAT")).isEqualTo("that")
  }

  @Test
  fun domainOccurrencesCarryTheWordsOtherChanges() {
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val rows = OccurrenceExtractor.extract(clip, ShortsV1.derive(clip), advice)
    val occurrences = OccurrenceExtractor.occurrences(rows, clipTitle = "A clip")
    val get = occurrences.filter { it.wordText == "get" }.associateBy { it.change.value }
    assertThat(get.getValue("flapping").otherChanges).containsExactly(ChangeLabel("linking"))
    assertThat(get.getValue("linking").otherChanges).containsExactly(ChangeLabel("flapping"))
    assertThat(get.getValue("linking").crossesBoundary).isTrue()
    assertThat(occurrences.first().clipTitle).isEqualTo("A clip")
    assertThat(occurrences.single { it.wordText == "water?" }.otherChanges).isEmpty()
  }

  @Test
  fun aPhraseOutsideEveryShortIsAProgrammingError() {
    val clip = AnalysisFixtures.clipData()
    assertThrows(IllegalArgumentException::class.java) { OccurrenceExtractor.extract(clip, emptyList(), advice) }
  }

  @Test
  fun theDerivationOfACurrentClip() {
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val derived = ClipDerivation.derive(clip, advice)
    assertThat(derived.ruleVersion).isEqualTo(ShortsV1.RULE_VERSION)
    val short = derived.shorts.single() // 0–1.1, 2.5–3.7, 4.5–5.5: one short of 5.5 s
    assertThat(short.ordinal).isEqualTo(1)
    assertThat(short.bounds.firstSegment to short.bounds.lastSegment).isEqualTo(0 to 2)
    assertThat(short.markedWords).isEqualTo(9)
    // "gonna" (Safe to produce, diff_cost 0) beats the clearer-looking but unusable "uh" (50 ms)
    assertThat(short.defaultSpotlight)
      .isEqualTo(Spotlight(ref(1, 1), ChangeLabel("contraction_lex"), Advice.PRODUCE, crossesBoundary = false))
    assertThat(derived.occurrences).hasSize(10)
  }

  @Test
  fun changeCountsAndChangeModesAgree() {
    // D-48: a change's short count (distinct shorts among its eligible rows) is exactly what its mode plays
    val clip = parse(AnalysisFixtures.syntheticBytes())
    val derived = ClipDerivation.derive(clip, advice)
    assertThat(derived.shorts.map { it.ordinal }).isEqualTo((1..derived.shorts.size).toList())
    val rowsByShort = derived.occurrences.groupBy { it.shortFirstSegment }
    val labels = derived.occurrences.map { it.change }.toSet()
    assertThat(labels.size).isAtLeast(10)
    for (label in labels) {
      val counted = derived.occurrences.filter { it.eligible && it.change == label }
        .map { it.shortFirstSegment }.toSet()
      val played = derived.shorts.map { it.bounds.firstSegment }.filter { first ->
        SpotlightRules.choose(rowsByShort[first].orEmpty(), SpotlightMode.Change(label)) != null
      }.toSet()
      assertThat(played).isEqualTo(counted)
    }
    // every default spotlight is an eligible row of its own short
    for (short in derived.shorts) {
      val spot = short.defaultSpotlight ?: continue
      val row = derived.occurrences.single { it.word == spot.word && it.change == spot.change }
      assertThat(row.eligible).isTrue()
      assertThat(row.shortFirstSegment).isEqualTo(short.bounds.firstSegment)
    }
    assertThat(derived.shorts.count { it.defaultSpotlight != null }).isAtLeast(derived.shorts.size / 2)
  }
}
