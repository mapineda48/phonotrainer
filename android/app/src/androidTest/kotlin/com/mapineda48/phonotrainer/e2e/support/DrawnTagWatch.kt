package com.mapineda48.phonotrainer.e2e.support

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.ref.WeakReference

/**
 * Remembers whether a node tagged [tag] was in a frame the app drew since [start] (architecture 21.5). The Compose test
 * API looks at the screen only once it is idle, so a state that lasts a frame or two, such as a new ViewModel's first
 * state before its data arrives, is gone by then. This looks at every frame drawn by the activity on screen and by
 * every activity created after [start] (a recreated one included), from that activity's first frame on, until
 * [close].
 *
 * ```
 * val loading = DrawnTagWatch(LibraryTags.LOADING)
 * loading.start(phono.scenario)
 * phono.scenario.recreate()
 * assertThat(loading.drawn).isFalse()
 * ```
 */
class DrawnTagWatch(private val tag: String) : AutoCloseable {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app = instrumentation.targetContext.applicationContext as Application
  private val watched = mutableListOf<Pair<WeakReference<View>, ViewTreeObserver.OnDrawListener>>()
  private var registered = false

  @Volatile private var seen = false

  /** Whether a frame drawn since [start] showed a node tagged [tag]. */
  val drawn: Boolean get() = seen

  private val activities = object : Application.ActivityLifecycleCallbacks {
    // After onCreate the activity has its content and no frame yet: its first frame is drawn after onResume.
    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = watch(activity)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
  }

  /** Forgets the frames drawn so far and watches the activity of [scenario] and every activity created from now on. */
  fun start(scenario: ActivityScenario<*>) {
    instrumentation.runOnMainSync {
      seen = false
      if (!registered) {
        app.registerActivityLifecycleCallbacks(activities)
        registered = true
      }
    }
    scenario.onActivity(::watch)
  }

  override fun close() {
    instrumentation.runOnMainSync {
      if (registered) app.unregisterActivityLifecycleCallbacks(activities)
      registered = false
      watched.forEach { (view, listener) -> view.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(listener) }
      watched.clear()
    }
  }

  /** Main thread only. */
  private fun watch(activity: Activity) {
    val root = activity.window.decorView
    if (watched.any { it.first.get() === root }) return
    val listener = ViewTreeObserver.OnDrawListener { if (!seen && shows(root)) seen = true }
    root.viewTreeObserver.addOnDrawListener(listener)
    watched += WeakReference(root) to listener
  }

  private fun shows(view: View): Boolean = when (view) {
    is ViewRootForTest -> has(view.semanticsOwner.unmergedRootSemanticsNode)
    is ViewGroup -> view.children.any(::shows)
    else -> false
  }

  private fun has(node: SemanticsNode): Boolean =
    node.config.getOrNull(SemanticsProperties.TestTag) == tag || node.children.any(::has)
}
