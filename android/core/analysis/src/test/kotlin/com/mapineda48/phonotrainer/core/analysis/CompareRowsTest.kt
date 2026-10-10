package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.Variant
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.phrase
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.word
import org.junit.Test

/** The Compare step (spec F-10 step 2): the web's `compareWords` cases (`lib.test.ts`), F-10-AC1 and F-10-AC2. */
class CompareRowsTest {
  /** The web test's notation: "t≠ɾ", "t–∅", "∅+ə". */
  private fun ops(rows: List<CompareRow>) = rows.map { row ->
    val mark = when (row.status) {
      CompareStatus.SAME -> "="
      CompareStatus.CHANGED -> "≠"
      CompareStatus.NOT_HEARD -> "–"
      CompareStatus.ADDED -> "+"
    }
    "${row.dictionary ?: "∅"}$mark${row.said ?: "∅"}"
  }

  @Test
  fun marksADroppedFinalTAndNothingElse() {
    // F-10-AC2: a dropped sound carries "–" (read "{symbol}, not heard")
    val that = word(0, "that", 0.4, "ð æ t", "ð æ")
    val rows = CompareRows.compare(that)
    assertThat(ops(rows)).containsExactly("ð=ð", "æ=æ", "t–∅").inOrder()
    assertThat(rows.last().status).isEqualTo(CompareStatus.NOT_HEARD)
  }

  @Test
  fun pairsADifferentSoundAtTheSameMomentAsAChange() {
    // F-10-AC1: "water" with flapping pairs the expected [t] with the heard [ɾ] as changed
    val water = word(0, "water", 1.0, "w ɔ t ɚ", "w ɔ ɾ ɚ", changes = listOf("flapping"))
    val rows = CompareRows.compare(water)
    assertThat(ops(rows)).containsExactly("w=w", "ɔ=ɔ", "t≠ɾ", "ɚ=ɚ").inOrder()
    val changed = rows[2]
    assertThat(changed.status).isEqualTo(CompareStatus.CHANGED)
    // the timed phones let "Tapping a sound plays it"
    assertThat(changed.dictionaryPhone).isEqualTo(TimedPhone("t", 1.12, 1.17))
    assertThat(changed.saidPhone).isEqualTo(TimedPhone("ɾ", 1.12, 1.17))
    assertThat(changed.change).isNull()
    assertThat(changed.nextWord).isFalse()
  }

  @Test
  fun marksASoundHeardWhereNothingWasExpectedAsAdded() {
    val film = word(0, "film", 1.0, "f ɪ l m", "f ɪ l m")
    val withSchwa = film.copy(realizedPhones = film.realizedPhones + TimedPhone("ə", 1.3, 1.35))
    assertThat(ops(CompareRows.compare(withSchwa)).last()).isEqualTo("∅+ə")
  }

  @Test
  fun doesNotCallTwoPhonesThatMerelyGrazeEachOtherInTimeAChange() {
    // CTC peaks: the same phone can be detected a frame apart in each row
    val dont = word(0, "don't", 0.6, "d oʊ n", "d oʊ n").copy(
      canonicalPhones = listOf(TimedPhone("n", 0.684, 0.704)),
      realizedPhones = listOf(TimedPhone("n", 0.704, 0.744)),
    )
    assertThat(ops(CompareRows.compare(dont))).containsExactly("n=n")
  }

  @Test
  fun stressAndLengthMarksDoNotMakeADifferentSound() {
    val two = word(0, "two", 0.0, "t uː", "t u")
    assertThat(ops(CompareRows.compare(two))).containsExactly("t=t", "uː=u").inOrder()
  }

  @Test
  fun withABoundaryEachWordIsComparedOnItsOwn() {
    // "that" loses its /t/; "time" starts with /t/: it must not stand in for the lost one
    val that = word(0, "that", 0.4, "ð æ t", "ð æ", changes = listOf("t_deletion"), boundaryLinkNext = true)
    val time = word(1, "time", 0.6, "t aɪ m", "t aɪ m")
    val rows = CompareRows.compare(that, time)
    assertThat(ops(rows)).containsExactly("ð=ð", "æ=æ", "t–∅", "t=t", "aɪ=aɪ", "m=m").inOrder()
    assertThat(rows.indexOfFirst { it.nextWord }).isEqualTo(3) // the web's boundaryAt
    assertThat(rows.drop(3).all { it.nextWord }).isTrue()
    // the gap between the words (the web's gapMs): 0.51 to 0.6
    val clip = AnalysisFixtures.clip(listOf(phrase(0, 0.4, 1.0, words = listOf(that, time))))
    val ref = WordRef(clip.jobId, 0, 0)
    assertThat(clip.boundaryGapMs(ref)).isEqualTo(90)
    assertThat(CompareRows.of(clip, ref)).isEqualTo(rows)
  }

  @Test
  fun theClipsRowsIncludeTheNextWordOnlyWhenTheChangeCrossesTheBoundary() {
    val clip = parse(AnalysisFixtures.bytes(Variant.RULES_3))
    val did = CompareRows.of(clip, WordRef(clip.jobId, 0, 0)) // palatalization: "did" with "you"
    assertThat(ops(did)).containsExactly("d=d", "ɪ=ɪ", "d≠dʒ", "j=j", "uː≠ə").inOrder()
    assertThat(did.map { it.nextWord }).containsExactly(false, false, false, true, true).inOrder()
    val water = CompareRows.of(clip, WordRef(clip.jobId, 0, 4)) // flapping stays inside the word
    assertThat(water.none { it.nextWord }).isTrue()
    // "%": nothing expected and nothing heard, so no row; a word that is not in this clip has none either
    assertThat(CompareRows.of(clip, WordRef(clip.jobId, 2, 0))).isEmpty()
    assertThat(CompareRows.of(clip, WordRef(clip.jobId, 9, 0))).isEmpty()
    assertThat(CompareRows.of(clip, WordRef(JobId("another-job"), 0, 0))).isEmpty()
  }
}
