package com.mapineda48.phonotrainer.core.ui.component

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Family
import org.junit.Test

/** What an example plays and says (web `clipPadding`, `displayWord`, `stripStress`). */
class OccurrenceRowPlaybackTest {
  private val water = PreviewData.occurrence

  @Test
  fun aWordPlaysWithALittleRoomAroundIt() {
    val span = OccurrenceRowPlayback.span(water)
    assertThat(span.jobId).isEqualTo(water.word.jobId)
    assertThat(span.word).isEqualTo(water.word)
    assertThat(span.startMs).isEqualTo(water.startMs - OccurrenceRowPlayback.PAD_BEFORE_MS)
    assertThat(span.endMs).isEqualTo(water.endMs + OccurrenceRowPlayback.PAD_AFTER_MS)
  }

  @Test
  fun aBoundaryChangeRunsIntoTheNextWordAndNeverStartsBeforeTheClip() {
    val linked = water.copy(startMs = 50, family = Family.BOUNDARY)
    val span = OccurrenceRowPlayback.span(linked)
    assertThat(span.startMs).isEqualTo(0)
    assertThat(span.endMs).isEqualTo(linked.endMs + OccurrenceRowPlayback.BOUNDARY_PAD_AFTER_MS)
    val crossing = water.copy(crossesBoundary = true, nextWordText = "you")
    assertThat(OccurrenceRowPlayback.span(crossing).endMs).isEqualTo(crossing.endMs + OccurrenceRowPlayback.BOUNDARY_PAD_AFTER_MS)
    // Crossing with no next word (the clip's last word) shows and plays the word alone.
    assertThat(OccurrenceRowPlayback.runsIntoNext(water.copy(crossesBoundary = true, nextWordText = null))).isFalse()
  }

  @Test
  fun theWordsAndFormsAreShownAsTheWebShowsThem() {
    assertThat(OccurrenceRowPlayback.displayWord("water?")).isEqualTo("water")
    assertThat(OccurrenceRowPlayback.displayWord("“don't,”")).isEqualTo("don't")
    assertThat(OccurrenceRowPlayback.displayWord("%")).isEqualTo("%")
    assertThat(OccurrenceRowPlayback.dictionaryForm(water.copy(dictIpa = "ˈwɔtɚ"))).isEqualTo("wɔtɚ")
    assertThat(OccurrenceRowPlayback.dictionaryForm(water.copy(dictIpa = " "))).isNull()
    assertThat(OccurrenceRowPlayback.saidForm(water.copy(realizedIpa = null))).isEqualTo("∅")
  }
}
