package com.mapineda48.phonotrainer.core.sync.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.sync.engine.DownloadRunner
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundState
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.engine.SyncPasses
import com.mapineda48.phonotrainer.core.sync.engine.SyncWork
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import com.mapineda48.phonotrainer.core.sync.transfer.RunProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** The names and inputs of `:core:sync`'s unique works (architecture 7.2); "Forget this computer" cancels them by name. */
internal object SyncWorkNames {
  const val PERIODIC = "sync-periodic"
  const val ON_WIFI = "sync-on-wifi"
  const val SEND_NOW = "send-now"
  const val DOWNLOADS = "downloads"

  const val KEY_MODE = "mode"
  const val KEY_USER_VISIBLE = "user_visible"

  const val MODE_PERIODIC = "periodic"
  const val MODE_ON_WIFI = "on_wifi"
  const val MODE_SEND_NOW = "send_now"

  /** Foreground notification ids, apart from the notifier's own (which all carry a tag) and the queue's 9101–9102. */
  const val DOWNLOAD_NOTIFICATION = 9_201
  const val SEND_NOW_NOTIFICATION = 9_202

  /** The periodic sync's period (spec 7.5: at most every 15 minutes, the shortest WorkManager allows). */
  val PERIOD: Duration = Duration.ofMinutes(15)
}

/**
 * The Wi-Fi retry delay of architecture 7.2 (Android review M1): a `sync-on-wifi` run that did not reach the computer
 * schedules the next one 15 minutes later, then 30, 60 and at most 2 hours while the outcome stays "not reachable";
 * reaching the computer, or a network change seen in the foreground, starts it again from 15 minutes.
 */
internal object WifiBackoff {
  val FIRST: Duration = Duration.ofMinutes(15)
  val LAST: Duration = Duration.ofHours(2)

  /** A retry of requests that failed for a moment waits at least this long (the retry schedule's first step). */
  val MIN_RETRY: Duration = Duration.ofSeconds(30)

  fun next(previous: Duration): Duration =
    if (previous < FIRST) FIRST else minOf(previous.multipliedBy(2), LAST)

  /**
   * A run that ended without reaching the computer while something waits: the next `sync-on-wifi` after the doubled
   * delay, recorded so the following miss doubles it again (never a zero delay from inside a run).
   */
  suspend fun retryLater(meta: SyncMeta, work: SyncWork) {
    val next = next(Duration.ofMillis(meta.wifiBackoffMs()))
    meta.setWifiBackoffMs(next.toMillis())
    work.nextOnWifi(next)
  }
}

