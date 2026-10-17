package com.mapineda48.phonotrainer.core.player

import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.qa
import com.mapineda48.phonotrainer.core.player.support.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clock the captions read every frame (architecture 11.3; spec SC-09 and F-07-AC5, player side): `positionMs()`
 * read at each frame stays within 60 ms of what is being heard, taken from the moment the platform reports the audio
 * output started (`onAudioPositionAdvancing`, derived from the audio track's timestamps) and the speed.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CaptionClockTest {
  private val harness = PlayerHarness()
  private val job = JobId("captions")
  private lateinit var pool: FeedPlayerPool

  @After
  fun tearDown() {
    if (::pool.isInitialized) onMain { pool.release() }
    harness.release()
  }

  @Test
  fun thePositionReadEachFrameFollowsTheHeardAudioAtFullSpeed() = check(speed = 1f)

  @Test
  fun thePositionReadEachFrameFollowsTheHeardAudioAtHalfSpeed() = check(speed = 0.5f)

  private fun check(speed: Float) {
    harness.clip(job, true)
    pool = harness.pool()
    val start = 3_000L
    onMain {
      pool.setQueue(listOf(harness.item(harness.media(job), start, start + 6_000)), 0)
      pool.setSpeed(speed)
      pool.settle(0, autoplay = false)
    }
    waitFor("the short prepared") { pool.currentPlayer()?.playbackState == androidx.media3.common.Player.STATE_READY }
    val playout = object : AnalyticsListener {
      @Volatile var startedAt: Long? = null

      override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        if (startedAt == null) startedAt = playoutStartSystemTimeMs
      }
    }
    val reads = CopyOnWriteArrayList<Pair<Long, Long>>() // System.currentTimeMillis, positionMs()
    var sampling = true
    val sampler = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        if (!sampling) return
        reads += System.currentTimeMillis() to pool.positionMs()
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
    onMain {
      pool.currentPlayer()!!.addAnalyticsListener(playout)
      Choreographer.getInstance().postFrameCallback(sampler)
      pool.play()
    }
    waitFor("the audio output to start") { playout.startedAt != null }
    Thread.sleep(PLAY_MS)
    onMain { sampling = false }

    val startedAt = playout.startedAt!!
    val errors = reads
      .filter { (wall, _) -> wall > startedAt + WARM_UP_MS }
      .map { (wall, position) -> position - (start + (wall - startedAt) * speed) }
    qa("speed=$speed reads=${errors.size} error ms min=${errors.minOrNull()} max=${errors.maxOrNull()} mean=${errors.average()}")

    assertThat(errors.size).isAtLeast(MIN_READS)
    assertThat(errors.maxOf { abs(it.toDouble()) }).isAtMost(SC_09_MS)
  }

  private companion object {
    const val PLAY_MS = 2_500L
    const val WARM_UP_MS = 200L
    const val MIN_READS = 60
    const val SC_09_MS = 60.0
  }
}
