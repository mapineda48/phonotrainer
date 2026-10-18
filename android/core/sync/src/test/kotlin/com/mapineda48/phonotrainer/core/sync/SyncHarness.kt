package com.mapineda48.phonotrainer.core.sync

import android.content.Context
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundSession
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundState
import com.mapineda48.phonotrainer.core.sync.live.JobChannelSession
import com.mapineda48.phonotrainer.core.sync.startup.StartupCheck
import com.mapineda48.phonotrainer.core.sync.startup.SyncDirs
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeChannelLiveness
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mapineda48.phonotrainer.core.common.log.LeveledPhonoLog
import com.mapineda48.phonotrainer.core.common.log.LogLevel
import com.mapineda48.phonotrainer.core.common.log.LogSink
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.SendReport
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.sync.catalogue.CatalogueReconciler
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipRemoval
import com.mapineda48.phonotrainer.core.sync.engine.ChannelState
import com.mapineda48.phonotrainer.core.sync.engine.PassFlag
import com.mapineda48.phonotrainer.core.sync.engine.SyncOrchestrator
import com.mapineda48.phonotrainer.core.sync.engine.SyncWork
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.repository.RoomClipRepository
import com.mapineda48.phonotrainer.core.sync.storage.DeviceSpace
import com.mapineda48.phonotrainer.core.sync.storage.RoomStorageRepository
import com.mapineda48.phonotrainer.core.sync.storage.StorageRefresh
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.Downloader
import com.mapineda48.phonotrainer.core.sync.transfer.TransferBoard
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import com.mapineda48.phonotrainer.core.testing.computer.PhoneCredentials
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipLeases
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeDecoderCapabilities
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeProgressRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeStudyProtection
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Collections
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sync engine wired by hand over an in-memory `library.db`, a fresh `filesDir` and the in-memory fake computer
 * (work-plan 2.4: core tests wire the fakes they need). Every collaborator `:core:sync` does not own is a settable fake
 * of `:core:testing-jvm`; [events] records, in order, what the pass did to the computer and to the other cores.
 */
internal class SyncHarness : AutoCloseable {
  val context: Context = ApplicationProvider.getApplicationContext()
  val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  val clips = db.clipDao()
  val artifacts = db.artifactDao()
  val clock = TestClock(Instant.ofEpochSecond(FakeComputerClock.REFERENCE_EPOCH_S))
  val computer = FakeComputer(clock = FakeComputerClock.frozen())
  val phone: PhoneCredentials = computer.pairDevice()
  val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val fakeClient = FakeComputerClient(computer, phone)
  val client = RecordingClient(fakeClient, events)
  val status = FakeComputerStatusRepository(CONNECTED)
  val pairing = FakePairingStore(record(computer.serverId, LibraryId(computer.libraryId.value), phone.deviceId))
  val settings = FakeSettingsRepository()
  val decoders = FakeDecoderCapabilities()
  val protection = FakeStudyProtection()
  val space = FakeSpace()
  val indexer = RecordingIndexer(events)
  val leases = FakeClipLeases()
  val reconciler = RecordingReconciler(events)
  val sender = RecordingSender(events)
  val messages = FakeUserMessages()
  val reference = FakeReferenceRepository()
  val requests = FakeRequestRepository()
  val progress = FakeProgressRepository()
  val trigger = RecordingTrigger()
  val work = RecordingWork(events)
  val hooks = RecordingHooks(events)
  val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val log: PhonoLog = LeveledPhonoLog(LogSink { level, _, message, _ -> if (level >= LogLevel.WARN) logged += message }, verbose = false)
  val filesDir: File = File(context.filesDir, "sync-${System.nanoTime()}").apply { mkdirs() }
  val cacheDir: File = File(context.cacheDir, "sync-${System.nanoTime()}").apply { mkdirs() }
  val files = ClipFiles(filesDir)
  val meta = SyncMeta(db.syncMetaDao())
  val board = TransferBoard()
  val startup = FakeStartupGate()
  val channel = OpenChannel()
  val flag = PassFlag()
  val refresh = StorageRefresh()

