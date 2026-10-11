package com.mapineda48.phonotrainer.e2e

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.mapineda48.phonotrainer.e2e.di.PerTestIsolation
import com.mapineda48.phonotrainer.e2e.di.TestWorkerFactory
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runs the end-to-end suite on Hilt's test application (architecture 21.5). [PerTestIsolation] joins the run's
 * listeners (a `listener` argument given on the command line is kept): every test starts with a fresh test WorkManager
 * (synchronous executor, test driver, workers created by [TestWorkerFactory] with the running test's Hilt bindings;
 * the app's own initialiser is removed from the manifest), and every fake computer a test started is closed when it
 * ends. Together with Hilt's per-test component (in-memory databases, own DataStore files) no state crosses tests.
 */
class HiltTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
    super.newApplication(cl, HiltTestApplication::class.java.name, context)

  override fun onCreate(arguments: Bundle?) {
    val args = arguments ?: Bundle()
    val listeners = listOfNotNull(args.getString(LISTENER)?.takeIf { it.isNotBlank() }, PerTestIsolation::class.java.name)
    args.putString(LISTENER, listeners.joinToString(","))
    super.onCreate(args)
    PerTestIsolation.freshWorkManager(targetContext)
  }

  private companion object {
    /** The instrumentation argument that names extra JUnit `RunListener`s (comma-separated). */
    const val LISTENER = "listener"
  }
}
