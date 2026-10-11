package com.mapineda48.phonotrainer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapineda48.phonotrainer.core.common.di.ProcessStart
import com.mapineda48.phonotrainer.core.notifications.NotificationsStartup
import dagger.hilt.android.HiltAndroidApp
import java.time.Instant
import javax.inject.Inject

/**
 * The application and root of the Hilt graph (architecture 2.2). WorkManager is initialised on demand with the Hilt
 * worker factory, so workers of :core:sync and :core:requests get their dependencies injected. `onCreate` records the
 * process start instant (4.6) and creates the notification channels (13).
 */
@HiltAndroidApp
class PhonoApplication : Application(), Configuration.Provider {
  @Inject
  lateinit var workerFactory: HiltWorkerFactory

  /** Injected first thing in [onCreate], which fixes the instant `StartupCheck` compares with (architecture 4.6). */
  @Inject
  @ProcessStart
  lateinit var processStart: Instant

  @Inject
  lateinit var notificationsStartup: NotificationsStartup

  override fun onCreate() {
    super.onCreate()
    notificationsStartup.onApplicationCreate(this)
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
