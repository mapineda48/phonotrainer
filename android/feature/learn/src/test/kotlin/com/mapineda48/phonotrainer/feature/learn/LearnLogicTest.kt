package com.mapineda48.phonotrainer.feature.learn

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import org.junit.Test

/** The pure rules of Learn (spec F-14, F-15, F-16) on the bundled content and the sample reference. */
class LearnLogicTest {
  private val reference = SampleReference.reference
  private val content = BundledContent.didactic()

  @Test
  fun aPlainNameIsCapitalizedAndASymbolLikeNameStaysAsItIs() {
    val names = reference.labels.keys.associate { it.value to LearnLogic.changeName(reference, it) }
    assertThat(names["flapping"]).isEqualTo("Flapping")
    assertThat(names["vowel_reduction"]).isEqualTo("Vowel reduction")
    assertThat(names["h_dropping"]).isEqualTo("H-dropping")
    assertThat(names["th_stopping"]).isEqualTo("Th-stopping")
    assertThat(names["t_deletion"]).isEqualTo("t/d deletion")
    assertThat(names["nt_reduction"]).isEqualTo("nt → n")
    assertThat(LearnLogic.changeName(reference, ChangeLabel("future_change"))).isEqualTo("Future change")
  }

  @Test
  fun learnHasEveryFamilyInOrderThenTheWholeWordChanges() {
    val sections = LearnLogic.sections(reference)
    assertThat(sections.map { it.family }).containsExactly(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY, null).inOrder()
    assertThat(sections.last().cards.map { it.label.value }).containsExactly("word_elision", "contraction_lex")
    // All sixteen changes have a card, each once.
    val labels = sections.flatMap { it.cards }.map { it.label }
    assertThat(labels).containsExactlyElementsIn(reference.labels.keys)
    // A whole-word change wears its own mark: the contraction the lexical one, the elided word none of the four.
    val marks = sections.last().cards.associate { it.label.value to it.family }
    assertThat(marks).containsExactly("word_elision", Family.OTHER, "contraction_lex", Family.LEXICAL)
  }

  @Test
  fun aCardCarriesTheReferencesDescriptionAndAdvice() {
    val card = LearnLogic.card(reference, ChangeLabel("flapping"))
    assertThat(card.family).isEqualTo(Family.TD)
    assertThat(card.description).isEqualTo(reference.descriptions[ChangeLabel("flapping")])
    assertThat(card.advice).isEqualTo(reference.practice[ChangeLabel("flapping")])
  }

  @Test
  fun aChangeNamedOnlyInAFamilyIsStillKnownAndAnUnknownLabelIsNot() {
    assertThat(LearnLogic.isKnown(reference, ChangeLabel("flapping"))).isTrue()
    val renamed = reference.copy(labels = reference.labels - ChangeLabel("flapping"))
    assertThat(LearnLogic.isKnown(renamed, ChangeLabel("flapping"))).isTrue()
    assertThat(LearnLogic.isKnown(reference, ChangeLabel("no_such_change"))).isFalse()
  }

  @Test
  fun thePillNamesTheFamilyAndAWholeWordChangeHasNone() {
    assertThat(LearnLogic.familyName(reference, ChangeLabel("flapping"))).isEqualTo("t/d processes")
    assertThat(LearnLogic.familyName(reference, ChangeLabel("word_elision"))).isNull()
  }

  @Test
  fun theDemoNamesEachChangingSoundAndSaysWhatTheMouthDoes() {
    val demo = LearnLogic.demo(content.lesson(ChangeLabel("flapping"))?.demo, content)!!
    assertThat(demo.word).isEqualTo("water")
    assertThat(demo.from.symbol).isEqualTo("t")
    assertThat(demo.from.name).isEqualTo("Voiceless alveolar stop")
    assertThat(demo.to?.symbol).isEqualTo("ɾ")
    assertThat(demo.to?.name).isEqualTo("Alveolar tap")
    assertThat(demo.to?.cue).startsWith("The tip bounces off the ridge once")
    // The elided word has no demo (spec F-15 item 2).
    assertThat(LearnLogic.demo(content.lesson(ChangeLabel("word_elision"))?.demo, content)).isNull()
  }

  @Test
  fun theChartOpensOnAKnownSymbolEvenWithItsMarksAndOnNothingOtherwise() {
    assertThat(LearnLogic.selectable("ɾ", content)).isEqualTo("ɾ")
    assertThat(LearnLogic.selectable("iː", content)).isEqualTo("iː")
    assertThat(LearnLogic.selectable("§", content)).isNull()
    assertThat(LearnLogic.selectable(" ", content)).isNull()
    assertThat(LearnLogic.selectable(null, content)).isNull()
  }

  @Test
  fun theSymbolSheetSaysTheNameTheTextAsInAndTheChangeItSignals() {
    val detail = LearnLogic.symbolDetail("ɾ", content, reference, ExamplesUi.Loading)
    assertThat(detail.name).isEqualTo("Alveolar tap")
    assertThat(detail.body).contains("You hear it in “water”")
    // The glossary text already says what the mouth does: the sentence is not repeated.
    assertThat(detail.cue).isNull()
    assertThat(detail.asIn).isEqualTo("the -tt- of “butter”")
    assertThat(detail.change).isEqualTo(ChangeLinkUi(ChangeLabel("flapping"), "flapping"))
    // A symbol whose glossary entry links to the chart itself signals no change.
    assertThat(LearnLogic.symbolDetail("p", content, reference, ExamplesUi.Loading).change).isNull()
  }

  @Test
  fun aSymbolWithoutAGlossaryEntryStillSaysWhatTheMouthDoes() {
    val bare = BundledContent.didactic().apply { entries.remove("ipa:p") }
    val detail = LearnLogic.symbolDetail("p", bare, reference, ExamplesUi.Loading)
    assertThat(detail.body).isNull()
    assertThat(detail.cue).startsWith("Both lips seal")
  }

  @Test
  fun aLengthMarkedSymbolFindsItsOwnGlossaryEntry() {
    assertThat(LearnLogic.symbolEntry("iː", content)?.key).isEqualTo("ipa:iː")
    assertThat(LearnLogic.symbolEntry("ˈɾ", content)?.key).isEqualTo("ipa:ɾ")
  }

  @Test
  fun everyPlacedSymbolHasItsName() {
    val names = LearnLogic.names(content.layout, content)
    assertThat(names.keys).containsExactlyElementsIn(content.layout.placed + content.layout.unplaced)
    assertThat(names["ɾ"]).isEqualTo("alveolar tap")
  }

  @Test
  fun wordsWeUseSplitsTermsFromMeasures() {
    val (terms, measures) = LearnLogic.glossaryGroups(
      listOf(GlossaryHit("schwa", "Schwa", "…"), GlossaryHit("metric:deviate", "Words that differ", "…")),
    )
    assertThat(terms.map { it.key }).containsExactly("schwa")
    assertThat(measures.map { it.key }).containsExactly("metric:deviate")
  }
}
