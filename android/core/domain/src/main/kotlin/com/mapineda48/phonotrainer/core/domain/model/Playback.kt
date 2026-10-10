package com.mapineda48.phonotrainer.core.domain.model

// Work-plan Appendix A.7: playback (WP-11, WP-17, WP-18, WP-30).

data class SpanPlaybackState(
  val span: ClipSpan?,
  val playing: Boolean,
  val positionMs: Long,
  val speed: Float,
  val track: Track,
  val loop: Boolean,
  val error: AppError.Media?,
)

data class FeedPlaybackState(
  val index: Int,
  val playing: Boolean,
  val speed: Float,
  val track: Track,
  val loop: LoopMode,
  val ended: Boolean,
  val holdSlow: Boolean,
  val videoShown: Boolean,
  val firstFrameRendered: Boolean,
  val error: AppError.Media?,
  /** The other track plays, and why (architecture 11.1, spec D-47). */
  val trackFallback: TrackFallback? = null,
)

enum class TrackFallback { DIALOGUE_NOT_ON_PHONE, MIX_NOT_ON_PHONE, NO_DIALOGUE_TRACK }

sealed interface ShadowingState {
  data object Idle : ShadowingState

  data class Running(val round: Int, val of: Int, val phase: ShadowingPhase, val speed: Float) : ShadowingState
}

enum class ShadowingPhase { LISTEN, YOUR_TURN }

enum class PlaybackOwner { FEED, SPAN, QUIZ, RECORD_AB, SHADOWING }

data class DecoderInfo(val mime: String, val maxWidth: Int, val maxHeight: Int, val maxFps: Double?, val hardware: Boolean)

enum class MouthMode { FOLLOW_AUDIO, STEP, STILL }

data class RecordTarget(val word: WordRef?, val span: ClipSpan, val phrase: Boolean)
