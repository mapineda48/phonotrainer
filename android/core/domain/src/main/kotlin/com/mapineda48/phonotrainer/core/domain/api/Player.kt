package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

// :core:player (WP-11), architecture 3.4 and 11; work-plan 4.2 for FeedPlayer and ShadowingRunner. The composable
// surface (`PlayerSurface(feedPlayer, index, modifier)`) lives in :core:player's `android` package.

/** Lessons, practice, Learn examples, end cards. */
interface SpanPlayer {
  val state: StateFlow<SpanPlaybackState>

  suspend fun play(span: ClipSpan, speed: Float = 1f, track: Track = Track.MIX, loop: Boolean = false)

  fun stop()
}

/** One sound at a time. */
interface AudioArbiter {
  fun claim(owner: PlaybackOwner)

  fun release(owner: PlaybackOwner)
}

interface DecoderCapabilities {
  fun canPlay(codec: String, width: Int, height: Int, fps: Double = 30.0): Boolean

  /** Connection details and QA (architecture 11.7): codec, max size, hardware or software. */
  fun summary(): List<DecoderInfo>
}

/** :core:player (WP-11) → :feature:watch (WP-17). */
interface FeedPlayer {
  /** Index, playing, speed, track, loop, ended, holdSlow, videoShown, error. */
  val state: StateFlow<FeedPlaybackState>

  fun setQueue(items: List<FeedItem>, startIndex: Int)

  /** Prepares n-1, n, n+1 (pool of 3); plays n when [autoplay]. */
  fun settle(index: Int, autoplay: Boolean)

  fun play()

  fun pause()

  fun seekTo(positionMs: Long)

  fun setSpeed(speed: Float)

  fun holdSlow(held: Boolean)

  fun setTrack(track: Track)

  /** OFF, PHRASE, SHORT. */
  fun setLoop(mode: LoopMode)

  fun replayPhrase()

  fun playSpan(span: ClipSpan)

  /** Read every frame for captions (architecture 11.3). */
  fun positionMs(): Long

  fun release()
}

/** :core:player (WP-11) → lesson (WP-18), watch (WP-17). */
interface ShadowingRunner {
  /** Idle, or round k of n, phase LISTEN/YOUR_TURN, speed. */
  val state: StateFlow<ShadowingState>

  suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track)

  /** Restores the previous speed. */
  fun stop()
}