  val removal = ClipRemoval(db, clips, files, leases, Provider { setOf<ClipRemovalHook>(hooks) }, Provider { reconciler }, messages, log)
  val catalogue = CatalogueReconciler(db, clips, artifacts, meta, removal, messages, clock, log)
  /** The transfer the downloader uses: the fake computer's, unless a test wraps it. */
  @Volatile var transfer: ArtifactTransfer = fakeClient.transfer
  val transfers = mutableListOf<DownloadRequest>()
  private val routedTransfer = object : ArtifactTransfer {
    override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
      transfers += request
      return transfer.download(request, onProgress)
    }
  }
  val downloader = Downloader(
    db, clips, artifacts, files, routedTransfer, status, pairing, settings, decoders, protection, space, indexer,
    Provider { reconciler }, board, startup, clock, Dispatchers.IO, log,
  )
  val orchestrator = SyncOrchestrator(
    pairing, status, client, Provider<RequestSender> { sender }, Provider<RequestReconciler> { reconciler },
    Provider { requests }, catalogue, removal, reference, meta, indexer, clips, downloader, work, channel, flag, startup,
    clock, log,
  )
  val clipRepository = RoomClipRepository(
    db, clips, artifacts, settings, status, board, Provider { progress }, protection, meta, catalogue, files, indexer,
    trigger, clock, Dispatchers.IO, log,
  )
  val storage = RoomStorageRepository(db, clips, artifacts, settings, space, files, downloader, trigger, refresh, clock, Dispatchers.IO)

  val liveness = FakeChannelLiveness()
  val network = FakeNetworkMonitor()

  /** The job channel session over the fake computer's in-memory channel. */
  val channelSession = JobChannelSession(
    fakeClient.channel, liveness, pairing, catalogue, removal, Provider<RequestReconciler> { reconciler },
    Provider<SyncTrigger> { trigger }, meta, log,
  )

  /** The foreground session over [channelSession], with [foreground] and [scope] as given. */
  fun session(
    foreground: ForegroundState,
    scope: CoroutineScope,
    startupTasks: Set<StartupTask> = emptySet(),
    work: SyncWork = this.work,
  ) =
    ForegroundSession(
      foreground, status, pairing, network, channelSession, leases, removal, work, meta,
      StartupCheck(clock.now(), { startupTasks }, db, clips, artifacts, files, SyncDirs(filesDir, cacheDir), refresh, scope, Dispatchers.IO, log),
      Provider<SyncTrigger> { trigger }, scope, log,
    )

  /** A pass and then the downloads it scheduled, as WorkManager would run them. */
  suspend fun syncAndDownload() {
    orchestrator.pass(SyncReason.SYNC_NOW)
    downloader.run {}
  }

  suspend fun clip(id: JobId): ClipEntity = clips.get(id.value)!!

  suspend fun artifact(id: JobId, name: String): ArtifactEntity = artifacts.get(id.value, name)!!

  suspend fun library(): RemoteLibrary = (fakeClient.library() as ApiResult.Ok).value

  override fun close() {
    db.close()
    computer.close()
    filesDir.deleteRecursively()
    cacheDir.deleteRecursively()
  }

  /** Records what reached the computer: the listing and the reference. */
  class RecordingClient(private val delegate: ComputerClient, private val events: MutableList<String>) : ComputerClient by delegate {
    override suspend fun library(): ApiResult<RemoteLibrary> {
      events += "library"
      return delegate.library()
    }

    override suspend fun reference(): ApiResult<String> {
      events += "reference"
      return delegate.reference()
    }
  }

  class RecordingSender(private val events: MutableList<String>) : RequestSender {
    var operations = SendReport(sent = 0, refused = 0, transient = 0, blocked = false)
    var links = SendReport(sent = 0, refused = 0, transient = 0, blocked = false)

    override suspend fun sendOperations(): SendReport {
      events += "operations"
      return operations
    }

    override suspend fun sendLinks(): SendReport {
      events += "links"
      return links
    }

    override fun scheduleUploads() {
      events += "uploads"
    }
  }

  class RecordingReconciler(private val events: MutableList<String>) : RequestReconciler {
    val libraries = mutableListOf<RemoteLibrary>()
    val jobs = mutableListOf<RemoteJob>()
    val deleted = mutableListOf<JobId>()
    val onPhone = mutableListOf<JobId>()

    override suspend fun reconcile(library: RemoteLibrary) {
      events += "reconcile"
      libraries += library
    }

    override suspend fun onJob(job: RemoteJob) {
      jobs += job
    }

    override suspend fun onJobDeleted(id: JobId) {
      deleted += id
    }

    override suspend fun onClipOnPhone(id: JobId) {
      events += "onPhone:${id.value}"
      onPhone += id
    }
  }

  /** The sync trigger, recording every reason from any thread. */
  class RecordingTrigger : SyncTrigger {
    val reasons: MutableList<SyncReason> = Collections.synchronizedList(mutableListOf())

    override fun request(reason: SyncReason) {
      reasons += reason
    }
  }

  class RecordingIndexer(private val events: MutableList<String>) : ClipIndexer {
    var outcome: (JobId) -> IndexOutcome = { IndexOutcome.Indexed(shorts = 3, occurrences = 5) }
    val indexed = mutableListOf<JobId>()
    val dropped = mutableListOf<Set<JobId>>()
    var reindexes = 0

    override suspend fun index(id: JobId): IndexOutcome {
      events += "index:${id.value}"
      indexed += id
      return outcome(id)
    }

    override suspend fun reindexStale() {
      reindexes++
    }

    override suspend fun drop(ids: Set<JobId>) {
      dropped += ids
    }
  }

  class RecordingHooks(private val events: MutableList<String>) : ClipRemovalHook {
    val removed = mutableListOf<Set<JobId>>()

    override suspend fun onClipsRemoved(ids: Set<JobId>) {
      events += "hooks:${ids.joinToString(",") { it.value }}"
      removed += ids
    }
  }

  class RecordingWork(private val events: MutableList<String>) : SyncWork {
    val downloads: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    @Volatile var sendNows = 0
    @Volatile var onWifi = 0
    val nextOnWifi: MutableList<Duration> = Collections.synchronizedList(mutableListOf())
    @Volatile var periodic = false

    @Synchronized
    override fun scheduleDownloads(userVisible: Boolean) {
      events += "downloads"
      downloads += userVisible
    }

    @Synchronized
    override fun sendNow() {
      sendNows++
    }

    @Synchronized
    override fun ensureOnWifi() {
      onWifi++
    }

    @Synchronized
    override fun nextOnWifi(delay: Duration) {
      nextOnWifi += delay
    }

    @Synchronized
    override fun ensurePeriodic() {
      periodic = true
    }

    @Synchronized
    override fun cancelUnpaired() {
      periodic = false
    }
  }

  /** The phone's storage, settable: 64 GB total and plenty free unless a test says otherwise. */
  class FakeSpace(var free: Long = 50L * GB, var total: Long = 64L * GB) : DeviceSpace {
    var waitingCopies = 0L
    var cache = 0L

    override fun allocatableBytes(): Long = free

    override fun totalBytes(): Long = total

    override fun waitingCopiesBytes(): Long = waitingCopies

    override fun cacheBytes(): Long = cache
  }

  class OpenChannel : ChannelState {
    val state = MutableStateFlow(false)
    override val open: StateFlow<Boolean> = state
  }

  /** An in-memory pairing store holding [initial]; the key is never needed (the fake client signs by itself). */
  class FakePairingStore(initial: PairingRecord?) : PairingStore {
    val state = MutableStateFlow(initial)

    override val pairing = state

    override suspend fun current(): PairingRecord? = state.value

    override suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long) =
      error("not used by the sync engine")

    override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> = DeviceKeyResult.NotPaired

    override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = Unit

    override suspend fun updateAddress(address: HostPort, at: Instant) = Unit

    override suspend fun updateLibraryId(libraryId: LibraryId) {
      state.value = state.value?.copy(libraryId = libraryId)
    }

    override suspend fun updateClockOffset(offsetS: Long) = Unit

    override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = Unit

    override suspend fun recordUpload(bytes: Long, duration: Duration) = Unit

    override suspend fun clear() {
      state.value = null
    }
  }

  companion object {
    const val GB = 1_000_000_000L
    val CONNECTED = ComputerStatus.Connected(name = "Desk", analyzing = 0, inLine = 0, lowOnSpace = false)

    fun record(serverId: ServerId, libraryId: LibraryId, deviceId: DeviceId) = PairingRecord(
      serverId = serverId,
      serverName = "Desk",
      address = HostPort("192.0.2.10", 8000),
      deviceId = deviceId,
      deviceName = "Test phone",
      libraryId = libraryId,
      pairedAt = Instant.EPOCH,
      lastSeen = null,
      apiVersion = 6,
      capabilities = null,
      clockOffsetS = 0,
      lastProbe = ProbeOutcome.Ok,
      lastProbeAt = null,
      addressChangedAt = null,
      uploadThroughput = null,
    )
  }
}

/** The study pack and video names of a finished clip of the fake computer. */
internal object Names {
  const val ANALYSIS = "analysis"
  const val MIX = "audio_mix"
  const val DIALOGUE = "audio_dialogue"
  const val ORIGINAL = "original"
}

/** The value of header [name] of a recorded call, whatever its case. */
internal fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/** A request id for tests. */
internal fun requestId(n: Int) = RequestId("00000000-0000-4000-8000-%012d".format(n))
