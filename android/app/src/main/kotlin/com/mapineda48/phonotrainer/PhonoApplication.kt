package com.mapineda48.phonotrainer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application and root of the Hilt graph (architecture 2.2). WorkManager is initialised on demand with the Hilt
 * worker factory, so workers of :core:sync and :core:requests get their dependencies injected.
 */
@HiltAndroidApp
class PhonoApplication : Application(), Configuration.Provider {
  @Inject
  lateinit var workerFactory: HiltWorkerFactory

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
