package com.mapineda48.phonotrainer.core.testing.fake.player

import com.mapineda48.phonotrainer.core.domain.api.AudioArbiter
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Settable fakes of the :core:player interfaces (work-plan 4.6; WP-11 owns this folder after B1).

class FakeSpanPlayer : SpanPlayer {
  val stateFlow = MutableStateFlow(SpanPlaybackState(null, false, 0, 1f, Track.MIX, false, null))
  val played = mutableListOf<ClipSpan>()

  override val state: StateFlow<SpanPlaybackState> = stateFlow

  override suspend fun play(span: ClipSpan, speed: Float, track: Track, loop: Boolean) {
    played += span
    stateFlow.value = SpanPlaybackState(span, true, span.startMs, speed, track, loop, null)
  }

  override fun stop() {
    stateFlow.value = stateFlow.value.copy(playing = false)
  }
}

/** Records every command; [stateFlow] is what the feed observes. */
class FakeFeedPlayer : FeedPlayer {
  val stateFlow = MutableStateFlow(FeedPlaybackState(0, false, 1f, Track.MIX, LoopMode.OFF, false, false, false, false, null))
  val commands = mutableListOf<String>()
  var queue: List<FeedItem> = emptyList()
    private set
  var position = 0L

  override val state: StateFlow<FeedPlaybackState> = stateFlow

  override fun setQueue(items: List<FeedItem>, startIndex: Int) {
    queue = items
    commands += "setQueue:${items.size}:$startIndex"
  }

  override fun settle(index: Int, autoplay: Boolean) {
    commands += "settle:$index:$autoplay"
    stateFlow.value = stateFlow.value.copy(index = index, playing = autoplay)
  }

  override fun play() {
    commands += "play"
    stateFlow.value = stateFlow.value.copy(playing = true)
  }

  override fun pause() {
    commands += "pause"
    stateFlow.value = stateFlow.value.copy(playing = false)
  }

  override fun seekTo(positionMs: Long) {
    commands += "seekTo:$positionMs"
    position = positionMs
  }

  override fun setSpeed(speed: Float) {
    commands += "setSpeed:$speed"
    stateFlow.value = stateFlow.value.copy(speed = speed)
  }

  override fun holdSlow(held: Boolean) {
    commands += "holdSlow:$held"
    stateFlow.value = stateFlow.value.copy(holdSlow = held)
  }

  override fun setTrack(track: Track) {
    commands += "setTrack:$track"
    stateFlow.value = stateFlow.value.copy(track = track)
  }

  override fun setLoop(mode: LoopMode) {
    commands += "setLoop:$mode"
    stateFlow.value = stateFlow.value.copy(loop = mode)
  }

  override fun replayPhrase() {
    commands += "replayPhrase"
  }

  override fun playSpan(span: ClipSpan) {
    commands += "playSpan:${span.startMs}-${span.endMs}"
  }

  override fun positionMs(): Long = position

  override fun release() {
    commands += "release"
  }
}

class FakeShadowingRunner : ShadowingRunner {
  val stateFlow = MutableStateFlow<ShadowingState>(ShadowingState.Idle)
  val runs = mutableListOf<ClipSpan>()

  override val state: StateFlow<ShadowingState> = stateFlow

  override suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track) {
    runs += span
  }

  override fun stop() {
    stateFlow.value = ShadowingState.Idle
  }
}

class FakeAudioArbiter : AudioArbiter {
  val owners = mutableListOf<PlaybackOwner>()

  override fun claim(owner: PlaybackOwner) {
    owners.remove(owner)
    owners += owner
  }

  override fun release(owner: PlaybackOwner) {
    owners.remove(owner)
  }
}

class FakeDecoderCapabilities(
  var decoders: List<DecoderInfo> = emptyList(),
  var playable: (String, Int, Int) -> Boolean = { _, _, _ -> true },
) : DecoderCapabilities {
  override fun canPlay(codec: String, width: Int, height: Int, fps: Double): Boolean = playable(codec, width, height)

  override fun summary(): List<DecoderInfo> = decoders
}
