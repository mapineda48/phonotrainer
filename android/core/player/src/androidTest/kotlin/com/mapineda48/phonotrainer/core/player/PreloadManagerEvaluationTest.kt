package com.mapineda48.phonotrainer.core.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.media.ClipSources
import com.mapineda48.phonotrainer.core.player.media.DefaultExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.media.ShortPlanner
import com.mapineda48.phonotrainer.core.player.support.DecoderCounter
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.qa
import com.mapineda48.phonotrainer.core.player.support.waitFor
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The evaluation of Media3's `DefaultPreloadManager` as a drop-in for the pool's preparation step (architecture 11.2;
 * work-plan WP-11 Proof): one player, the neighbours preloaded with their tracks selected and their first second
 * loaded, swiped through the same shorts as `SwipeTimingTest`. It records swipe to sound and the decoder count for
 * the decision in `decisions.md`; it asserts only that every swipe played.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreloadManagerEvaluationTest {
  private val harness = PlayerHarness()

  @Test
  fun swipeToSoundWithOnePlayerAndThePreloadManager() {
    val sources = ClipSources()
    val plans = (0 until SWIPES + 2).map { i ->
      val job = JobId("clip-${i % 4}")
      val start = (i * STEP_MS) % LAST_START_MS
      val item = harness.item(harness.media(job), start, start + 2_000, firstSegment = i)
      ShortPlanner.plan(item, Track.MIX, true, false) { true }
    }
    var current = 0
    val control = TargetPreloadStatusControl<Int, PreloadStatus> { index ->
      if (abs(index - current) == 1) PreloadStatus.specifiedRangeLoaded(0, PRELOAD_MS) else PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
    }
    val counter = DecoderCounter()
    val (manager, player, shorts) = onMain {
      val builder = DefaultPreloadManager.Builder(harness.context, control)
        .setRenderersFactory(DefaultRenderersFactory(harness.context))
      val player = builder.buildExoPlayer(
        ExoPlayer.Builder(harness.context).setAudioAttributes(DefaultExoPlayerFactory.SPEECH, false).setLooper(Looper.getMainLooper()),
      )
      player.addAnalyticsListener(counter)
      val manager = builder.build()
      val shorts: List<MediaSource> = plans.map(sources::short)
      shorts.forEachIndexed { i, source -> manager.add(source, i) }
      manager.setCurrentPlayingIndex(0)
      manager.invalidate()
      player.setMediaSource(manager.getMediaSource(shorts[0].mediaItem)!!)
      player.prepare()
      player.play()
      Triple(manager, player, shorts)
    }
    waitFor("the first short playing") { player.currentPosition > 100 }

    val latencies = (1..SWIPES).map { index ->
      Thread.sleep(DWELL_MS)
      val playout = object : AnalyticsListener {
        @Volatile var startedAt: Long? = null

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
          if (startedAt == null) startedAt = playoutStartSystemTimeMs
        }
      }
      val start = System.currentTimeMillis()
      onMain {
        player.addAnalyticsListener(playout)
        current = index
        player.setMediaSource(manager.getMediaSource(shorts[index].mediaItem) ?: shorts[index])
        player.prepare()
        player.play()
        manager.setCurrentPlayingIndex(index)
        manager.invalidate()
      }
      waitFor("sound of short $index", timeoutMs = 5_000) { playout.startedAt != null }
      onMain { player.removeAnalyticsListener(playout) }
      playout.startedAt!! - start
    }
    onMain {
      manager.release()
      player.release()
    }

    val sorted = latencies.sorted()
    qa("preload-manager swipe-to-sound ms: n=${latencies.size} p50=${sorted[sorted.size / 2]} " +
        "p90=${sorted[(sorted.size * 9 + 9) / 10 - 1]} max=${sorted.last()} all=$latencies; video decoders peak=${counter.peak}",
    )
    assertThat(latencies).hasSize(SWIPES)
  }

  private companion object {
    const val SWIPES = 50
    const val DWELL_MS = 400L
    const val STEP_MS = 173L
    const val LAST_START_MS = 9_500L
    const val PRELOAD_MS = 1_000L
  }
}
