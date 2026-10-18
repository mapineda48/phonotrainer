package com.mapineda48.phonotrainer.e2e.di

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * The JUnit listener `HiltTestRunner` adds to every run (architecture 21.5): before each test a fresh test WorkManager
 * (an empty in-memory work database, so no request survives from an earlier test), after each test the test's
 * application scope is cancelled (so nothing a test's singletons started keeps running into the next test) and every
 * fake computer it started is closed.
 */
class PerTestIsolation : RunListener() {
  override fun testStarted(description: Description) {
    freshWorkManager(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  override fun testFinished(description: Description) {
    synchronized(scopes) {
      scopes.forEach { it.cancel() }
      scopes.clear()
    }
    FakeComputers.closeAll()
  }

  companion object {
    private val scopes: MutableList<CoroutineScope> = Collections.synchronizedList(mutableListOf())

    /** A test's `@ApplicationScope` (`TestCoroutinesModule`), cancelled when the test ends. */
    fun track(scope: CoroutineScope): CoroutineScope = scope.also { scopes += it }

    /**
     * Replaces WorkManager with a new test instance: synchronous, driven by the test driver, Hilt-aware workers. The
     * previous instance's work is cancelled but its database stays open: a worker of the previous test may still be
     * finishing (a download the test did not wait for), and WorkManager writes its end into that database; closing it
     * under the worker crashes the process inside WorkManager.
     */
    fun freshWorkManager(context: Context) {
      runCatching { WorkManager.getInstance(context).cancelAllWork() }
      val configuration = Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setWorkerFactory(TestWorkerFactory)
        .build()
      WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }
  }
}
