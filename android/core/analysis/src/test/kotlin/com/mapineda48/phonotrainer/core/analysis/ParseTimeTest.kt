package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import org.junit.Test

/**
 * NFR-02's parse budget (architecture 9.1): an analysis the size of the largest real one (1,500 words, 230 phrases,
 * about 4 MB pretty-printed) parses well under 1.5 s. This JVM run guards against an accidental slow path; the
 * budget on the phone itself is measured by WP-19 and WP-25.
 */
class ParseTimeTest {
  @Test
  fun aFifteenHundredWordAnalysisParsesWellUnderTheBudget() {
    val bytes = AnalysisFixtures.syntheticBytes(words = 1_500, phrases = 230)
    assertThat(bytes.size).isGreaterThan(3_000_000)

    val clip = parse(bytes)
    assertThat(clip.phrases).hasSize(230)
    assertThat(clip.wordCount).isEqualTo(1_500)
    assertThat(clip.phrases.sumOf { it.f0?.size ?: 0 }).isGreaterThan(30_000)

    repeat(WARM_UP) { parse(bytes) }
    val times = List(RUNS) {
      val start = System.nanoTime()
      parse(bytes)
      (System.nanoTime() - start) / 1_000_000
    }.sorted()
    val median = times[RUNS / 2]
    println("Parsed ${bytes.size} bytes (1,500 words, 230 phrases) in ${times.joinToString()} ms; median $median ms")
    assertThat(median).isLessThan(BUDGET_MS)
  }

  private companion object {
    const val WARM_UP = 2
    const val RUNS = 5
    const val BUDGET_MS = 1_500L
  }
}
