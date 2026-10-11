package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Shorts v1 (spec F-06 item 1, architecture 9.2): the F-06-AC1 golden case, edge cases and the F-06-AC2 properties. */
class ShortsV1Test {
  private fun phrases(vararg bounds: Pair<Double, Double>) =
    bounds.mapIndexed { i, (start, end) -> PhraseBounds(i, start, end) }

  @Test
  fun theRuleVersionIsOne() {
    assertThat(ShortsV1.RULE_VERSION).isEqualTo(1)
  }

  @Test
  fun f06Ac1GoldenCase() {
    // A 26 s clip: P0 0–4, P1 4.3–11, P2 11.8–14, P3 14.2–20, P4 23.5–25
    val shorts = ShortsV1.derive(
      phrases(0.0 to 4.0, 4.3 to 11.0, 11.8 to 14.0, 14.2 to 20.0, 23.5 to 25.0),
      clipEndS = 26.0,
    )
    assertThat(shorts).hasSize(2)
    val (first, second) = shorts
    assertThat(first.firstSegment to first.lastSegment).isEqualTo(0 to 1)
    assertThat(first.playStartMs to first.playEndMs).isEqualTo(0L to 11_400L)
    assertThat(second.firstSegment to second.lastSegment).isEqualTo(2 to 4)
    assertThat(second.playStartMs to second.playEndMs).isEqualTo(11_550L to 25_400L)
    assertThat(second.startMs to second.endMs).isEqualTo(11_800L to 25_000L)
    assertThat(second.phraseCount).isEqualTo(3)
    assertThat(3 in second).isTrue()
    assertThat(1 in second).isFalse()
    assertThat(second.id(AnalysisFixtures.JOB).firstSegment).isEqualTo(2)
  }

  @Test
  fun theGoldenCaseThroughTheParser() {
    val json = AnalysisFixtures.timeline(
      listOf(0.0 to 4.0, 4.3 to 11.0, 11.8 to 14.0, 14.2 to 20.0, 23.5 to 25.0),
      durationS = 26.0,
    )
    val shorts = ShortsV1.derive(parse(AnalysisFixtures.encode(json)))
    assertThat(shorts.map { it.playStartMs to it.playEndMs }).containsExactly(0L to 11_400L, 11_550L to 25_400L)
      .inOrder()
  }

  @Test
  fun noPhraseNoShort() {
    assertThat(ShortsV1.derive(emptyList(), clipEndS = 10.0)).isEmpty()
  }

  @Test
  fun aSinglePhraseIsOneShortWithClampedPlayBounds() {
    val short = ShortsV1.derive(phrases(0.1 to 2.0), clipEndS = 2.2).single()
    assertThat(short.playStartMs).isEqualTo(0)
    assertThat(short.playEndMs).isEqualTo(2_200)
  }

  @Test
  fun aClipEndBeforeTheEndOfSpeechNeverCutsAPhrase() {
    val short = ShortsV1.derive(phrases(1.0 to 5.0), clipEndS = 4.0).single()
    assertThat(short.playEndMs).isEqualTo(5_000)
  }

  @Test
  fun aPhraseLongerThanThirtySecondsStaysWhole() {
    val shorts = ShortsV1.derive(phrases(0.0 to 3.0, 3.2 to 40.0, 40.1 to 42.0), clipEndS = 45.0)
    // P0 cannot join P1 (40 s), P2 (1.9 s) joins P1 only if the union fits: 42 - 3.2 = 38.8 > 30, so it stays
    assertThat(shorts.map { it.firstSegment to it.lastSegment }).containsExactly(0 to 0, 1 to 1, 2 to 2).inOrder()
  }

  @Test
  fun aShortPauseClosesAShortThatIsAlreadyTenSeconds() {
    // 0–10 then a 0.6 s pause: closed; a 0.5 s pause would not close it
    val closed = ShortsV1.derive(phrases(0.0 to 10.0, 10.6 to 16.0), clipEndS = 16.0)
    assertThat(closed.map { it.firstSegment }).containsExactly(0, 1).inOrder()
    val open = ShortsV1.derive(phrases(0.0 to 10.0, 10.5 to 16.0), clipEndS = 16.0)
    assertThat(open.map { it.firstSegment }).containsExactly(0)
  }

  @Test
  fun aLongPauseClosesAnyShortAndOverlapsAreNoPause() {
    val shorts = ShortsV1.derive(
      phrases(0.0 to 6.0, 9.0 to 15.0, 14.5 to 16.0),
      clipEndS = 16.0,
    )
    assertThat(shorts.map { it.firstSegment to it.lastSegment }).containsExactly(0 to 0, 1 to 2).inOrder()
  }

  @Test
  fun aShortUnderFiveSecondsJoinsTheNextWhenThePreviousIsFull() {
    // pass 1: {P0} (28 s), {P1} (2 s, after a 3 s pause), {P2,P3}; P1 cannot join P0 (33 s) but joins the next
    val shorts = ShortsV1.derive(
      phrases(0.0 to 28.0, 31.0 to 33.0, 36.5 to 40.0, 40.2 to 45.0),
      clipEndS = 45.0,
    )
    assertThat(shorts.map { it.firstSegment to it.lastSegment }).containsExactly(0 to 0, 1 to 3).inOrder()
  }

