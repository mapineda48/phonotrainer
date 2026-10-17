package com.mapineda48.phonotrainer.core.requests.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.requests.store.AppVisibility
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.intake.FileIntake
import com.mapineda48.phonotrainer.core.requests.send.UploadForeground
import com.mapineda48.phonotrainer.core.requests.send.Uploader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** The names and inputs of the queue's work (architecture 7.2). */
internal object QueueWorkNames {
  const val UPLOADS = "uploads"
  const val KEY_REQUEST = "request_id"
  const val KEY_USER_VISIBLE = "user_visible"

  fun shareIntake(id: RequestId): String = "intake-share-${id.value}"

  fun fileIntake(id: RequestId): String = "intake-${id.value}"

  /** Foreground notification ids, apart from the notifier's own (which all carry a tag). */
  const val UPLOAD_NOTIFICATION = 9_101
  const val INTAKE_NOTIFICATION = 9_102
}

/** [RequestWork] on WorkManager, initialised by the application with the Hilt worker factory. */
internal class WorkManagerRequestWork @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : RequestWork {
  private val work: WorkManager get() = WorkManager.getInstance(context)

  override fun startShareIntake(id: RequestId) {
    val request = OneTimeWorkRequestBuilder<ShareIntakeWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .setInputData(workDataOf(QueueWorkNames.KEY_REQUEST to id.value))
      .build()
    work.enqueueUniqueWork(QueueWorkNames.shareIntake(id), INTAKE_POLICY, request)
  }

  override fun startFileIntake(id: RequestId) {
    val request = OneTimeWorkRequestBuilder<FileIntakeWorker>()
      .setInputData(workDataOf(QueueWorkNames.KEY_REQUEST to id.value))
      .build()
    work.enqueueUniqueWork(QueueWorkNames.fileIntake(id), INTAKE_POLICY, request)
  }

  override fun stopIntake(id: RequestId) {
    work.cancelUniqueWork(QueueWorkNames.shareIntake(id))
    work.cancelUniqueWork(QueueWorkNames.fileIntake(id))
  }

