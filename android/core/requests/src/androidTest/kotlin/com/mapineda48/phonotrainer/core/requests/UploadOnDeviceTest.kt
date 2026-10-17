package com.mapineda48.phonotrainer.core.requests

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.requests.android.QueueWorkNames
import com.mapineda48.phonotrainer.core.requests.android.dataSyncForegroundAllowed
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A visible upload of a file above the background window on the device (F-20-AC10, architecture 7.2 and 8.5): with
 * `FOREGROUND_SERVICE_DATA_SYNC` in the manifest (this test package declares it, as the app's merged manifest will by
 * change request CR-WP09-1), the real WorkManager's service really enters the foreground as `dataSync` while the body
 * is sent, and the file reaches the computer. Without the permission the worker does not ask for the service at all
 * (`UploadWorkerTest` on the JVM), because WorkManager would report success while Android refused the service.
 */
@RunWith(AndroidJUnit4::class)
class UploadOnDeviceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private suspend fun DeviceQueue.largeWaitingFile(): RequestId {
    val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
    val id = RequestId(UUID.randomUUID().toString())
    val copy = files.copy(id, "mp4")
    copy.parentFile!!.mkdirs()
    copy.writeBytes(bytes)
    val now = System.currentTimeMillis()
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
        // Declared 600 MB: above the 200 MB background window when no upload rate was measured.
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

  /** Whether WorkManager's foreground service of this app is running in the foreground now. */
  @Suppress("DEPRECATION") // getRunningServices still returns the caller's own services, which is all this needs
  private fun foregroundServiceRunning(): Boolean =
    context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
      it.service.className == WORK_MANAGER_SERVICE && it.foreground
    }

  @Test
  fun aLargeFileSentFromTheOpenAppRunsAsADataSyncForegroundServiceAndReachesTheComputer() = runBlocking {
    assertThat(ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC))
      .isEqualTo(PackageManager.PERMISSION_GRANTED)
    assertThat(dataSyncForegroundAllowed(context)).isTrue()
    DeviceQueue().use { queue ->
      val id = queue.largeWaitingFile()
      ActivityScenario.launch(SheetStandInActivity::class.java).use {
        queue.visible = true
        queue.client.hold()

        queue.work.scheduleUploads(userVisible = true)

        withTimeout(TIMEOUT_MS) { queue.client.bodyStarted.await() }
        withTimeout(TIMEOUT_MS) { while (!foregroundServiceRunning()) delay(POLL_MS) }
        queue.client.release()

        val work = WorkManager.getInstance(context)
        withTimeout(TIMEOUT_MS) {
          while (work.getWorkInfosForUniqueWork(QueueWorkNames.UPLOADS).get().any { !it.state.isFinished }) delay(POLL_MS)
        }
        assertThat(work.getWorkInfosForUniqueWork(QueueWorkNames.UPLOADS).get().last().state)
          .isEqualTo(WorkInfo.State.SUCCEEDED)
      }
      assertThat(queue.computer.jobsWithKey(id)).hasSize(1)
      assertThat(queue.core.requests.get(id.value)!!.state).isEqualTo("IN_LINE")
    }
  }

  private companion object {
    /** WorkManager's own foreground service (a library-internal class, named rather than referenced). */
    const val WORK_MANAGER_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
    const val TIMEOUT_MS = 30_000L
    const val POLL_MS = 50L
  }
}
