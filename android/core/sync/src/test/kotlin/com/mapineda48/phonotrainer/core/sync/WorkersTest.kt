package com.mapineda48.phonotrainer.core.sync

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.sync.engine.DownloadRunner
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import com.mapineda48.phonotrainer.core.sync.work.DownloadWorker
import com.mapineda48.phonotrainer.core.sync.work.SyncWorkNames
import com.mapineda48.phonotrainer.core.sync.work.SyncWorker
import com.mapineda48.phonotrainer.core.sync.work.WifiBackoff
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The workers of architecture 7.2 with `TestListenableWorkerBuilder` (F-21-AC8): a background run brings a clip that
 * finished while the app was closed and updates "Last synced"; a visible download asks for a `dataSync` foreground
 * service only when Android allows it and goes on as ordinary work when it does not; stops are logged with their reason.
 */
@RunWith(RobolectricTestRunner::class)
class WorkersTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  private class RecordingForegroundUpdater(private val refuse: Boolean = false) : TestForegroundUpdater() {
    val asked = mutableListOf<ForegroundInfo>()

    override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> {
      asked += foregroundInfo
      // ForegroundServiceStartNotAllowedException is an IllegalStateException.
      if (refuse) return Futures.immediateFailedFuture(IllegalStateException("Not allowed to start the service now"))
      return super.setForegroundAsync(context, id, foregroundInfo)
    }
  }

  private fun SyncHarness.syncWorker(mode: String): SyncWorker = TestListenableWorkerBuilder<SyncWorker>(context)
    .setInputData(workDataOf(SyncWorkNames.KEY_MODE to mode))
    .setWorkerFactory(SyncWorkerFactory({ orchestrator }, { DownloadRunner { downloader.run(it) } }, { work }, { meta }, log = log))
    .build()

  private fun SyncHarness.downloadWorker(
    visible: Boolean,
    updater: RecordingForegroundUpdater,
    runner: DownloadRunner = DownloadRunner { downloader.run(it) },
  ): DownloadWorker = TestListenableWorkerBuilder<DownloadWorker>(context)
    .setInputData(workDataOf(SyncWorkNames.KEY_USER_VISIBLE to true))
    .setForegroundUpdater(updater)
    .setWorkerFactory(SyncWorkerFactory({ orchestrator }, { runner }, { work }, { meta }, SettableForeground(visible), log))
    .build()

  @Test
  fun aBackgroundSyncBringsAClipThatFinishedWhileTheAppWasClosedAndUpdatesLastSynced() = sync { h ->
    // F-21-AC8.
    h.computer.addFinishedClip("Ordering coffee", video = false)
    assertThat(h.meta.lastListingAt()).isNull()

    assertThat(h.syncWorker(SyncWorkNames.MODE_PERIODIC).doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(h.work.downloads).containsExactly(false)
    assertThat(h.downloadWorker(visible = false, RecordingForegroundUpdater()).doWork()).isEqualTo(ListenableWorker.Result.success())

    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
    assertThat(h.meta.lastListingAt()).isEqualTo(h.clock.now())
  }

  @Test
  fun aPeriodicRunThatDoesNotReachTheComputerLeavesAWifiWorkForWhatWaits() = sync { h ->
    h.status.statusState.value = ComputerStatus.Offline
    h.requests.countsState.value = h.requests.countsState.value.copy(waiting = 1)

    h.syncWorker(SyncWorkNames.MODE_PERIODIC).doWork()

    assertThat(h.work.onWifi).isEqualTo(1)
  }

  @Test
  fun aDownloadRunThatLosesTheComputerWhileFilesWaitLeavesTheNextWifiRunWithADoublingDelay() = sync { h ->
    // Architecture 7.2 (Android review M1): never a zero delay from inside a run, so a computer whose probe answers
    // while its downloads do not cannot loop a pass and a run.
    h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.status.statusState.value = ComputerStatus.Offline

    assertThat(h.downloadWorker(visible = false, RecordingForegroundUpdater()).doWork()).isEqualTo(ListenableWorker.Result.success())
    h.downloadWorker(visible = false, RecordingForegroundUpdater()).doWork()

    assertThat(h.work.nextOnWifi).containsExactly(WifiBackoff.FIRST, WifiBackoff.FIRST.multipliedBy(2)).inOrder()
    assertThat(h.meta.wifiBackoffMs()).isEqualTo(WifiBackoff.FIRST.multipliedBy(2).toMillis())
    assertThat(h.work.onWifi).isEqualTo(0)
  }

  @Test
  fun aDownloadRunThatEndsWithNothingWaitingOrStillConnectedLeavesNoWifiRun() = sync { h ->
    h.status.statusState.value = ComputerStatus.Offline
    h.downloadWorker(visible = false, RecordingForegroundUpdater(), runner = { RunOutcome.NOT_CONNECTED }).doWork()

    h.status.statusState.value = SyncHarness.CONNECTED
    h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.downloadWorker(visible = false, RecordingForegroundUpdater(), runner = { RunOutcome.DONE }).doWork()

    assertThat(h.work.nextOnWifi).isEmpty()
    assertThat(h.work.onWifi).isEqualTo(0)
  }

  @Test
  @Config(sdk = [35])
  fun aVisibleDownloadIsADataSyncForegroundServiceWhenAndroidAllowsIt() = sync { h ->
    shadowOf(context as Application).grantPermissions(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
    h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.PAIRED, userVisible = true)
    val updater = RecordingForegroundUpdater()

    h.downloadWorker(visible = true, updater).doWork()

    assertThat(updater.asked).isNotEmpty()
    assertThat(updater.asked.first().foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  @Config(sdk = [35])
  fun aRefusedForegroundServiceFallsBackToOrdinaryWorkAndTheDownloadStillHappens() = sync { h ->
    shadowOf(context as Application).grantPermissions(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
    h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.PAIRED, userVisible = true)
    val updater = RecordingForegroundUpdater(refuse = true)

    assertThat(h.downloadWorker(visible = true, updater).doWork()).isEqualTo(ListenableWorker.Result.success())

    assertThat(updater.asked).hasSize(1)
    assertThat(h.logged).contains("sync.download_not_foreground (java.lang.IllegalStateException)")
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  @Config(sdk = [35])
  fun withoutTheDataSyncPermissionNoForegroundIsAskedAndTheDownloadIsOrdinaryWork() = sync { h ->
    shadowOf(context as Application).denyPermissions(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
    h.computer.addFinishedClip("Ordering coffee", video = false)
    h.orchestrator.pass(SyncReason.PAIRED, userVisible = true)
    val updater = RecordingForegroundUpdater()

    h.downloadWorker(visible = true, updater).doWork()

    assertThat(updater.asked).isEmpty()
    assertThat(h.logged).contains("sync.download_not_foreground.permission")
    assertThat(h.clipRepository.clips(ClipQuery()).first().single().availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  @Test
  fun aDownloadStartedAfterTheAppLeftTheForegroundNeverAsksForTheService() = sync { h ->
    val updater = RecordingForegroundUpdater()

    h.downloadWorker(visible = false, updater, runner = { RunOutcome.DONE }).doWork()

    assertThat(updater.asked).isEmpty()
  }

  @Test
  @Config(sdk = [30])
  fun expeditedSyncAndDownloadWorkCarryADataSyncNotificationOnAndroid10And11() = sync { h ->
    val syncInfo = h.syncWorker(SyncWorkNames.MODE_SEND_NOW).getForegroundInfoAsync().get()
    val downloadInfo = h.downloadWorker(visible = false, RecordingForegroundUpdater()).getForegroundInfoAsync().get()

    assertThat(syncInfo.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(downloadInfo.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    assertThat(syncInfo.notificationId).isNotEqualTo(downloadInfo.notificationId)
  }

  @Test
  fun aStoppedDownloadLogsWhyItWasStopped() = runBlocking {
    SyncHarness().use { h ->
      val started = CompletableDeferred<Unit>()
      val runner = DownloadRunner {
        started.complete(Unit)
        awaitCancellation()
      }
      initWorkManager(context, SyncWorkerFactory({ ScriptedPasses(PassOutcome.NotPaired) }, { runner }, { h.work }, { h.meta }, log = h.log))
      val request = OneTimeWorkRequestBuilder<DownloadWorker>().build()
      val workManager = WorkManager.getInstance(context)
      workManager.enqueue(request).result.get()
      withTimeout(10_000) { started.await() }

      workManager.cancelWorkById(request.id).result.get()

      withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (h.logged.none { it.startsWith("sync.download_stopped.") }) delay(10) }
      }
      val state = workManager.getWorkInfoById(request.id).get()!!.state
      assertThat(state.isFinished).isTrue()
      assertThat(state).isNotEqualTo(WorkInfo.State.SUCCEEDED)
    }
  }
}
