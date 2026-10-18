package com.mapineda48.phonotrainer.feature.lesson

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import org.junit.Test

/** The lesson's pure rules (spec F-10, F-12; the web's lesson and `lib/words.ts`). */
class LessonLogicTest {
  private val clip = LessonFixtures.clip
  private val reference = SampleReference.reference

  @Test
  fun timesAreWrittenToTheTenthAsTheWebWritesThem() {
    assertThat(LessonLogic.clock(44.8)).isEqualTo("0:44.8")
    assertThat(LessonLogic.clock(4.84)).isEqualTo("0:04.8")
    // Rounded before splitting into minutes: 59.96 s is 1:00.0, never 0:60.0.
    assertThat(LessonLogic.clock(59.96)).isEqualTo("1:00.0")
    assertThat(LessonLogic.clock(78.4)).isEqualTo("1:18.4")
    assertThat(LessonLogic.clock(-1.0)).isEqualTo("0:00.0")
  }

  @Test
  fun theWordIsShownWithoutThePunctuationAroundIt() {
    assertThat(LessonLogic.displayText("water?")).isEqualTo("water")
    assertThat(LessonLogic.displayText("“Did")).isEqualTo("Did")
    assertThat(LessonLogic.displayText("I'm")).isEqualTo("I'm")
    assertThat(LessonLogic.displayText("9.30.")).isEqualTo("9.30")
    assertThat(LessonLogic.displayText("%")).isEqualTo("%")
  }

  @Test
  fun aWordElisionOrAnUnsureRecognizerIsLowConfidenceAndNoSoundIsNothingHeard() {
    val base = AnalysisFixtures.word(0, "a", 0.0, "ə", "ə")
    assertThat(LessonLogic.reliability(base)).isEqualTo(Reliability.OK)
    assertThat(LessonLogic.reliability(base.copy(lowConfidence = true))).isEqualTo(Reliability.LOW_CONFIDENCE)
    assertThat(LessonLogic.reliability(base.copy(changes = listOf(ChangeLabel("word_elision"))))).isEqualTo(Reliability.LOW_CONFIDENCE)
    assertThat(LessonLogic.reliability(base.copy(realizedPhones = emptyList()))).isEqualTo(Reliability.NOTHING_HEARD)
  }

  @Test
  fun aChangeIsNamedByTheReferenceAndAnUnknownOneByItsKey() {
    val word = checkNotNull(clip.word(LessonFixtures.WATER))
    val flapping = LessonLogic.change(reference, word, ChangeLabel("flapping"))
    assertThat(flapping.name).isEqualTo("flapping")
    assertThat(flapping.family).isEqualTo(Family.TD)
    val unknown = LessonLogic.change(reference, word, ChangeLabel("future_change"))
    assertThat(unknown.name).isEqualTo("future change")
    assertThat(unknown.family).isEqualTo(Family.OTHER)
    assertThat(unknown.advice).isNull()
  }

  @Test
  fun aWordAtTheStartOfTheClipIsNeverPlayedFromBeforeZero() {
    val did = checkNotNull(clip.word(LessonFixtures.DID))
    assertThat(LessonLogic.wordSpan(clip, LessonFixtures.DID, did)).isEqualTo(ClipSpan(LessonFixtures.JOB, 0, 230, LessonFixtures.DID))
  }

  @Test
  fun practiceAddsTheNextWordOfThePhraseOrAcrossTheBoundaryButNeverAfterAPause() {
    assertThat(LessonLogic.practiceNext(clip, LessonFixtures.DID)?.text).isEqualTo("you")
    assertThat(LessonLogic.practiceNext(clip, LessonFixtures.GET)?.text).isEqualTo("the")
    // "water?" ends its phrase and does not link: the next phrase's "I'm" is not added.
    assertThat(LessonLogic.practiceNext(clip, LessonFixtures.WATER)).isNull()
    assertThat(LessonLogic.defaultTarget(clip, LessonFixtures.WATER)).isEqualTo(ShadowingTarget.PHRASE)
    assertThat(LessonLogic.defaultTarget(clip, LessonFixtures.GONNA)).isEqualTo(ShadowingTarget.WORD)
  }

  @Test
  fun theSoundCardNamesTheHeardSoundAndFindsItsGlossaryEntry() {
    val sorts = LessonLogic.sorts(LessonFixtures.waterRows)
    val tap = LessonLogic.sound(sorts[2], FakeDidacticContent())
    assertThat(tap.symbol).isEqualTo("ɾ")
    assertThat(tap.name).isEqualTo("alveolar tap")
    assertThat(tap.explainKey).isEqualTo("ipa:ɾ")
    val w = LessonLogic.sound(sorts[0], FakeDidacticContent())
    assertThat(w.name).isNull()
    assertThat(w.explainKey).isNull()
  }

  @Test
  fun aPhraseWithoutWordsIsSkippedBothWays() {
    assertThat(LessonLogic.next(clip, LessonFixtures.GO)).isEqualTo(LessonFixtures.OK)
    assertThat(LessonLogic.previous(clip, LessonFixtures.OK)).isEqualTo(LessonFixtures.GO)
    assertThat(LessonLogic.position(clip, LessonFixtures.OK)).isEqualTo(9)
  }
}
