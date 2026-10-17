package com.mapineda48.phonotrainer.core.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.feed.FollowerVideo
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness.Media
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.qa
import com.mapineda48.phonotrainer.core.player.support.waitFor
import com.mapineda48.phonotrainer.core.testing.synthetic.FrameCode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video follows the analysed audio (work-plan WP-11 Proof; architecture 11.1, 24): every frame the player renders
 * carries its index, so its moment in the video is known; the frame's release time is compared with the audio clock
 * the captions read. Merged playback must stay within 60 ms (else the follower fallback would be enabled); follower
 * mode, for negative offsets, within its 0.2 s re-sync plus a frame.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AvSyncTest {
  private val harness = PlayerHarness()
  private val job = JobId("sync")
  private lateinit var pool: FeedPlayerPool

  @After
  fun tearDown() {
    if (::pool.isInitialized) onMain { pool.release() }
    harness.release()
  }

  @Test
  fun mergedPlaybackStartsAtTheShortsStartAndStaysInSync() = check(offsetMs = 0, startMs = 1_000, maxDriftMs = 60)

  @Test
  fun aPositiveStartOffsetIsSkippedAndStaysInSync() = check(offsetMs = 500, startMs = 2_000, maxDriftMs = 60)

  /** The follower is re-synced once it drifts past 0.2 s, so it may be that far off plus a frame and a check. */
  @Test
  fun aNegativeOffsetIsFollowedWithinTheResyncLimit() =
    check(offsetMs = -400, startMs = 2_000, maxDriftMs = FollowerVideo.MAX_DRIFT_MS + FRAME_MS + FollowerVideo.SYNC_MS)

  private fun check(offsetMs: Long, startMs: Long, maxDriftMs: Long) {
    harness.clip(job, true)
    pool = harness.pool()
    val spec = Media.VIDEO
    val item = harness.item(harness.media(job, offsetMs = offsetMs), startMs, startMs + 5_000)
    val frames = CopyOnWriteArrayList<Pair<Long, Int>>() // release time (System.nanoTime), frame index
    val thread = HandlerThread("frames").apply { start() }
    val reader = ImageReader.newInstance(spec.width, spec.height, ImageFormat.YUV_420_888, IMAGES)
    reader.setOnImageAvailableListener(
      { r -> r.acquireNextImage()?.use { frames += it.timestamp to FrameCode.read(it) } },
      Handler(thread.looper),
    )
    // The reader is the video's output before its decoder starts: a CPU-read surface swapped in later would receive
    // buffers allocated for the placeholder surface, which it cannot read back.
    onMain {
      pool.setQueue(listOf(item), 0)
      pool.settle(0, autoplay = false)
      (pool.surface.value!!.player as ExoPlayer).setVideoSurface(reader.surface)
    }
    waitFor("the short prepared") { (pool.surface.value?.player as? ExoPlayer)?.playbackState == Player.STATE_READY }
    val clock = CopyOnWriteArrayList<Pair<Long, Long>>() // System.nanoTime, analysed position
    var sampling = true
    val sampler = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        if (!sampling) return
        if (pool.state.value.playing) clock += System.nanoTime() to pool.positionMs()
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
    onMain {
      Choreographer.getInstance().postFrameCallback(sampler)
      pool.play()
    }
    Thread.sleep(PLAY_MS)
    onMain {
      sampling = false
      pool.pause()
      (pool.surface.value?.player as? ExoPlayer)?.clearVideoSurface()
    }
    reader.close()
    thread.quitSafely()

    assertThat(clock.size).isGreaterThan(MIN_SAMPLES)
    val playStartNs = clock.first().first
    val playEndNs = clock.last().first
    val drifts = frames
      .filter { (ns, _) -> ns > playStartNs + WARM_UP_NS && ns < playEndNs }
      .map { (ns, index) -> spec.frameTimeMs(index) - offsetMs - positionAt(clock, ns) }
    frames.take(DEBUG_FRAMES).forEach { (ns, index) ->
      qa("offset=$offsetMs frame=$index at=${(ns - playStartNs) / NS_PER_MS} drift=${spec.frameTimeMs(index) - offsetMs - positionAt(clock, ns)}")
    }
    qa("offset=$offsetMs frames=${frames.size} measured=${drifts.size} drift min=${drifts.minOrNull()} max=${drifts.maxOrNull()} " +
      "mean=${drifts.average()}")

    assertThat(drifts.size).isAtLeast(MIN_FRAMES)
    assertThat(drifts.maxOf { abs(it) }).isAtMost(maxDriftMs.toDouble())
    // Merged playback starts at the short's start: the first frame shown is the one at start + offset.
    val first = frames.minOf { it.second }
    val expected = ((startMs + offsetMs) * spec.fps / 1000).toInt()
    assertThat(first).isIn(com.google.common.collect.Range.closed(expected - 1, expected + FIRST_FRAME_SLACK))
  }

  /** The audio clock at [ns], from the nearest earlier sample at speed 1. */
  private fun positionAt(clock: List<Pair<Long, Long>>, ns: Long): Double {
    val sample = clock.lastOrNull { it.first <= ns } ?: clock.first()
    return sample.second + (ns - sample.first) / NS_PER_MS
  }

  private companion object {
    const val IMAGES = 6
    const val PLAY_MS = 3_500L
    const val WARM_UP_NS = 500_000_000L
    const val NS_PER_MS = 1_000_000.0
    const val MIN_SAMPLES = 60
    const val MIN_FRAMES = 40
    const val FIRST_FRAME_SLACK = 3
    const val DEBUG_FRAMES = 40
    const val FRAME_MS = 34L
  }
}
