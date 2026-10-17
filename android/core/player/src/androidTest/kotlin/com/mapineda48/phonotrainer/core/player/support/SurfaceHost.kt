package com.mapineda48.phonotrainer.core.player.support

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.mapineda48.phonotrainer.core.player.android.PlayerSurface
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool

/**
 * A real window that shows the feed's surfaces the way the Watch pager does: an activity whose Compose content runs
 * on the main thread with the real frame clock (a Compose test rule would run Media3's effects on the test thread).
 */
internal class SurfaceHost private constructor(private val scenario: ActivityScenario<ComponentActivity>) : AutoCloseable {
  override fun close() = scenario.close()

  /**
   * A screen capture of the one `SurfaceView` on screen (what the learner sees of the video), or null when none is
   * showing or its surface is not ready.
   */
  fun captureSurface(): android.graphics.Bitmap? {
    var view: android.view.SurfaceView? = null
    scenario.onActivity { view = find(it.window.decorView) }
    val surface = view?.takeIf { it.width > 0 && it.height > 0 && it.holder.surface.isValid } ?: return null
    val bitmap = android.graphics.Bitmap.createBitmap(surface.width, surface.height, android.graphics.Bitmap.Config.ARGB_8888)
    val done = java.util.concurrent.CountDownLatch(1)
    var result = android.view.PixelCopy.ERROR_UNKNOWN
    val thread = android.os.HandlerThread("capture").apply { start() }
    android.view.PixelCopy.request(surface, bitmap, { code -> result = code; done.countDown() }, android.os.Handler(thread.looper))
    done.await(CAPTURE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
    thread.quitSafely()
    return bitmap.takeIf { result == android.view.PixelCopy.SUCCESS }
  }

  private fun find(view: android.view.View): android.view.SurfaceView? = when (view) {
    is android.view.SurfaceView -> view
    is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
    else -> null
  }

  companion object {
    /** Shows [content] in a fresh activity. */
    fun show(content: @Composable () -> Unit): SurfaceHost {
      val scenario = ActivityScenario.launch(ComponentActivity::class.java)
      scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply { setContent(content) }) }
      return SurfaceHost(scenario)
    }

    /** The current page's surface, as the pager shows it: page `state.index` and nothing else. */
    fun currentPage(pool: FeedPlayerPool): SurfaceHost = show {
      val state by pool.state.collectAsState()
      Column {
        PlayerSurface(pool, state.index, Modifier.fillMaxWidth().height(PAGE_HEIGHT.dp))
      }
    }

    private const val PAGE_HEIGHT = 240
    private const val CAPTURE_TIMEOUT_S = 5L
  }
}
