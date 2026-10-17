package com.mapineda48.phonotrainer.core.player

import com.mapineda48.phonotrainer.core.domain.api.AudioArbiter
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.decoder.MediaCodecDecoderCapabilities
import com.mapineda48.phonotrainer.core.player.decoder.PlatformVideoDecoders
import com.mapineda48.phonotrainer.core.player.decoder.VideoDecoderSource
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.media.DefaultExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.media.ExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.shadowing.PlanShadowingRunner
import com.mapineda48.phonotrainer.core.player.span.ExoSpanPlayer
import com.mapineda48.phonotrainer.core.player.span.SpanEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `:core:player`'s bindings (architecture 11; work-plan 4.2). The arbiter, the span player, the shadowing runner and
 * the decoder list are one per app; [FeedPlayer] is **unscoped**: each Watch ViewModel gets its own pool and releases
 * it in `onCleared` (architecture 11.2).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlayerModule {
  @Binds
  fun arbiter(impl: FocusAudioArbiter): AudioArbiter

  @Binds
  fun spanPlayer(impl: ExoSpanPlayer): SpanPlayer

  @Binds
  fun spanEngine(impl: ExoSpanPlayer): SpanEngine

  @Binds
  fun feedPlayer(impl: FeedPlayerPool): FeedPlayer

  @Binds
  fun shadowing(impl: PlanShadowingRunner): ShadowingRunner

  @Binds
  fun decoders(impl: MediaCodecDecoderCapabilities): DecoderCapabilities

  @Binds
  fun decoderSource(impl: PlatformVideoDecoders): VideoDecoderSource

  @Binds
  fun players(impl: DefaultExoPlayerFactory): ExoPlayerFactory

  @Binds
  fun foreground(impl: ProcessForegroundSignal): ForegroundSignal
}
