package com.mapineda48.phonotrainer.e2e

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runs the end-to-end suite on Hilt's test application and initialises WorkManager for tests (synchronous executor,
 * test driver), since the app's own initialiser is removed from the manifest (architecture 21.5).
 */
class HiltTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
    super.newApplication(cl, HiltTestApplication::class.java.name, context)

  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    val configuration = Configuration.Builder()
      .setExecutor(SynchronousExecutor())
      .build()
    WorkManagerTestInitHelper.initializeTestWorkManager(targetContext, configuration)
  }
}
