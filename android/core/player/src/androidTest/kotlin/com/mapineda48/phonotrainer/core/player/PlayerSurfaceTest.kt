package com.mapineda48.phonotrainer.core.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.player.android.PlayerSurface
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.SurfaceHost
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.waitFor
import com.mapineda48.phonotrainer.core.testing.synthetic.FrameCode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The poster shows until the first frame (architecture 11.2; work-plan WP-11 Proof): only the current page renders to
 * a surface, and `firstFrameRendered` turns true only once a frame of the current short reached it, again after each
 * swipe; a hidden pool renders nothing.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest {
  private val harness = PlayerHarness()
  private val job = JobId("surface")
  private lateinit var pool: FeedPlayerPool
  private var host: SurfaceHost? = null

  @After
  fun tearDown() {
    host?.close()
    if (::pool.isInitialized) onMain { pool.release() }
    harness.release()
  }

  @Test
  fun onlyTheCurrentPageRendersAndThePosterGivesWayAtTheFirstFrame() {
    harness.clip(job, true)
    pool = harness.pool()
    val media = harness.media(job)
    val items = listOf(harness.item(media, 0, 4_000), harness.item(media, 4_000, 8_000, firstSegment = 1))
    onMain {
      pool.setQueue(items, 0)
      pool.settle(0, autoplay = false)
    }
    assertThat(onMain { pool.state.value.firstFrameRendered }).isFalse()

    host = SurfaceHost.show {
      Column {
        PlayerSurface(pool, 0, Modifier.size(160.dp, 120.dp))
        PlayerSurface(pool, 1, Modifier.size(160.dp, 120.dp))
      }
    }
    assertThat(onMain { pool.surface.value?.index }).isEqualTo(0)
    onMain { pool.play() }
    waitFor("the first frame of short 0") { pool.state.value.firstFrameRendered }

    onMain { pool.settle(1, autoplay = false) }
    // A new current short: the poster again until its own first frame.
    assertThat(onMain { pool.state.value.firstFrameRendered }).isFalse()
    assertThat(onMain { pool.surface.value?.index }).isEqualTo(1)
    onMain { pool.play() }
    waitFor("the first frame of short 1") { pool.state.value.firstFrameRendered }
    assertThat(onMain { pool.state.value.videoShown }).isTrue()
    // What is on screen is the right moment of short 1's video (a prepared neighbour moved to the real surface).
    Thread.sleep(SETTLE_MS)
    val at = onMain { pool.positionMs() }
    val shown = FrameCode.read(requireNotNull(host!!.captureSurface()) { "no surface on screen" })
    val expected = (at * PlayerHarness.Media.VIDEO.fps / 1000).toInt()
    assertThat(shown).isIn(com.google.common.collect.Range.closed(expected - FRAME_SLACK, expected + FRAME_SLACK))

    // Hidden: nothing renders and the poster comes back.
    onMain { pool.setVisible(false) }
    assertThat(onMain { pool.surface.value }).isNull()
    assertThat(onMain { pool.state.value.firstFrameRendered }).isFalse()
  }

  @Test
  fun anAudioOnlyShortRendersNoSurfaceFrame() {
    harness.clip(job, true)
    pool = harness.pool()
    onMain {
      pool.setQueue(listOf(harness.item(harness.media(job, video = null, codec = null), 0, 4_000)), 0)
      pool.settle(0, autoplay = true)
    }
    host = SurfaceHost.currentPage(pool)
    waitFor("playback") { pool.positionMs() > 500 }

    with(onMain { pool.state.value }) {
      assertThat(videoShown).isFalse()
      assertThat(firstFrameRendered).isFalse()
    }
  }

  private companion object {
    const val SETTLE_MS = 500L

    /** A capture and a position read on another thread are a few frames apart. */
    const val FRAME_SLACK = 10
  }
}
