package com.mapineda48.phonotrainer.core.player

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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Working stubs of the contract freeze (work-plan 2.3): nothing plays. WP-11 replaces this file.

class StubSpanPlayer @Inject constructor() : SpanPlayer {
  override val state: StateFlow<SpanPlaybackState> = MutableStateFlow(
    SpanPlaybackState(span = null, playing = false, positionMs = 0, speed = 1f, track = Track.MIX, loop = false, error = null),
  )

  override suspend fun play(span: ClipSpan, speed: Float, track: Track, loop: Boolean) = Unit

  override fun stop() = Unit
}

class StubFeedPlayer @Inject constructor() : FeedPlayer {
  override val state: StateFlow<FeedPlaybackState> = MutableStateFlow(
    FeedPlaybackState(
      index = 0,
      playing = false,
      speed = 1f,
      track = Track.MIX,
      loop = LoopMode.OFF,
      ended = false,
      holdSlow = false,
      videoShown = false,
      firstFrameRendered = false,
      error = null,
    ),
  )

  override fun setQueue(items: List<FeedItem>, startIndex: Int) = Unit

  override fun settle(index: Int, autoplay: Boolean) = Unit

  override fun play() = Unit

  override fun pause() = Unit

  override fun seekTo(positionMs: Long) = Unit

  override fun setSpeed(speed: Float) = Unit

  override fun holdSlow(held: Boolean) = Unit

  override fun setTrack(track: Track) = Unit

  override fun setLoop(mode: LoopMode) = Unit

  override fun replayPhrase() = Unit

  override fun playSpan(span: ClipSpan) = Unit

  override fun positionMs(): Long = 0

  override fun release() = Unit
}

class StubShadowingRunner @Inject constructor() : ShadowingRunner {
  override val state: StateFlow<ShadowingState> = MutableStateFlow(ShadowingState.Idle)

  override suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track) = Unit

  override fun stop() = Unit
}

class StubAudioArbiter @Inject constructor() : AudioArbiter {
  override fun claim(owner: PlaybackOwner) = Unit

  override fun release(owner: PlaybackOwner) = Unit
}

class StubDecoderCapabilities @Inject constructor() : DecoderCapabilities {
  override fun canPlay(codec: String, width: Int, height: Int, fps: Double): Boolean = false

  override fun summary(): List<DecoderInfo> = emptyList()
}

@Module
@InstallIn(SingletonComponent::class)
interface PlayerModule {
  @Binds
  fun spanPlayer(impl: StubSpanPlayer): SpanPlayer

  @Binds
  fun feedPlayer(impl: StubFeedPlayer): FeedPlayer

  @Binds
  fun shadowing(impl: StubShadowingRunner): ShadowingRunner

  @Binds
  fun arbiter(impl: StubAudioArbiter): AudioArbiter

  @Binds
  fun decoders(impl: StubDecoderCapabilities): DecoderCapabilities
}
