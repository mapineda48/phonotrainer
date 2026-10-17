package com.mapineda48.phonotrainer.core.player.shadowing

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import com.mapineda48.phonotrainer.core.player.arbiter.ArbiterClient
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.arbiter.PauseReason
import com.mapineda48.phonotrainer.core.player.span.SpanEngine
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeAnnouncer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Spec F-12 (AC1, AC3, AC4 player side), architecture 11.5: 8 phases, polite announcements, the speed restored. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanShadowingRunnerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val dispatcher = StandardTestDispatcher()
  private val dispatchers = PlayerDispatchers(dispatcher)
  private val arbiter = FocusAudioArbiter(
    audioManager,
    object : ForegroundSignal {
      override val foreground = MutableStateFlow(true)
    },
    dispatchers,
  )
  private val engine = FakeEngine()
  private val announcer = FakeAnnouncer()
  private val runner = PlanShadowingRunner(engine, arbiter, announcer, ShadowingWords(context), dispatchers)
  private val span = ClipSpan(JobId("job"), startMs = 4_000, endMs = 6_000, word = null)

  private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

  @Test
  fun eightPhasesInOrderAnnouncedPolitelyWithThePlansTimings() = test {
    val states = mutableListOf<ShadowingState>()
    val watcher = launch { runner.state.collect { states += it } }
    engine.time = { testScheduler.currentTime }

    runner.run(span, slowSpeed = 0.75f, track = Track.DIALOGUE)

    // F-12-AC1: listens of about 2.67 s (rounds 1 to 3) and 2 s (round 4), each followed by a silence 400 ms longer.
    assertThat(engine.calls).containsExactly(
      "load:4000-6000:DIALOGUE@0",
      "listen:0.75@0", "pause@2667",
      "listen:0.75@5734", "pause@8401",
      "listen:0.75@11468", "pause@14135",
      "listen:1.0@17202", "pause@19202",
      "pause@21602",
    ).inOrder()
    // F-12-AC4: each phase's text, politely.
    assertThat(announcer.announced).containsExactly(
      "Round 1 of 4: listen at 0.75×", "Round 1 of 4: your turn — say it now",
      "Round 2 of 4: listen at 0.75×", "Round 2 of 4: your turn — say it now",
      "Round 3 of 4: listen at 0.75×", "Round 3 of 4: your turn — say it now",
      "Round 4 of 4: listen at full speed", "Round 4 of 4: your turn — say it now",
    ).inOrder()
    runCurrent()
    assertThat(states.filterIsInstance<ShadowingState.Running>().map { "${it.round}/${it.of}:${it.phase}@${it.speed}" })
      .containsExactly(
        "1/4:LISTEN@0.75", "1/4:YOUR_TURN@0.75", "2/4:LISTEN@0.75", "2/4:YOUR_TURN@0.75",
        "3/4:LISTEN@0.75", "3/4:YOUR_TURN@0.75", "4/4:LISTEN@1.0", "4/4:YOUR_TURN@1.0",
      ).inOrder()
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(arbiter.owner.value).isNull()
    watcher.cancel()
  }

  @Test
  fun theSpeedFromBeforeIsRestoredWhenThePlanEnds() = test {
    engine.setSpeed(0.5f)

    runner.run(span, slowSpeed = 0.75f, track = Track.MIX)

    assertThat(engine.speed).isEqualTo(0.5f)
  }

  @Test
  fun stopEndsTheLoopAndRestoresTheSpeed() = test {
    engine.setSpeed(0.5f)
    val run = launch { runner.run(span, slowSpeed = 0.75f, track = Track.MIX) }
    advanceTimeBy(3_000)
    assertThat(runner.state.value).isEqualTo(ShadowingState.Running(1, 4, ShadowingPhase.YOUR_TURN, 0.75f))

    runner.stop()
    advanceUntilIdle()

    assertThat(run.isCompleted).isTrue()
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(engine.speed).isEqualTo(0.5f)
    assertThat(engine.calls.last()).startsWith("pause")
    assertThat(arbiter.owner.value).isNull()
    assertThat(announcer.announced).hasSize(2)
  }

  @Test
  fun aSwipeToAnotherShortStopsItSilently() = test {
    engine.setSpeed(1f)
    val run = launch { runner.run(span, slowSpeed = 0.5f, track = Track.MIX) }
    advanceTimeBy(1_000)
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.SHADOWING)

    // F-12-AC3: the feed's settle interrupts shadowing; a feed claim does the same.
    arbiter.claim(PlaybackOwner.FEED, NoClient)
    advanceUntilIdle()

    assertThat(run.isCompleted).isTrue()
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(engine.speed).isEqualTo(1f)
    assertThat(announcer.announced).containsExactly("Round 1 of 4: listen at 0.5×")
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.FEED)
  }

  @Test
  fun losingAudioFocusToACallStopsWithAnAnnouncement() = test {
    val run = launch { runner.run(span, slowSpeed = 0.75f, track = Track.MIX) }
    advanceTimeBy(500)

    shadowOf(audioManager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    advanceUntilIdle()

    assertThat(run.isCompleted).isTrue()
    assertThat(announcer.announced.last()).isEqualTo("Shadowing stopped.")
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(engine.speed).isEqualTo(1f)
  }

  @Test
  fun aCancelledCallerStopsTheLoop() = test {
    val run = launch { runner.run(span, slowSpeed = 0.75f, track = Track.MIX) }
    advanceTimeBy(4_000)

    run.cancel()
    advanceUntilIdle()

    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(engine.calls.last()).startsWith("pause")
    assertThat(arbiter.owner.value).isNull()
  }

  @Test
  fun withoutTheClipsAudioNothingRuns() = test {
    engine.loads = false

    runner.run(span, slowSpeed = 0.75f, track = Track.MIX)

    assertThat(announcer.announced).isEmpty()
    assertThat(engine.calls.none { it.startsWith("listen") }).isTrue()
    assertThat(runner.state.value).isEqualTo(ShadowingState.Idle)
    assertThat(arbiter.owner.value).isNull()
  }

  @Test
  fun aSlowSpeedOfOneRunsThreeRoundsAtFullSpeed() = test {
    runner.run(span, slowSpeed = 1f, track = Track.MIX)

    assertThat(announcer.announced).containsExactly(
      "Round 1 of 3: listen at full speed", "Round 1 of 3: your turn — say it now",
      "Round 2 of 3: listen at full speed", "Round 2 of 3: your turn — say it now",
      "Round 3 of 3: listen at full speed", "Round 3 of 3: your turn — say it now",
    ).inOrder()
  }

  @Test
  fun aSecondRunReplacesTheFirst() = test {
    val first = launch { runner.run(span, slowSpeed = 0.75f, track = Track.MIX) }
    advanceTimeBy(1_000)
    val other = span.copy(startMs = 0, endMs = 1_000)
    val second = launch { runner.run(other, slowSpeed = 0.5f, track = Track.MIX) }
    advanceTimeBy(100)

    assertThat(first.isCompleted).isTrue()
    assertThat(second.isActive).isTrue()
    assertThat(runner.state.value).isEqualTo(ShadowingState.Running(1, 4, ShadowingPhase.LISTEN, 0.5f))
    second.cancel()
  }

  private object NoClient : ArbiterClient {
    override fun onArbiterPause(reason: PauseReason) = Unit

    override fun onArbiterVolume(volume: Float) = Unit
  }

  private class FakeEngine : SpanEngine {
    private var current = 1f
    override val speed: Float get() = current
    var loads = true
    var time: () -> Long = { 0 }
    val calls = mutableListOf<String>()

    override fun setSpeed(speed: Float) {
      current = speed
    }

    override suspend fun load(span: ClipSpan, track: Track): Boolean {
      calls += "load:${span.startMs}-${span.endMs}:$track@${time()}"
      return loads
    }

    override fun listen(rate: Float) {
      current = rate
      calls += "listen:$rate@${time()}"
    }

    override fun pause() {
      calls += "pause@${time()}"
    }

    override fun setVolume(volume: Float) = Unit
  }
}
