package com.mapineda48.phonotrainer.e2e

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
 *
 * WorkManager's foreground service is off for the run (decisions I-21): the test WorkManager runs WorkManager's
 * main-thread callbacks inline on the worker's thread, and that service's dispatcher, written for the main thread
 * alone, then raced the end of a visible transfer and asked Android for a foreground service of type "none". Workers
 * that ask for the foreground go on as if Android had granted it; the service itself is proved by `:core:sync` and
 * `:core:requests` on the device, with WorkManager's own executors.
 *
 * The system, not this process, keeps a component's enabled state: if the run dies before [finish] (a crash the runner
 * does not survive, or a killed process), the service stays off on that install, `adb install -r` included. Uninstall
 * the app (AGP's managed-device and connected runs do after the run), or turn the service back on with
 * `adb shell pm enable com.mapineda48.phonotrainer/androidx.work.impl.foreground.SystemForegroundService`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
    super.newApplication(cl, HiltTestApplication::class.java.name, context)

  override fun onCreate(arguments: Bundle?) {
    val args = arguments ?: Bundle()
    val listeners = listOfNotNull(args.getString(LISTENER)?.takeIf { it.isNotBlank() }, PerTestIsolation::class.java.name)
    args.putString(LISTENER, listeners.joinToString(","))
    super.onCreate(args)
    foregroundService(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    PerTestIsolation.freshWorkManager(targetContext)
  }

  override fun finish(resultCode: Int, results: Bundle?) {
    foregroundService(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    super.finish(resultCode, results)
  }

  /** Sets WorkManager's foreground service to [state] without killing the app, whose process runs this runner. */
  private fun foregroundService(state: Int) {
    targetContext.packageManager.setComponentEnabledSetting(
      ComponentName(targetContext, WORK_MANAGER_FOREGROUND_SERVICE),
      state,
      PackageManager.DONT_KILL_APP,
    )
  }

  private companion object {
    /** The instrumentation argument that names extra JUnit `RunListener`s (comma-separated). */
    const val LISTENER = "listener"

    /** WorkManager's own foreground service (a library-internal class, named rather than referenced). */
    const val WORK_MANAGER_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
  }
}
