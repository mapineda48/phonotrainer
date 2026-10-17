package com.mapineda48.phonotrainer.core.player.fakes

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeAudioArbiter
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeDecoderCapabilities
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeFeedPlayer
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeShadowingRunner
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.player.ShadowingRun
import com.mapineda48.phonotrainer.core.testing.fake.player.SpanPlay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The settable fakes of `:core:testing-jvm` `fake/player/` (work-plan 4.6) behave as feature tests expect. */
class PlayerFakesTest {
  private val span = ClipSpan(JobId("job"), 1_000, 1_400, null)

  @Test
  fun theFeedPlayerRecordsCommandsAndShowsWhatTheTestSets() {
    val player = FakeFeedPlayer()

    player.settle(3, autoplay = true)
    player.setSpeed(0.5f)
    player.setLoop(LoopMode.PHRASE)
    player.seekTo(12_000)
    player.setVisible(false)
    player.stateFlow.value = player.stateFlow.value.copy(trackFallback = TrackFallback.DIALOGUE_NOT_ON_PHONE)

    assertThat(player.commands).containsExactly(
      "settle:3:true", "setSpeed:0.5", "setLoop:PHRASE", "seekTo:12000", "setVisible:false",
    ).inOrder()
    assertThat(player.positionMs()).isEqualTo(12_000)
    assertThat(player.visibility).containsExactly(false)
    with(player.state.value) {
      assertThat(index).isEqualTo(3)
      assertThat(playing).isFalse()
      assertThat(speed).isEqualTo(0.5f)
      assertThat(loop).isEqualTo(LoopMode.PHRASE)
      assertThat(trackFallback).isEqualTo(TrackFallback.DIALOGUE_NOT_ON_PHONE)
    }
  }

  @Test
  fun theSpanPlayerAndTheShadowingRunnerRecordTheirCalls() = runTest {
    val spans = FakeSpanPlayer()
    val shadowing = FakeShadowingRunner()

    spans.play(span, speed = 0.75f, track = Track.DIALOGUE, loop = true)
    spans.stop()
    shadowing.run(span, slowSpeed = 0.5f, track = Track.MIX)
    shadowing.stateFlow.value = ShadowingState.Running(2, 4, com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase.LISTEN, 0.5f)
    shadowing.stop()

    assertThat(spans.calls).containsExactly(SpanPlay(span, 0.75f, Track.DIALOGUE, true))
    assertThat(spans.stops).isEqualTo(1)
    assertThat(spans.state.value.playing).isFalse()
    assertThat(shadowing.calls).containsExactly(ShadowingRun(span, 0.5f, Track.MIX))
    assertThat(shadowing.stops).isEqualTo(1)
    assertThat(shadowing.state.value).isEqualTo(ShadowingState.Idle)
  }

  @Test
  fun theArbiterKeepsTheLastClaimLast() {
    val arbiter = FakeAudioArbiter()

    arbiter.claim(PlaybackOwner.FEED)
    arbiter.claim(PlaybackOwner.SPAN)
    arbiter.claim(PlaybackOwner.FEED)
    arbiter.release(PlaybackOwner.SPAN)

    assertThat(arbiter.owners).containsExactly(PlaybackOwner.FEED)
  }

  @Test
  fun decoderCapabilitiesAnswerWhatTheTestSets() {
    val caps = FakeDecoderCapabilities(decoders = listOf(DecoderInfo("video/avc", 1920, 1080, 60.0, true)))
    caps.playable = { codec, _, height -> codec == "h264" && height <= 1080 }

    assertThat(caps.canPlay("h264", 1920, 1080)).isTrue()
    assertThat(caps.canPlay("av1", 1920, 1080)).isFalse()
    assertThat(caps.summary()).hasSize(1)
  }
}
