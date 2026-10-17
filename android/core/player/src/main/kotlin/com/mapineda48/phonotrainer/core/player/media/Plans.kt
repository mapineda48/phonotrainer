package com.mapineda48.phonotrainer.core.player.media

import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

/** How a short shows its video (architecture 11.1). */
internal enum class VideoMode {
  /** Audio only: no video on the phone, an audio-only clip, a video this phone cannot decode or one that failed. */
  NONE,

  /** One player, the analysed audio merged with the video: one timeline and one clock. */
  MERGED,

  /** A negative offset (the video starts after the analysed zero): a second, muted player follows the audio. */
  FOLLOWER,
}

/** Everything a slot needs to play one short; equal plans play the same thing. */
internal data class ShortPlan(
  val shortId: ShortId,
  /** Play bounds on the analysed timeline (spec F-06 item 1.4); the player's position 0 is [playStartMs]. */
  val playStartMs: Long,
  val playEndMs: Long,
  /** The analysed audio file that plays, and which track it is. */
  val audio: File,
  val playedTrack: Track,
  /** The other track plays, and why (spec D-47); null when the chosen one plays. */
  val trackFallback: TrackFallback?,
  val video: File?,
  /** Video time = analysed time + offset (server-contract 6.3 `start_offset_ms`). */
  val videoOffsetMs: Long,
  val videoMode: VideoMode,
) {
  /** Plays the same files the same way as [other]; only the fallback line may differ. */
  fun sameMedia(other: ShortPlan?): Boolean = other != null && copy(trackFallback = null) == other.copy(trackFallback = null)
}

/**
 * The pure part of preparing a short (architecture 11.1): which audio file, which fallback line, which video mode.
 *
 * Track fallback (spec F-06 Partial, D-47): the chosen track's file plays when it is on the phone; otherwise the
 * other one, with [TrackFallback.DIALOGUE_NOT_ON_PHONE] (dialogue chosen, its file not downloaded yet),
 * [TrackFallback.MIX_NOT_ON_PHONE] (mix chosen, only the dialogue present: [ClipMedia.audioMix] then holds the
 * dialogue file, WP-10's reading of the frozen type) or [TrackFallback.NO_DIALOGUE_TRACK] (dialogue chosen, the clip
 * was analysed without one). Which of the two dialogue cases it is comes from the clip's analysis
 * (`meta.dialogue_separation.applied`); [hasDialogueTrack] is null until it is known, and so is the line.
 */
internal object ShortPlanner {
  fun plan(
    item: FeedItem,
    chosen: Track,
    hasDialogueTrack: Boolean?,
    videoFailed: Boolean,
    canPlayVideo: (ClipMedia) -> Boolean,
  ): ShortPlan {
    val media = item.media
    val dialogue = media.audioDialogue
    val mixOnPhone = dialogue == null || media.audioMix != dialogue
    val (audio, played, fallback) = when {
      chosen == Track.MIX && mixOnPhone -> Triple(media.audioMix, Track.MIX, null)
      chosen == Track.MIX -> Triple(media.audioMix, Track.DIALOGUE, TrackFallback.MIX_NOT_ON_PHONE)
      dialogue != null -> Triple(dialogue, Track.DIALOGUE, null)
      else -> Triple(
        media.audioMix,
        Track.MIX,
        when (hasDialogueTrack) {
          true -> TrackFallback.DIALOGUE_NOT_ON_PHONE
          false -> TrackFallback.NO_DIALOGUE_TRACK
          null -> null
        },
      )
    }
    val start = max(0L, item.short.playStartMs)
    val end = max(start + 1, item.short.playEndMs)
    val video = media.videoFile
    val mode = when {
      video == null || videoFailed -> VideoMode.NONE
      media.videoAvailability == VideoAvailability.AudioOnlyClip -> VideoMode.NONE
      media.videoAvailability == VideoAvailability.CannotPlayOnThisPhone -> VideoMode.NONE
      !canPlayVideo(media) -> VideoMode.NONE
      media.videoOffsetMs < 0 -> VideoMode.FOLLOWER
      else -> VideoMode.MERGED
    }
    return ShortPlan(
      shortId = item.short.id,
      playStartMs = start,
      playEndMs = end,
      audio = audio,
      playedTrack = played,
      trackFallback = fallback,
      video = video.takeIf { mode != VideoMode.NONE },
      videoOffsetMs = media.videoOffsetMs,
      videoMode = mode,
    )
  }
}

/** A span's bounds stretched so it can be heard (web `wordSpan`, architecture 11.5). */
internal object SpanBounds {
  /** Anything shorter than this is inaudible: some words last one or two CTC frames (web `MIN_AUDIBLE`). */
  const val MIN_AUDIBLE_MS: Long = 250

  /** [startMs]..[endMs], or [MIN_AUDIBLE_MS] around its middle when shorter, never before 0. */
  fun audible(startMs: Long, endMs: Long): LongRange {
    val start = max(0L, startMs)
    val end = max(start, endMs)
    if (end - start >= MIN_AUDIBLE_MS) return start..end
    val from = max(0L, (start + end) / 2 - MIN_AUDIBLE_MS / 2)
    return from..from + MIN_AUDIBLE_MS
  }
}

/** One phrase of a short on the analysed timeline, in ms. */
internal data class PhraseSpan(val startMs: Long, val endMs: Long)

/** The phrases of a short, for "Replay" and "Loop: Phrase" (architecture 11.1). */
internal object PhraseSpans {
  /** Every phrase of the clip, in segment order (`result[segment]`). */
  fun ofClip(data: ClipData): List<PhraseSpan> = data.phrases.map {
    PhraseSpan((it.startS * MS).roundToLong(), (max(it.startS, it.endS) * MS).roundToLong())
  }

  /** The phrases of [short], its first to its last segment, from a clip's [ofClip]. */
  fun of(clip: List<PhraseSpan>, short: ShortInfo): List<PhraseSpan> =
    (short.id.firstSegment..short.lastSegment).mapNotNull { clip.getOrNull(it) }

  /**
   * The phrase being played at [positionMs]: the last one that started at or before it (so a pause between phrases
   * belongs to the phrase before), else the first; null when there are none.
   */
  fun at(phrases: List<PhraseSpan>, positionMs: Long): PhraseSpan? =
    phrases.lastOrNull { it.startMs <= positionMs + TOLERANCE_MS } ?: phrases.firstOrNull()

  private const val MS = 1000.0

  /** A seek lands a little before a phrase start; this keeps "Replay" right after it on that phrase. */
  private const val TOLERANCE_MS = 30L
}
