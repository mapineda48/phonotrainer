package com.mapineda48.phonotrainer.core.requests

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.requests.android.QueueWorkNames
import com.mapineda48.phonotrainer.core.requests.android.UploadWorker
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The visible upload of a file above the background window (F-20-AC10, Android review H4) through the real
 * [UploadWorker]. WorkManager 2.12.0 reports `setForeground()` done before its service calls `startForeground`, and the
 * service swallows a refusal, so on Android 14 and later the worker itself must not ask for a `dataSync` foreground
 * service the app has no `FOREGROUND_SERVICE_DATA_SYNC` for: the file then keeps waiting for the app, instead of
 * starting as ordinary work that Android stops at its 10-minute limit and that restarts from zero.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun queueTest(block: suspend TestScope.(QueueHarness) -> Unit) = runTest {
    QueueHarness(backgroundScope).use { block(it) }
  }

  /** Every foreground the worker asked WorkManager for. */
  private class RecordingForegroundUpdater : TestForegroundUpdater() {
    val asked = mutableListOf<ForegroundInfo>()

    override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
      asked += foregroundInfo
      return super.setForegroundAsync(context, id, foregroundInfo)
    }
  }

  private val transfers = TransferNotifier { _, progress, _ ->
    NotificationCompat.Builder(context, "transfers").setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle(progress.title).build()
  }

  /** A 600 MB video waiting to be sent: above the 200 MB background window when no upload rate was measured. */
  private suspend fun QueueHarness.largeWaitingFile(): RequestId {
    val bytes = ByteArray(1_000) { 1 }
    val id = RequestId("40000000-0000-4000-8000-%012d".format(core.requests.count() + 1))
    val copy = files.copy(id, "mp4")
    copy.parentFile!!.mkdirs()
    copy.writeBytes(bytes)
    val now = clock.now().toEpochMilli()
    core.insert(
      RequestEntity(
        id = id.value,
        seq = 0,
        kind = "FILE",
        state = "WAITING",
        createdAt = now,
        updatedAt = now,
        fileName = "VID_0001.mp4",
        fileMime = "video/mp4",
        fileBytes = 600_000_000,
        copyFile = files.relative(copy),
        copyBytes = bytes.size.toLong(),
        copyComplete = true,
        fileSha256 = Sha256.hex(bytes),
        targetServerId = computer.serverId.value,
      ),
    )
    return id
  }

  /** Runs the upload work the learner started from the app, which is open, as WorkManager would. */
  private suspend fun QueueHarness.runVisibleUpload(foreground: RecordingForegroundUpdater): ListenableWorker.Result {
    visibility.visible = true
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == UploadWorker::class.java.name) {
          UploadWorker(appContext, workerParameters, uploader, transfers, visibility, PhonoLog.NONE)
        } else {
          null
        }
    }
    val worker = TestListenableWorkerBuilder<UploadWorker>(context)
      .setWorkerFactory(factory)
      .setForegroundUpdater(foreground)
      .setInputData(workDataOf(QueueWorkNames.KEY_USER_VISIBLE to true))
      .build()
    return worker.doWork()
  }

  @Test
  @Config(sdk = [35])
  fun withoutTheDataSyncPermissionALargeFileKeepsWaitingForTheAppAndNoForegroundServiceIsAskedFor() = queueTest { queue ->
    val id = queue.largeWaitingFile()
    val foreground = RecordingForegroundUpdater()

    assertThat(queue.runVisibleUpload(foreground)).isEqualTo(ListenableWorker.Result.success())

    assertThat(foreground.asked).isEmpty()
    val waiting = queue.request(id)
    assertThat(waiting.state).isEqualTo(RequestState.WAITING)
    assertThat(waiting.waiting!!.needsAppOpen).isTrue()
    assertThat(waiting.waiting!!.attempts).isEqualTo(0)
    assertThat(waiting.lastError).isNull()
    assertThat(queue.computer.requests.none { it.path.startsWith("/api/phone/uploads") }).isTrue()
    assertThat(queue.files.dir(id).exists()).isTrue()
  }

  @Test
  @Config(sdk = [35])
  fun withTheDataSyncPermissionALargeFileIsSentAsAVisibleDataSyncTransfer() = queueTest { queue ->
    shadowOf(context as Application).grantPermissions(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
    val id = queue.largeWaitingFile()
    val foreground = RecordingForegroundUpdater()

    queue.runVisibleUpload(foreground)

    assertThat(foreground.asked).isNotEmpty()
    assertThat(foreground.asked.map { it.foregroundServiceType }.distinct())
      .containsExactly(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(foreground.asked.first().notificationId).isEqualTo(QueueWorkNames.UPLOAD_NOTIFICATION)
    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
    assertThat(queue.request(id).state).isEqualTo(RequestState.IN_LINE)
  }

  @Test
  @Config(sdk = [30])
  fun beforeAndroid14NoTypePermissionIsNeededForTheVisibleTransfer() = queueTest { queue ->
    val id = queue.largeWaitingFile()
    val foreground = RecordingForegroundUpdater()

    queue.runVisibleUpload(foreground)

    assertThat(foreground.asked).isNotEmpty()
    assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
  }
}
