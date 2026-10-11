package com.mapineda48.phonotrainer.e2e.di

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * The JUnit listener `HiltTestRunner` adds to every run (architecture 21.5): before each test a fresh test WorkManager
 * (an empty in-memory work database, so no request survives from an earlier test), after each test every fake
 * computer it started is closed.
 */
class PerTestIsolation : RunListener() {
  override fun testStarted(description: Description) {
    freshWorkManager(InstrumentationRegistry.getInstrumentation().targetContext)
  }

  override fun testFinished(description: Description) {
    FakeComputers.closeAll()
  }

  companion object {
    /** Replaces WorkManager with a new test instance: synchronous, driven by the test driver, Hilt-aware workers. */
    fun freshWorkManager(context: Context) {
      runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
      val configuration = Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setWorkerFactory(TestWorkerFactory)
        .build()
      WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }
  }
}
