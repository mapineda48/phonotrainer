package com.mapineda48.phonotrainer.core.sync

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
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.sync.transfer.DownloadPlanner
import com.mapineda48.phonotrainer.core.sync.work.SyncWorkNames
import com.mapineda48.phonotrainer.core.sync.work.dataSyncForegroundAllowed
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download worker as a user-visible transfer on the device (architecture 7.2, 7.4, 21.1; work-plan WP-08): started
 * from the open app it runs as WorkManager's `dataSync` foreground service; stopped half-way through a file (the
 * "Stop" of its notification, or Android), it puts the file back in the queue with what arrived, and the next run
 * resumes from there with `Range` and verifies the whole file.
 */
@RunWith(AndroidJUnit4::class)
class DownloadOnDeviceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  /** Whether WorkManager's foreground service of this app is running in the foreground now. */
  @Suppress("DEPRECATION") // getRunningServices still returns the caller's own services, which is all this needs
  private fun foregroundServiceRunning(): Boolean =
    context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
      it.service.className == WORK_MANAGER_SERVICE && it.foreground
    }

  private fun downloads(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(SyncWorkNames.DOWNLOADS).get()

  private suspend fun until(what: String, condition: suspend () -> Boolean) {
    withTimeout(TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }
    check(condition()) { what }
  }

  @Test
  fun aVisibleDownloadStoppedHalfWayResumesFromItsPartAndIsVerifiedWhole(): Unit = runBlocking {
    assertThat(ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC))
      .isEqualTo(PackageManager.PERMISSION_GRANTED)
    assertThat(dataSyncForegroundAllowed(context)).isTrue()
    DeviceSync().use { sync ->
      val id = sync.computer.addFinishedClip("Ordering coffee", video = false)
      sync.catalogue()
      sync.transfer.holdAfterHalf(Names.MIX)

      ActivityScenario.launch(ForegroundStandInActivity::class.java).use {
        sync.foreground.state.value = true
        sync.work.scheduleDownloads(userVisible = true)

        val half = withTimeout(TIMEOUT_MS) { sync.transfer.halfWritten.await() }
        until("the visible transfer runs as a foreground service") { foregroundServiceRunning() }

        // The "Stop" of the notification: WorkManager cancels the run in the middle of the file.
        workManager.cancelUniqueWork(SyncWorkNames.DOWNLOADS).result.get()
        until("the run ends") { downloads().all { it.state.isFinished } }

        val stopped = sync.db.artifactDao().get(id.value, Names.MIX)!!
        assertThat(stopped.localState).isEqualTo("QUEUED")
        assertThat(stopped.partBytes).isEqualTo(half)
        assertThat(sync.files.part(id.value, Names.MIX, stopped.partRevision!!)!!.length()).isEqualTo(half)

        sync.transfer.release()
        sync.work.scheduleDownloads(userVisible = true)
        until("the next run succeeds") { downloads().any { it.state == WorkInfo.State.SUCCEEDED } }
        val resumed = sync.transfer.requests.last { it.target.name.startsWith(".${Names.MIX}.") }
        assertThat(resumed.resumeFrom).isEqualTo(half)
        val done = sync.db.artifactDao().get(id.value, Names.MIX)!!
        assertThat(done.localState).isEqualTo("READY")
        assertThat(done.verifyFailures).isEqualTo(0)
        assertThat(DownloadPlanner.packOnPhone(sync.db.artifactDao().forJob(id.value), Track.MIX)).isTrue()
        assertThat(sync.reconciler.onPhone).containsExactly(id)
      }
    }
  }

  private object Names {
    const val MIX = "audio_mix"
  }

  private companion object {
    /** WorkManager's own foreground service (a library-internal class, named rather than referenced). */
    const val WORK_MANAGER_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
    const val TIMEOUT_MS = 60_000L
    const val POLL_MS = 50L
  }
}
