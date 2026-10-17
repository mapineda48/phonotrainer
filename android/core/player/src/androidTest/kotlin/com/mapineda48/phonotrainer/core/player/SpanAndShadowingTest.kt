package com.mapineda48.phonotrainer.core.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.shadowing.PlanShadowingRunner
import com.mapineda48.phonotrainer.core.player.shadowing.ShadowingWords
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness.Media
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.waitFor
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The span player and the shadowing runner on real codecs (architecture 11.5): a span plays its stretch of the
 * chosen track and returns at its end, short spans are stretched to be heard, one sound at a time with the feed, and
 * shadowing alternates sound and silence and gives the speed back.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpanAndShadowingTest {
  private val harness = PlayerHarness()
  private val job = JobId("span")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  @After
  fun tearDown() {
    scope.cancel()
    harness.release()
  }

  private fun timed(block: suspend () -> Unit): Long {
    val start = SystemClock.uptimeMillis()
    runBlocking { withTimeout(TIMEOUT_MS) { block() } }
    return SystemClock.uptimeMillis() - start
  }

  @Test
  fun aSpanPlaysItsStretchOfTheChosenTrackAndReturnsAtItsEnd() {
    harness.content.mediaById[job] = harness.media(job)
    val spans = harness.spanPlayer()

    val took = timed { spans.play(ClipSpan(job, 2_000, 2_800, null), speed = 1f, track = Track.DIALOGUE) }

    assertThat(took).isIn(Range.closed(700L, 1_600L))
    assertThat(harness.heardFirst().dominant(Media.TONES, ms = 300)).isEqualTo(Media.DIALOGUE_HZ)
    with(spans.state.value) {
      assertThat(playing).isFalse()
      assertThat(track).isEqualTo(Track.DIALOGUE)
      assertThat(error).isNull()
    }
  }

  @Test
  fun aVeryShortSpanIsStretchedToAQuarterSecond() {
    harness.content.mediaById[job] = harness.media(job)
    val spans = harness.spanPlayer()

    val took = timed { spans.play(ClipSpan(job, 3_000, 3_040, null)) }

    assertThat(took).isIn(Range.closed(200L, 1_000L))
    assertThat(spans.state.value.positionMs).isIn(Range.closed(2_895L, 3_200L))
  }

  @Test
  fun missingAudioIsReportedAtOnce() {
    val spans = harness.spanPlayer()

    val took = timed { spans.play(ClipSpan(job, 0, 500, null)) }

    assertThat(took).isLessThan(1_000L)
    assertThat(spans.state.value.error).isEqualTo(AppError.Media.AudioMissing)
  }

  @Test
  fun aLoopingSpanPlaysUntilTheFeedTakesTheSound() {
    harness.content.mediaById[job] = harness.media(job)
    harness.clip(job, true)
    val spans = harness.spanPlayer()
    val pool = harness.pool()
    onMain { pool.setQueue(listOf(harness.item(harness.media(job), 0, 6_000)), 0) }
    var returned = false
    val loop = scope.launch {
      spans.play(ClipSpan(job, 1_000, 1_400, null), loop = true)
      returned = true
    }
    Thread.sleep(1_500)
    assertThat(spans.state.value.playing).isTrue()
    assertThat(returned).isFalse()

    onMain { pool.settle(0, autoplay = true) } // one sound at a time

    waitFor("the span to give the sound to the feed") { returned && !spans.state.value.playing }
    waitFor("the feed playing") { pool.positionMs() > 300 }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
    loop.cancel()
    onMain { pool.release() }
  }

  @Test
  fun shadowingAlternatesSoundAndSilenceAndGivesTheSpeedBack() {
    harness.content.mediaById[job] = harness.media(job)
    val spans = harness.spanPlayer()
    val announcer = FakeAnnouncer()
    val runner = onMain {
      PlanShadowingRunner(spans, harness.arbiter, announcer, ShadowingWords(harness.context), PlayerDispatchers(Dispatchers.Main.immediate))
    }
    onMain { spans.setSpeed(0.75f) }
    val phases = mutableListOf<Pair<ShadowingState, Double>>()
    val watcher = scope.launch {
      runner.state.collect { state ->
        if (state is ShadowingState.Running) {
          phases += state to 0.0
        }
      }
    }

    // A 0.6 s span at 0.5×: listens of 1.2 s and silences of 1.6 s three times, then 0.6 s and 1 s at full speed (10 s).
    val took = timed { runner.run(ClipSpan(job, 2_000, 2_600, null), slowSpeed = 0.5f, track = Track.MIX) }

    watcher.cancel()
    assertThat(took).isIn(Range.closed(9_900L, 11_500L))
    assertThat(phases.map { (it.first as ShadowingState.Running).phase.name }).containsExactly(
      "LISTEN", "YOUR_TURN", "LISTEN", "YOUR_TURN", "LISTEN", "YOUR_TURN", "LISTEN", "YOUR_TURN",
    ).inOrder()
    assertThat(announcer.announced).hasSize(8)
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(spans.state.value.speed).isEqualTo(0.75f)
    assertThat(onMain { harness.arbiter.owner.value }).isNull()
  }

  @Test
  fun shadowingIsSilentDuringYourTurn() {
    harness.content.mediaById[job] = harness.media(job)
    val spans = harness.spanPlayer()
    val runner = onMain {
      PlanShadowingRunner(spans, harness.arbiter, FakeAnnouncer(), ShadowingWords(harness.context), PlayerDispatchers(Dispatchers.Main.immediate))
    }
    val run = scope.launch {
      runner.run(ClipSpan(job, 2_000, 3_000, null), slowSpeed = 0.5f, track = Track.MIX)
    }
    // Round 1: listen 0–2 s, your turn 2–4.4 s.
    Thread.sleep(1_500)
    val listening = harness.heardFirst().frames()
    Thread.sleep(400)
    assertThat(harness.heardFirst().frames()).isGreaterThan(listening) // sound goes out while listening
    assertThat(harness.heardFirst().dominant(Media.TONES, ms = 200)).isEqualTo(Media.MIX_HZ)
    Thread.sleep(1_200) // well inside "your turn"
    val silentFrom = harness.heardFirst().frames()
    Thread.sleep(600)
    assertThat(harness.heardFirst().frames() - silentFrom).isLessThan(harness.heardFirst().sampleRate / 20L) // under 50 ms of audio

    onMain { runner.stop() }
    waitFor("the loop to stop") { runner.state.value == ShadowingState.Idle && run.isCompleted }
  }

  private companion object {
    const val TIMEOUT_MS = 20_000L
  }
}
