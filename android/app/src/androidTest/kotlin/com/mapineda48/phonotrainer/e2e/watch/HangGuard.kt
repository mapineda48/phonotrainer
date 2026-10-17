package com.mapineda48.phonotrainer.e2e.watch

import android.os.Looper
import android.util.Printer
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Keeps a stuck Watch test from holding the one managed device of this computer (work-plan 1.2): the test fails after
 * [limitMs]. A little before that, logcat (tag [TAG]) gets, three times, what an idle wait is waiting for: the stacks
 * of the main and test threads, why Compose's idling resources say they are busy, and the main queue. A test that
 * finishes in time leaves no trace. It writes through the app's [PhonoLog] ([log] gives it once the test injected it).
 *
 * Espresso is not on this source set's compile classpath (the `:app` build file is the integrator's), so its registry
 * and Compose's resources behind it are read by name; a dump never fails the test.
 */
class HangGuard(private val limitMs: Long = LIMIT_MS, private val log: () -> PhonoLog) : TestRule {
  override fun apply(base: Statement, description: Description): Statement {
    val timed = Timeout.builder()
      .withTimeout(limitMs, TimeUnit.MILLISECONDS)
      .withLookingForStuckThread(true)
      .build()
      .apply(base, description)
    return object : Statement() {
      override fun evaluate() {
        val watchdog = Thread({ watch(description) }, "watch-hang-guard").apply { isDaemon = true }
        watchdog.start()
        try {
          timed.evaluate()
        } finally {
          watchdog.interrupt()
        }
      }
    }
  }

  private fun watch(description: Description) {
    try {
      Thread.sleep(limitMs - DUMP_BEFORE_MS)
      repeat(DUMPS) {
        dump(description)
        Thread.sleep(DUMP_EVERY_MS)
      }
    } catch (_: InterruptedException) {
      // The test finished in time.
    }
  }

  private fun dump(description: Description) {
    val log = log()
    log.i(TAG) { "${description.methodName} is still running after ${limitMs - DUMP_BEFORE_MS} ms" }
    val main = Looper.getMainLooper().thread
    for ((thread, stack) in Thread.getAllStackTraces()) {
      if (thread != main && !thread.name.startsWith(TEST_THREAD)) continue
      log.i(TAG) { "Thread ${thread.name} (${thread.state}):\n" + stack.joinToString("\n") { "  at $it" } }
    }
    for (resource in espressoResources()) {
      log.i(TAG) { "Idling resource ${resource.javaClass.name}: ${diagnostic(resource)}" }
      for (inner in composeResources(resource)) log.i(TAG) { "  ${inner.javaClass.name}: ${diagnostic(inner)}" }
    }
    Looper.getMainLooper().dump(Printer { line -> log.i(TAG) { line } }, "main queue: ")
  }

  private fun espressoResources(): List<Any> = runCatching {
    val registry = Class.forName(ESPRESSO_REGISTRY).getMethod("getInstance").invoke(null)!!
    (registry.javaClass.getMethod("getResources").invoke(registry) as Collection<*>).filterNotNull()
  }.getOrDefault(emptyList())

  /** The resources behind Compose's Espresso link (`ComposeIdlingResource` says why it is busy). */
  private fun composeResources(link: Any): List<Any> = runCatching {
    val registry = link.javaClass.getDeclaredField("registry").apply { isAccessible = true }.get(link)!!
    val resources = registry.javaClass.getDeclaredField("idlingResources").apply { isAccessible = true }
    (resources.get(registry) as Collection<*>).filterNotNull()
  }.getOrDefault(emptyList())

  private fun diagnostic(resource: Any): Any? = runCatching {
    resource.javaClass.methods.firstOrNull { it.name.startsWith(DIAGNOSTIC) }?.invoke(resource)
  }.getOrNull()

  companion object {
    const val TAG = "WatchHangGuard"

    /** Every Watch area test finishes in well under half a minute; a stuck one gives the device back after this. */
    const val LIMIT_MS = 90_000L
    private const val DUMP_BEFORE_MS = 15_000L
    private const val DUMPS = 3
    private const val DUMP_EVERY_MS = 2_000L
    private const val TEST_THREAD = "Time-limited test"
    private const val ESPRESSO_REGISTRY = "androidx.test.espresso.IdlingRegistry"
    private const val DIAGNOSTIC = "getDiagnosticMessageIfBusy"
  }
}
