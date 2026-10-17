package com.mapineda48.phonotrainer.core.player.feed

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mapineda48.phonotrainer.core.player.media.ClipSources
import com.mapineda48.phonotrainer.core.player.media.ShortPlan
import java.io.File
import kotlin.math.abs

/**
 * Follower mode (architecture 11.1): for a video whose offset is negative (it starts after the analysed zero, so
 * merging cannot line it up), a second, muted player shows the video and follows the short's audio player, which
 * stays the clock. Video time = analysed time + offset; before the video starts it holds its first frame. It is
 * re-synced when it drifts more than [MAX_DRIFT_MS] (the web's `MAX_DRIFT`).
 */
@OptIn(UnstableApi::class)
internal class FollowerVideo(
  val player: ExoPlayer,
  private val leader: ExoPlayer,
  private val plan: ShortPlan,
  video: File,
  sources: ClipSources,
) {
  init {
    player.volume = 0f
    player.repeatMode = Player.REPEAT_MODE_OFF
    player.setMediaSource(sources.videoOnly(video))
  }

  fun prepare() {
    player.prepare()
    sync()
  }

  fun stop() = player.stop()

  fun release() = player.release()

  /** Mirrors the leader's play state and speed, and seeks when the video has drifted. */
  fun sync() {
    if (player.playbackState == Player.STATE_IDLE) return
    if (player.playbackParameters != leader.playbackParameters) player.playbackParameters = leader.playbackParameters
    val target = plan.playStartMs + leader.currentPosition + plan.videoOffsetMs
    if (target < 0) {
      player.playWhenReady = false
      if (player.currentPosition != 0L) player.seekTo(0)
      return
    }
    if (abs(player.currentPosition - target) > MAX_DRIFT_MS) player.seekTo(target)
    player.playWhenReady = leader.isPlaying
  }

  companion object {
    /** The web's `MAX_DRIFT` (spec F-06 item 6). */
    const val MAX_DRIFT_MS: Long = 200

    /** How often the follower is checked while its short is current. */
    const val SYNC_MS: Long = 50
  }
}
