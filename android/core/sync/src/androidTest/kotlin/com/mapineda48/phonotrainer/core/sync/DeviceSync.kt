package com.mapineda48.phonotrainer.core.sync

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.sync.catalogue.CatalogueReconciler
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipRemoval
import com.mapineda48.phonotrainer.core.sync.catalogue.PairedSource
import com.mapineda48.phonotrainer.core.sync.engine.DownloadRunner
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundState
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.storage.AndroidDeviceSpace
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.Downloader
import com.mapineda48.phonotrainer.core.sync.transfer.TransferBoard
import com.mapineda48.phonotrainer.core.sync.work.DownloadWorker
import com.mapineda48.phonotrainer.core.sync.work.WorkManagerSyncWork
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipIndexer
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipLeases
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeDecoderCapabilities
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestReconciler
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeStudyProtection
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An activity that only keeps the test app in the foreground, as the open app does for a visible transfer. */
class ForegroundStandInActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }
}

/**
 * The sync engine's downloads wired by hand on the managed device (work-plan 2.4: no Hilt test component in core
 * instrumented tests): an in-memory `library.db`, a fresh folder under the real `filesDir`, the real free space, the
 * real WorkManager whose factory builds [DownloadWorker] over this harness's [downloader], and the in-memory fake
 * computer, paired and connected, behind a transfer a test can hold half-way through a file.
 */
internal class DeviceSync : AutoCloseable {
  val context: Context = ApplicationProvider.getApplicationContext()
  val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  val computer = FakeComputer()
  private val phone = computer.pairDevice()
  val client = FakeComputerClient(computer, phone)
  val transfer = HeldTransfer(client.transfer)
  val clock = TestClock(Instant.now())
  val files = ClipFiles(File(context.filesDir, "device-sync-${UUID.randomUUID()}").apply { mkdirs() })
  private val status = FakeComputerStatusRepository(ComputerStatus.Connected("Desk", analyzing = 0, inLine = 0, lowOnSpace = false))
  val pairing = Paired(
    PairingRecord(
      serverId = computer.serverId, serverName = "Desk", address = HostPort("192.0.2.10", 8000),
      deviceId = phone.deviceId, deviceName = "Test phone", libraryId = computer.libraryId, pairedAt = Instant.EPOCH,
      lastSeen = null, apiVersion = 6, capabilities = null, clockOffsetS = 0, lastProbe = ProbeOutcome.Ok,
      lastProbeAt = null, addressChangedAt = null, uploadThroughput = null,
    ),
  )
  val reconciler = FakeRequestReconciler()
  private val meta = SyncMeta(db.syncMetaDao())
  private val removal = ClipRemoval(
    db, db.clipDao(), files, FakeClipLeases(), Provider { emptySet() }, Provider { reconciler }, FakeUserMessages(), PhonoLog.NONE,
  )
  val catalogue = CatalogueReconciler(db, db.clipDao(), db.artifactDao(), meta, removal, FakeUserMessages(), clock, PhonoLog.NONE)
  val downloader = Downloader(
    db, db.clipDao(), db.artifactDao(), files, transfer, status, pairing,
    FakeSettingsRepository().apply { state.value = state.value.copy(downloadVideos = false) },
    FakeDecoderCapabilities(), FakeStudyProtection(), AndroidDeviceSpace(context, PhonoLog.NONE), FakeClipIndexer(),
    Provider { reconciler }, TransferBoard(), FakeStartupGate(), clock, Dispatchers.IO, PhonoLog.NONE,
  )
  val work = WorkManagerSyncWork(context, PhonoLog.NONE)
  val foreground = Visible()

  init {
    current = this
    if (!WorkManager.isInitialized()) {
      WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(Factory).build())
    }
  }

  /** The computer's listing reconciled into the catalogue, and its files queued (a pass without the network steps). */
  suspend fun catalogue() {
    val library = (client.library() as ApiResult.Ok<RemoteLibrary>).value
    catalogue.reconcile(library, PairedSource(computer.serverId.value, "Desk"))
    downloader.refreshQueue()
  }

  override fun close() {
    current = null
    transfer.release()
    db.close()
    computer.close()
    files.filesDir.deleteRecursively()
  }

  /**
   * The fake computer's transfer; [holdAfterHalf] makes the next download of a file named so write the real first half
   * of it and then wait, as a slow network does, until the run is stopped or [release]d.
   */
  class HeldTransfer(private val delegate: ArtifactTransfer) : ArtifactTransfer {
    val requests: MutableList<DownloadRequest> = Collections.synchronizedList(mutableListOf())
    val halfWritten = CompletableDeferred<Long>()
    @Volatile private var held: String? = null
    private val gate = CompletableDeferred<Unit>()

    fun holdAfterHalf(name: String) {
      held = name
    }

    fun release() {
      held = null
      gate.complete(Unit)
    }

    override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
      requests += request
      val name = held
      if (name == null || !request.target.name.startsWith(".$name.")) return delegate.download(request, onProgress)
      val whole = File(request.target.parentFile, "whole.tmp")
      delegate.download(request.copy(target = whole, resumeFrom = 0, ifRange = null), onProgress = {})
      val half = request.expectedBytes / 2
      request.target.writeBytes(whole.readBytes().copyOf(half.toInt()))
      whole.delete()
      onProgress(half)
      halfWritten.complete(half)
      gate.await()
      return delegate.download(request.copy(resumeFrom = half), onProgress)
    }
  }

  class Visible : ForegroundState {
    val state = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = state
  }

  /** A pairing store holding one computer; the key is never needed (the in-memory client signs by itself). */
  class Paired(record: PairingRecord) : PairingStore {
    override val pairing = MutableStateFlow<PairingRecord?>(record)

    override suspend fun current(): PairingRecord? = pairing.value

    override suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long) = Unit

    override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> = DeviceKeyResult.NotPaired

    override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = Unit

    override suspend fun updateAddress(address: HostPort, at: Instant) = Unit

    override suspend fun updateLibraryId(libraryId: LibraryId) = Unit

    override suspend fun updateClockOffset(offsetS: Long) = Unit

    override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = Unit

    override suspend fun recordUpload(bytes: Long, duration: Duration) = Unit

    override suspend fun clear() = Unit
  }

  /** Builds the download worker from the harness of the running test. */
  private object Factory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
      val sync = current ?: return null
      // A foreground service's notification needs its channel (the app's notifier creates it; this harness has none).
      NotificationManagerCompat.from(appContext).createNotificationChannel(
        NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Transfers").build(),
      )
      val transfers = TransferNotifier { _, progress, _ ->
        NotificationCompat.Builder(appContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
          .setContentTitle(progress.title.ifEmpty { "Transfers" }).build()
      }
      return when (workerClassName) {
        DownloadWorker::class.java.name ->
          DownloadWorker(appContext, workerParameters, DownloadRunner { sync.downloader.run(it) }, transfers, sync.foreground, PhonoLog.NONE)
        else -> null
      }
    }
  }

  companion object {
    @Volatile var current: DeviceSync? = null

    private const val CHANNEL = "transfers"
  }
}