/** [SyncWork] on WorkManager, initialised by the application with the Hilt worker factory. */
internal class WorkManagerSyncWork @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val log: PhonoLog,
) : SyncWork {
  private val work: WorkManager get() = WorkManager.getInstance(context)

  override fun scheduleDownloads(userVisible: Boolean) {
    // A run waiting to start already re-reads the queue; a running one re-reads it after each file and the next run,
    // appended behind it, picks up what it missed.
    if (waitingToStart(SyncWorkNames.DOWNLOADS) && !userVisible) return
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
      .setInputData(workDataOf(SyncWorkNames.KEY_USER_VISIBLE to userVisible))
      .build()
    work.enqueueUniqueWork(SyncWorkNames.DOWNLOADS, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  override fun sendNow() {
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .setInputData(workDataOf(SyncWorkNames.KEY_MODE to SyncWorkNames.MODE_SEND_NOW))
      .build()
    work.enqueueUniqueWork(SyncWorkNames.SEND_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  override fun ensureOnWifi() {
    work.enqueueUniqueWork(SyncWorkNames.ON_WIFI, ExistingWorkPolicy.KEEP, onWifi(Duration.ZERO))
  }

  override fun nextOnWifi(delay: Duration) {
    require(!delay.isZero && !delay.isNegative) { "a retry from inside a run never has a zero delay" }
    work.enqueueUniqueWork(SyncWorkNames.ON_WIFI, ExistingWorkPolicy.APPEND_OR_REPLACE, onWifi(delay))
  }

  override fun ensurePeriodic() {
    val request = PeriodicWorkRequestBuilder<SyncWorker>(SyncWorkNames.PERIOD.toMinutes(), TimeUnit.MINUTES)
      .setConstraints(
        Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build(),
      )
      .setInputData(workDataOf(SyncWorkNames.KEY_MODE to SyncWorkNames.MODE_PERIODIC))
      .build()
    work.enqueueUniquePeriodicWork(SyncWorkNames.PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  override fun cancelUnpaired() {
    work.cancelUniqueWork(SyncWorkNames.PERIODIC)
    work.cancelUniqueWork(SyncWorkNames.ON_WIFI)
  }

  private fun onWifi(delay: Duration) = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
    .setInputData(workDataOf(SyncWorkNames.KEY_MODE to SyncWorkNames.MODE_ON_WIFI))
    .build()

  private fun waitingToStart(name: String): Boolean = try {
    work.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
  } catch (failed: ExecutionException) {
    log.w("sync.work_unknown", failed)
    false
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    false
  }
}

/** Why Android stopped [worker], for the log: its stop reason on Android 12 and later, "unknown" before. */
internal fun stopReasonOf(worker: ListenableWorker): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason.toString() else "unknown"

/**
 * Whether WorkManager's foreground service can really enter the foreground as `dataSync`: on Android 14 and later only
 * with `FOREGROUND_SERVICE_DATA_SYNC` (WorkManager 2.12.0 reports `setForeground` done even when the service is
 * refused, so the worker checks first; WP-09's finding).
 */
internal fun dataSyncForegroundAllowed(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
  return ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) ==
    PackageManager.PERMISSION_GRANTED
}

/**
 * The sync worker (architecture 7.2): the periodic background sync (`sync-periodic`), the sync waiting for a Wi-Fi
 * network (`sync-on-wifi`, with the retry delay of [WifiBackoff], never re-enqueued with a zero delay from inside a
 * run), and the expedited pass of the share sheet (`send-now`). Each run is one pass; a background pass never calls
 * UI code and claims no time.
 */
@HiltWorker
internal class SyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val passes: SyncPasses,
  private val work: SyncWork,
  private val meta: SyncMeta,
  private val transfers: TransferNotifier,
  private val log: PhonoLog,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val mode = inputData.getString(SyncWorkNames.KEY_MODE) ?: SyncWorkNames.MODE_PERIODIC
    return try {
      val outcome = passes.pass(reasonOf(mode))
      when (mode) {
        SyncWorkNames.MODE_ON_WIFI -> afterOnWifi(outcome)
        else -> if (outcome is PassOutcome.NotConnected && passes.hasPendingWork()) work.ensureOnWifi()
      }
      Result.success()
    } catch (stopped: CancellationException) {
      log.w("sync.sync_stopped.${stopReasonOf(this)}")
      throw stopped
    }
  }

  private suspend fun afterOnWifi(outcome: PassOutcome) {
    when (outcome) {
      is PassOutcome.NotConnected -> if (passes.hasPendingWork()) WifiBackoff.retryLater(meta, work)
      is PassOutcome.Done -> {
        val retry = passes.nextRetryIn() ?: return
        work.nextOnWifi(maxOf(retry, WifiBackoff.MIN_RETRY))
      }
      PassOutcome.Blocked, PassOutcome.NotPaired -> Unit
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
    SyncWorkNames.SEND_NOW_NOTIFICATION,
    transfers.transferNotification(TransferKind.UPLOAD, TransferProgress(title = "", doneBytes = 0, totalBytes = null, items = 1)),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
  )

  private fun reasonOf(mode: String): SyncReason = when (mode) {
    SyncWorkNames.MODE_ON_WIFI -> SyncReason.ON_WIFI
    SyncWorkNames.MODE_SEND_NOW -> SyncReason.SEND_NOW
    else -> SyncReason.PERIODIC
  }
}

/**
 * The download worker (`downloads`, architecture 7.2, 7.4): one download run. When the learner started it in the
 * foreground it asks to be a visible transfer (a `dataSync` foreground service with the "Transfers" notification and its
 * "Stop"); if Android does not allow it (started after the app left the foreground, or no `dataSync` permission), it
 * goes on as ordinary deferrable work: downloads are resumable, so a stop is harmless (spec 7.7). A run that loses the
 * computer while files still wait leaves the next `sync-on-wifi` behind it, with the delay of [WifiBackoff] (a
 * computer whose probe answers while its downloads do not must not loop a pass and a run with no pause).
 */
@HiltWorker
internal class DownloadWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val runner: DownloadRunner,
  private val passes: SyncPasses,
  private val work: SyncWork,
  private val meta: SyncMeta,
  private val transfers: TransferNotifier,
  private val foreground: ForegroundState,
  private val log: PhonoLog,
) : CoroutineWorker(context, params) {
  private var promoted = false
  private var lastUpdate = 0L

  override suspend fun doWork(): Result {
    if (inputData.getBoolean(SyncWorkNames.KEY_USER_VISIBLE, false) && foreground.foreground.value) {
      promoted = promote(TransferProgress(title = "", doneBytes = 0, totalBytes = null, items = 1))
    }
    return try {
      val outcome = runner.run { progress -> update(progress) }
      if (outcome == RunOutcome.NOT_CONNECTED && passes.hasPendingWork()) WifiBackoff.retryLater(meta, work)
      Result.success()
    } catch (stopped: CancellationException) {
      log.w("sync.download_stopped.${stopReasonOf(this)}")
      throw stopped
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    info(TransferProgress(title = "", doneBytes = 0, totalBytes = null, items = 1))

  private suspend fun promote(progress: TransferProgress): Boolean {
    if (!dataSyncForegroundAllowed(applicationContext)) {
      log.w("sync.download_not_foreground.permission")
      return false
    }
    return try {
      setForeground(info(progress))
      true
    } catch (stopped: CancellationException) {
      throw stopped
    } catch (refused: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException (an IllegalStateException): started after the app left the
      // foreground. The run goes on as ordinary work (architecture 7.2).
      log.w("sync.download_not_foreground", refused)
      false
    }
  }

  private fun update(progress: RunProgress) {
    if (!promoted) return
    val now = System.currentTimeMillis()
    // Android drops notification updates above about five a second; one a second is plenty.
    if (now - lastUpdate < NOTIFICATION_INTERVAL_MS) return
    lastUpdate = now
    setForegroundAsync(
      info(TransferProgress(progress.title, progress.doneBytes, progress.totalBytes, progress.filesLeft)),
    )
  }

  private fun info(progress: TransferProgress): ForegroundInfo = ForegroundInfo(
    SyncWorkNames.DOWNLOAD_NOTIFICATION,
    transfers.transferNotification(TransferKind.DOWNLOAD, progress, stopIntent()),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
  )

  /** The notification's "Stop" (spec F-23): WorkManager cancels this work; null where WorkManager is not set up. */
  private fun stopIntent() = try {
    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
  } catch (notInitialized: IllegalStateException) {
    null
  }

  private companion object {
    const val NOTIFICATION_INTERVAL_MS = 1_000L
  }
}