  override fun scheduleUploads(userVisible: Boolean) {
    val request = OneTimeWorkRequestBuilder<UploadWorker>()
      .setInputData(workDataOf(QueueWorkNames.KEY_USER_VISIBLE to userVisible))
      .build()
    // Appended: a run in progress finishes its file, then the new run looks at the queue again.
    work.enqueueUniqueWork(QueueWorkNames.UPLOADS, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  private companion object {
    /**
     * A new intake of a request ("Try audio only" right after a failed copy) may arrive while the work that failed is
     * still finishing: it is appended and runs next, never dropped; a copy already done makes it end at once.
     */
    val INTAKE_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
  }
}

/** Why Android stopped [worker], for the log: its stop reason on Android 12 and later, "unknown" before. */
internal fun stopReasonOf(worker: ListenableWorker): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason.toString() else "unknown"

/** The "Transfers" notification as a foreground service of type `dataSync` (architecture 7.2, Android review L10). */
internal fun transferForeground(
  notifier: TransferNotifier,
  id: Int,
  kind: TransferKind,
  progress: TransferProgress,
): ForegroundInfo =
  ForegroundInfo(id, notifier.transferNotification(kind, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

/**
 * Whether WorkManager's foreground service can really enter the foreground as `dataSync`, checked before
 * `setForeground()` because the worker never learns of a refusal: WorkManager 2.12.0 completes `setForeground()` once it
 * has called `startForegroundService`, and the service's own `startForeground` catches `SecurityException` and
 * `ForegroundServiceStartNotAllowedException` and only logs them. On Android 14 and later that refusal is certain
 * without `FOREGROUND_SERVICE_DATA_SYNC` (and the system may also raise `ForegroundServiceDidNotStartInTimeException`).
 * Without this check a file above the background window would start as ordinary work, be stopped at Android's
 * 10-minute limit and restart from zero, over and over (F-20-AC10, Android review H4). The other refusals are not
 * expected here, because a visible transfer is only asked for while the app is visible: the app in the background
 * (refused by `startForegroundService` itself, which does reach the worker) and Android 15's daily data-sync limit
 * (reset when the app comes to the foreground).
 */
internal fun dataSyncForegroundAllowed(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
  return ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) ==
    PackageManager.PERMISSION_GRANTED
}

/**
 * The expedited copy of a shared item (`intake-share-<id>`, architecture 8.3, Android review H2a): it keeps the process
 * out of the cached state, so pressing Home during a large copy no longer freezes it. On Android 10 and 11 expedited
 * work runs as a foreground service with [getForegroundInfo].
 */
@HiltWorker
internal class ShareIntakeWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val intake: FileIntake,
  private val startup: StartupGate,
  private val transfers: TransferNotifier,
  private val core: QueueCore,
  private val live: QueueLive,
  private val log: PhonoLog,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val id = inputData.getString(QueueWorkNames.KEY_REQUEST)?.let(::RequestId) ?: return Result.failure()
    startup.awaitDone()
    return try {
      intake.copy(id)
      Result.success()
    } catch (stopped: CancellationException) {
      log.w("requests.intake_stopped.${stopReasonOf(this)}")
      throw stopped
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    transferForeground(transfers, QueueWorkNames.INTAKE_NOTIFICATION, TransferKind.SHARE_COPY, intakeProgress(core, live, inputData.getString(QueueWorkNames.KEY_REQUEST)))
}

/** The copy of a picked item, or of the copy already kept (`intake-<id>`): restarted after process death (F-19-AC4). */
@HiltWorker
internal class FileIntakeWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val intake: FileIntake,
  private val startup: StartupGate,
  private val transfers: TransferNotifier,
  private val core: QueueCore,
  private val live: QueueLive,
  private val log: PhonoLog,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val id = inputData.getString(QueueWorkNames.KEY_REQUEST)?.let(::RequestId) ?: return Result.failure()
    startup.awaitDone()
    return try {
      intake.copy(id)
      Result.success()
    } catch (stopped: CancellationException) {
      log.w("requests.intake_stopped.${stopReasonOf(this)}")
      throw stopped
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    transferForeground(transfers, QueueWorkNames.INTAKE_NOTIFICATION, TransferKind.SHARE_COPY, intakeProgress(core, live, inputData.getString(QueueWorkNames.KEY_REQUEST)))
}

private suspend fun intakeProgress(core: QueueCore, live: QueueLive, id: String?): TransferProgress {
  val row = id?.let { runCatching { core.requests.get(it) }.getOrNull() }
  val moved = id?.let { live.copied.value[it] }
  return TransferProgress(
    title = row?.let(core::title).orEmpty(),
    doneBytes = moved?.bytes ?: 0,
    totalBytes = moved?.total ?: row?.fileBytes,
    items = 1,
  )
}

/**
 * The uploads (`uploads`, architecture 8.5): a visible transfer when the learner started it in the foreground (it may
 * then send files of any size), ordinary background work otherwise, where a file starts only if it fits one run.
 */
@HiltWorker
internal class UploadWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val uploader: Uploader,
  private val transfers: TransferNotifier,
  private val visibility: AppVisibility,
  private val log: PhonoLog,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val foreground = WorkerForeground()
    if (inputData.getBoolean(QueueWorkNames.KEY_USER_VISIBLE, false) && visibility.visible()) {
      foreground.promote(TransferProgress(title = "", doneBytes = 0, totalBytes = null, items = 1))
    }
    return try {
      uploader.run(foreground)
      Result.success()
    } catch (stopped: CancellationException) {
      log.w("requests.upload_stopped.${stopReasonOf(this)}")
      throw stopped
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = transferForeground(
    transfers,
    QueueWorkNames.UPLOAD_NOTIFICATION,
    TransferKind.UPLOAD,
    TransferProgress(title = "", doneBytes = 0, totalBytes = null, items = 1),
  )

  private inner class WorkerForeground : UploadForeground {
    private var promoted = false
    private var permissionMissingLogged = false

    override fun appVisible(): Boolean = visibility.visible()

    override suspend fun promote(progress: TransferProgress): Boolean {
      if (promoted) return true
      if (!dataSyncForegroundAllowed(applicationContext)) {
        // The service would be refused where the worker cannot see it: the file keeps waiting for a visible transfer.
        if (!permissionMissingLogged) log.w("requests.upload_not_foreground.permission")
        permissionMissingLogged = true
        return false
      }
      return try {
        setForeground(transferForeground(transfers, QueueWorkNames.UPLOAD_NOTIFICATION, TransferKind.UPLOAD, progress))
        promoted = true
        true
      } catch (stopped: CancellationException) {
        throw stopped
      } catch (refused: Exception) {
        // Started after the app left the foreground (ForegroundServiceStartNotAllowedException): ordinary work.
        log.w("requests.upload_not_foreground", refused)
        false
      }
    }

    override suspend fun progress(progress: TransferProgress) {
      if (!promoted) return
      runCatching {
        setForeground(transferForeground(transfers, QueueWorkNames.UPLOAD_NOTIFICATION, TransferKind.UPLOAD, progress))
      }
    }
  }
}
