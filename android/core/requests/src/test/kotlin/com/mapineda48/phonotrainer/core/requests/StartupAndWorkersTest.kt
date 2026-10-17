package com.mapineda48.phonotrainer.core.requests

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.requests.android.QueueWorkNames
import com.mapineda48.phonotrainer.core.requests.android.ShareIntakeWorker
import com.mapineda48.phonotrainer.core.requests.android.UploadWorker
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The start-up copy checks (architecture 4.6, 8.3; F-20-AC9) and the workers' foreground side (Android review L10:
 * expedited work runs as a `dataSync` foreground service on Android 10 and 11).
 */
@RunWith(RobolectricTestRunner::class)
class StartupAndWorkersTest {
  private fun queueTest(block: suspend TestScope.(QueueHarness) -> Unit) = runTest {
    QueueHarness(backgroundScope).use { block(it) }
  }

  private suspend fun QueueHarness.stored(state: String, kind: String = "FILE", copy: Boolean = true, nextAttemptAt: Long? = null): RequestId {
    val id = RequestId("20000000-0000-4000-8000-%012d".format(core.requests.count() + 1))
    val file = files.copy(id, "mp4")
    if (copy) {
      file.parentFile!!.mkdirs()
      file.writeBytes(ByteArray(1_000) { 1 })
    }
    val now = clock.now().toEpochMilli()
    core.insert(
      RequestEntity(
        id = id.value,
        seq = 0,
        kind = kind,
        state = state,
        createdAt = now,
        updatedAt = now,
        fileName = "a.mp4",
        fileMime = "video/mp4",
        fileBytes = 1_000,
        copyFile = if (kind == "FILE") files.relative(file) else null,
        copyComplete = kind == "FILE",
        fileSha256 = Sha256.hex(ByteArray(1_000) { 1 }),
        linkSent = if (kind == "LINK") "https://youtu.be/abc123def45" else null,
        nextAttemptAt = nextAttemptAt,
      ),
    )
    return id
  }

  @Test
  fun aWaitingFileWhoseCopyWasDeletedOutsideTheAppIsMissingAtStartAndNeverSent() = queueTest { queue ->
    val id = queue.stored("WAITING")
    queue.files.dir(id).deleteRecursively()
    queue.clock.advanceBy(Duration.ofSeconds(5))

    queue.startup.run(queue.clock.now())
    queue.uploader.run(FakeForeground())

    val request = queue.request(id)
    assertThat(request.state).isEqualTo(RequestState.COPY_MISSING)
    assertThat(request.lastError!!.code).isEqualTo("copy_missing")
    assertThat(request.actions).containsExactly(com.mapineda48.phonotrainer.core.domain.model.RequestAction.REMOVE)
    assertThat(queue.computer.requests).isEmpty()
  }

  @Test
  fun aRequestLeftSendingWaitsAgainWithItsKeyAndNothingCounted() = queueTest { queue ->
    val id = queue.stored("SENDING", kind = "LINK", copy = false)
    queue.clock.advanceBy(Duration.ofSeconds(5))

    queue.startup.run(queue.clock.now())

    assertThat(queue.request(id).state).isEqualTo(RequestState.WAITING)
    assertThat(queue.request(id).waiting!!.attempts).isEqualTo(0)
  }

  @Test
  fun rowsAndFilesOfThisProcessAreLeftAlone() = queueTest { queue ->
    val id = queue.stored("PREPARING", copy = false)

    queue.startup.run(queue.clock.now().minusSeconds(1))

    assertThat(queue.request(id).state).isEqualTo(RequestState.PREPARING)
  }

  @Test
  fun directoriesNoRequestOwnsAreSwept() = queueTest { queue ->
    val kept = queue.stored("WAITING")
    val orphan = File(queue.files.root, "30000000-0000-4000-8000-000000000001").apply { mkdirs() }
    File(orphan, "source.mp4").writeBytes(ByteArray(10))
    orphan.setLastModified(0)
    queue.clock.advanceBy(Duration.ofSeconds(5))

    queue.startup.run(queue.clock.now())

    assertThat(orphan.exists()).isFalse()
    assertThat(queue.files.dir(kept).exists()).isTrue()
  }

  @Test
  fun aReconnectionMakesWaitingRequestsAndOperationsDueAtOnceKeepingTheirAttempts() = queueTest { queue ->
    queue.status.statusState.value = ComputerStatus.Offline
    val id = queue.stored("WAITING", kind = "LINK", copy = false, nextAttemptAt = Long.MAX_VALUE)
    queue.startup.run(queue.clock.now())

    queue.status.statusState.value = QueueHarness.CONNECTED
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { while (queue.row(id)!!.nextAttemptAt != null) delay(10) }
    }

    assertThat(queue.row(id)!!.nextAttemptAt).isNull()
  }

  @Test
  @Config(sdk = [30])
  fun theWorkersRunAsDataSyncForegroundServicesOnOlderAndroid() = queueTest { queue ->
    val context: Context = ApplicationProvider.getApplicationContext()
    val transfers = TransferNotifier { _, progress, _ ->
      NotificationCompat.Builder(context, "transfers").setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(progress.title).build()
    }
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
          ShareIntakeWorker::class.java.name ->
            ShareIntakeWorker(appContext, workerParameters, queue.intake, FakeStartupGate(), transfers, queue.core, queue.live, PhonoLog.NONE)
          UploadWorker::class.java.name ->
            UploadWorker(appContext, workerParameters, queue.uploader, transfers, queue.visibility, PhonoLog.NONE)
          else -> null
        }
    }
    val id = queue.stored("PREPARING", copy = false)

    val intake = TestListenableWorkerBuilder<ShareIntakeWorker>(context)
      .setWorkerFactory(factory)
      .setInputData(workDataOf(QueueWorkNames.KEY_REQUEST to id.value))
      .build()
      .getForegroundInfo()
    val upload = TestListenableWorkerBuilder<UploadWorker>(context).setWorkerFactory(factory).build().getForegroundInfo()

    assertThat(intake.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(intake.notificationId).isEqualTo(QueueWorkNames.INTAKE_NOTIFICATION)
    assertThat(intake.notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("a.mp4")
    assertThat(upload.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(upload.notificationId).isEqualTo(QueueWorkNames.UPLOAD_NOTIFICATION)
  }
}