  @Test
  fun aShortUnderFiveSecondsStaysWhenNeitherNeighbourHasRoom() {
    val shorts = ShortsV1.derive(phrases(0.0 to 28.0, 31.0 to 33.0, 36.0 to 63.0), clipEndS = 63.0)
    assertThat(shorts.map { it.lengthMs }).containsExactly(28_000L, 2_000L, 27_000L).inOrder()
  }

  @Test
  fun aPhraseThatWouldPassThirtySecondsStartsANewShort() {
    val shorts = ShortsV1.derive(phrases(0.0 to 8.0, 8.1 to 16.0, 16.1 to 24.0, 24.1 to 31.0), clipEndS = 31.0)
    assertThat(shorts.map { it.firstSegment to it.lastSegment }).containsExactly(0 to 2, 3 to 3).inOrder()
  }

  @Test
  fun f06Ac2PropertiesOverSyntheticTimings() {
    val random = Random(20261010)
    repeat(CLIPS) { clip ->
      val phrases = syntheticPhrases(random, count = 1 + random.nextInt(MAX_PHRASES))
      val clipEnd = phrases.last().endS + random.nextDouble() * 2
      val shorts = ShortsV1.derive(phrases, clipEnd)
      assertProperties("clip $clip", phrases, clipEnd, shorts)
      assertWithMessage("clip $clip is deterministic").that(ShortsV1.derive(phrases, clipEnd)).isEqualTo(shorts)
    }
  }

  @Test
  fun theLargeSyntheticClipKeepsTheProperties() {
    val clip = parse(AnalysisFixtures.syntheticBytes())
    val phrases = clip.phrases.map { PhraseBounds(it.index, it.startS, it.endS) }
    val shorts = ShortsV1.derive(clip)
    assertProperties("synthetic", phrases, clip.meta.durationS, shorts)
    assertThat(shorts.size).isAtLeast(20)
  }

  private fun assertProperties(name: String, phrases: List<PhraseBounds>, clipEnd: Double, shorts: List<ShortBounds>) {
    val message = assertWithMessage(name)
    // every phrase in exactly one short, in order: no phrase is split, dropped or repeated
    message.that(shorts.first().firstSegment).isEqualTo(0)
    message.that(shorts.last().lastSegment).isEqualTo(phrases.size - 1)
    shorts.zipWithNext().forEach { (a, b) -> message.that(b.firstSegment).isEqualTo(a.lastSegment + 1) }
    val clipEndMs = toMs(clipEnd)
    for ((i, short) in shorts.withIndex()) {
      message.that(short.lastSegment).isAtLeast(short.firstSegment)
      message.that(short.startMs).isEqualTo(toMs(phrases[short.firstSegment].startS))
      message.that(short.endMs).isEqualTo(toMs(phrases[short.lastSegment].endS))
      // no short over 30 s unless it is a single phrase
      if (short.phraseCount > 1) message.that(short.lengthMs).isAtMost(ShortsV1.MAX_MS)
      // a short under 5 s is left only when neither neighbour has room for it
      if (short.lengthMs < ShortsV1.MIN_MS) {
        shorts.getOrNull(i - 1)?.let { message.that(short.endMs - it.startMs).isGreaterThan(ShortsV1.MAX_MS) }
        shorts.getOrNull(i + 1)?.let { message.that(it.endMs - short.startMs).isGreaterThan(ShortsV1.MAX_MS) }
      }
      message.that(short.playStartMs).isEqualTo(max(0L, short.startMs - ShortsV1.PAD_BEFORE_MS))
      message.that(short.playEndMs).isEqualTo(max(short.endMs, min(clipEndMs, short.endMs + ShortsV1.PAD_AFTER_MS)))
    }
  }

  /**
   * Phrase timings with the measured distribution (spec F-06-AC2: median 1.3–3.8 s, longest 12.4 s), plus a rare
   * phrase over 30 s, and pauses from overlaps to several seconds; times have the analysis's millisecond precision.
   */
  private fun syntheticPhrases(random: Random, count: Int): List<PhraseBounds> {
    var cursor = random.nextDouble() * 3
    var lastEnd = 0.0
    return List(count) { index ->
      val drawn = if (random.nextInt(200) == 0) {
        30.0 + random.nextDouble() * 20
      } else {
        exp(0.9 + random.nextGaussian() * 0.6).coerceIn(0.2, 12.4)
      }
      // an overlapping segment still ends after the one before it, as Whisper's do
      val length = max(drawn, lastEnd - cursor + 0.05)
      val bounds = PhraseBounds(index, millis(cursor), millis(cursor + length))
      lastEnd = bounds.endS
      val roll = random.nextInt(100)
      val pause = when {
        roll < 10 -> -random.nextDouble() * 0.4
        roll < 55 -> random.nextDouble() * 0.6
        roll < 90 -> 0.6 + random.nextDouble() * 2.4
        else -> 3.0 + random.nextDouble() * 6
      }
      cursor = max(bounds.startS, bounds.endS + pause)
      bounds
    }
  }

  private fun millis(seconds: Double): Double = Math.round(seconds * 1000.0) / 1000.0

  private companion object {
    const val CLIPS = 1_000
    const val MAX_PHRASES = 150
  }
}
