package com.mapineda48.phonotrainer.core.designsystem.trace

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The trace path builder of design 12.1: runs split at unvoiced gaps, played and rest parts, the ribbon. */
@RunWith(RobolectricTestRunner::class)
class TracePathTest {
  private val samples = listOf(
    TraceSample(0f, 0.5f), TraceSample(0.2f, 0.3f), TraceSample(0.4f, null), TraceSample(0.6f, 0.6f), TraceSample(1f, 0.4f),
  )

  @Test
  fun unvoicedSamplesBreakTheLine() {
    val runs = TracePath.runs(samples)
    assertThat(runs).hasSize(2)
    assertThat(runs[0].map { it.x }).containsExactly(0f, 0.2f).inOrder()
    assertThat(runs[1].map { it.x }).containsExactly(0.6f, 1f).inOrder()
  }

  @Test
  fun playedAndRestMeetAtTheProgress() {
    val run = TracePath.runs(samples)[1]
    val played = TracePath.played(run, 0.8f)!!
    val rest = TracePath.rest(run, 0.8f)!!
    assertThat(played.last().x).isEqualTo(0.8f)
    assertThat(rest.first().x).isEqualTo(0.8f)
    assertThat(played.last().y).isWithin(1e-6f).of(0.5f)
    assertThat(TracePath.played(run, 0.5f)).isNull()
    assertThat(TracePath.rest(run, 1.2f)).isNull()
  }

  @Test
  fun theLineHasNoHeightInsideAGap() {
    assertThat(TracePath.yAt(samples, 0.1f)).isWithin(1e-6f).of(0.4f)
    assertThat(TracePath.yAt(samples, 0.5f)).isNull()
  }

  @Test
  fun theRibbonIsAClosedOutline() {
    val path = TracePath.ribbon(listOf(Offset(0f, 10f), Offset(50f, 10f), Offset(100f, 20f)), listOf(2f, 5f, 3f))
    val bounds = path.getBounds()
    assertThat(bounds.width).isAtLeast(100f)
    assertThat(bounds.height).isAtLeast(10f)
    assertThat(TracePath.ribbon(listOf(Offset(5f, 5f)), listOf(4f)).getBounds().width).isWithin(0.01f).of(4f)
  }
}
