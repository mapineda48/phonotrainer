package com.mapineda48.phonotrainer.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness.Media
import com.mapineda48.phonotrainer.core.player.support.Shell
import com.mapineda48.phonotrainer.core.player.support.SurfaceHost
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.qa
import com.mapineda48.phonotrainer.core.player.support.waitFor
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swipe to sound on the pool (spec SC-08: ≤ 300 ms at p90 over 50 swipes; work-plan WP-11 Proof), measured from the
 * call to `settle` to the moment the platform reports the audio output started (Media3's `onAudioPositionAdvancing`,
 * derived from the audio track's own timestamps), including jumps to shorts that start far from a key frame; and the
 * decoder count and memory while swiping (architecture 11.2, recorded for the DefaultPreloadManager decision).
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SwipeTimingTest {
  private val harness = PlayerHarness()
  private lateinit var pool: FeedPlayerPool
  private var host: SurfaceHost? = null

  @After
  fun tearDown() {
    host?.close()
    if (::pool.isInitialized) onMain { pool.release() }
    harness.release()
  }

  /** [count] two-second shorts spread over four clips, each clip's starts walking along the 12 s video. */
  private fun shorts(count: Int, video: java.io.File = Media.video, codec: String = "h264", offsetOf: (Int) -> Long = { (it / 4 % 5) * 2_000L }): List<FeedItem> =
    (0 until count).map { i ->
      val job = JobId("clip-${i % 4}")
      harness.clip(job, true)
      val start = offsetOf(i)
      harness.item(harness.media(job, video = video, codec = codec), start, start + 2_000, firstSegment = i)
    }

  @Test
  fun fiftySwipesStartSoundInTime() {
    pool = harness.pool()
    val items = shorts(SWIPES + 2)
    onMain {
      pool.setQueue(items, 0)
      pool.settle(0, autoplay = true)
    }
    host = SurfaceHost.currentPage(pool) // the current short renders to a real surface, as in the pager
    waitFor("the first short playing") { pool.positionMs() > items[0].short.playStartMs + 100 }
    val memoryBefore = pssKb()

    val latencies = (1..SWIPES).map { index ->
      Thread.sleep(DWELL_MS) // the learner watches a moment before swiping
      swipeToSound(index)
    }
    val memoryAfter = pssKb()

    report("swipe", latencies, memoryBefore, memoryAfter)
    assertThat(p90(latencies)).isAtMost(SC_08_MS)
    assertThat(harness.decoderCounter.peak).isAtMost(POOL_SIZE + 1) // a swap may overlap one release
  }

  @Test
  fun aJumpToAShortFarFromAKeyFrameStartsSoundInTime() {
    // Key frames every 5 s and starts 4.5 s after one: the decoder must decode 135 frames to show the first.
    pool = harness.pool()
    val items = shorts(40, video = Media.longGop) { if (it % 2 == 0) 4_500L else 9_500L }
    onMain {
      pool.setQueue(items, 0)
      pool.settle(0, autoplay = true)
    }
    host = SurfaceHost.currentPage(pool)
    waitFor("the first short playing") { pool.positionMs() > items[0].short.playStartMs + 100 }

    val far = listOf(20, 3, 30, 10, 36, 14, 26, 6, 33, 18)
    val latencies = far.map { index ->
      Thread.sleep(DWELL_MS)
      swipeToSound(index)
    }

    report("jump", latencies, null, null)
    assertThat(p90(latencies)).isAtMost(SC_08_MS)
  }

  @Test
  fun decodersAndMemoryWhileSwipingHdVideo() {
    // Architecture 11.2: three prepared players hold three video decoders; measured on 1280 x 720 H.264.
    pool = harness.pool()
    val items = shorts(HD_SWIPES + 2, video = Media.hd, codec = "h264") { (it / 4 % 4) * 2_000L }
      .map { it.copy(media = it.media.copy(videoWidth = Media.HD.width, videoHeight = Media.HD.height)) }
    onMain {
      pool.setQueue(items, 0)
      pool.settle(0, autoplay = true)
    }
    host = SurfaceHost.currentPage(pool)
    waitFor("the first short playing") { pool.positionMs() > items[0].short.playStartMs + 100 }
    val before = pssKb()

    val latencies = (1..HD_SWIPES).map { index ->
      Thread.sleep(DWELL_MS)
      swipeToSound(index)
    }
    val after = pssKb()

    report("hd-swipe", latencies, before, after)
    assertThat(harness.decoderCounter.peak).isAtMost(POOL_SIZE + 1)
    assertThat(p90(latencies)).isAtMost(SC_08_MS)
  }

  /** Settles on [index] with autoplay and returns how long until the audio output really started, in ms. */
  private fun swipeToSound(index: Int): Long {
    val playout = PlayoutListener()
    val target = onMain {
      val player = pool.playerAt(index)
      player?.addAnalyticsListener(playout)
      player
    }
    val start = System.currentTimeMillis()
    val current = onMain {
      pool.settle(index, autoplay = true)
      pool.currentPlayer()!!.also { if (it !== target) it.addAnalyticsListener(playout) }
    }
    waitFor("sound of short $index", timeoutMs = 5_000) { playout.startedAt != null }
    onMain {
      current.removeAnalyticsListener(playout)
      target?.removeAnalyticsListener(playout)
    }
    return playout.startedAt!! - start
  }

  private fun report(kind: String, latencies: List<Long>, before: Long?, after: Long?) {
    val sorted = latencies.sorted()
    qa("$kind-to-sound ms: n=${latencies.size} p50=${sorted[sorted.size / 2]} p90=${p90(latencies)} max=${sorted.last()} " +
        "all=$latencies; video decoders peak=${harness.decoderCounter.peak} live=${harness.decoderCounter.live}; " +
        "pss kB before=$before after=$after; ${decoderInstances()}",
    )
  }

  private fun p90(latencies: List<Long>): Long = latencies.sorted()[(latencies.size * 9 + 9) / 10 - 1]

  /** The app's total PSS from `dumpsys meminfo`, in kB. */
  private fun pssKb(): Long? = Shell.run("dumpsys meminfo ${harness.context.packageName}")
    .lineSequence().firstOrNull { it.trimStart().startsWith("TOTAL PSS:") || it.trimStart().startsWith("TOTAL:") }
    ?.trim()?.split(Regex("\\s+"))?.firstNotNullOfOrNull { it.toLongOrNull() }

  /** The codec instances the media resource manager holds for this process. */
  private fun decoderInstances(): String {
    val pid = android.os.Process.myPid()
    val dump = Shell.run("dumpsys media.resource_manager")
    val mine = dump.lines().filter { "$pid" in it || harness.context.packageName in it }.take(RESOURCE_LINES)
    return "resource_manager[$pid]=" + mine.joinToString(" | ") { it.trim() }.take(RESOURCE_CHARS)
  }

  private class PlayoutListener : AnalyticsListener {
    @Volatile var startedAt: Long? = null

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
      if (startedAt == null) startedAt = playoutStartSystemTimeMs
    }
  }

  private companion object {
    const val SWIPES = 50
    const val HD_SWIPES = 30
    const val DWELL_MS = 400L
    const val SC_08_MS = 300L
    const val POOL_SIZE = 3
    const val RESOURCE_LINES = 12
    const val RESOURCE_CHARS = 600
  }
}
