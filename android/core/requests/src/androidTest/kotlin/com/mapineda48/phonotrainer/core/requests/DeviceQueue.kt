package com.mapineda48.phonotrainer.core.requests

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.mapineda48.phonotrainer.core.common.id.UuidSource
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.requests.android.AndroidFreeSpace
import com.mapineda48.phonotrainer.core.requests.android.ContentSourceReader
import com.mapineda48.phonotrainer.core.requests.android.MediaAudioExtraction
import com.mapineda48.phonotrainer.core.requests.android.ShareIntakeWorker
import com.mapineda48.phonotrainer.core.requests.android.WorkManagerRequestWork
import com.mapineda48.phonotrainer.core.requests.intake.FileIntake
import com.mapineda48.phonotrainer.core.requests.intake.IntakeHandles
import com.mapineda48.phonotrainer.core.requests.intake.SourceReader
import com.mapineda48.phonotrainer.core.requests.store.ActiveUploads
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueFiles
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestWords
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSyncTrigger
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * The device queue wired by hand on the managed device (work-plan 2.4: no Hilt test component in core instrumented
 * tests), with the real content resolver (this test package's own provider allowed), the real free space, the real
 * audio extraction and the real WorkManager, whose worker factory builds the queue's workers from [current].
 */
internal class DeviceQueue : AutoCloseable {
  val context: Context = ApplicationProvider.getApplicationContext()
  val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  val files = QueueFiles(File(context.filesDir, "device-queue-${UUID.randomUUID()}").apply { mkdirs() })
  val handles = IntakeHandles()
  val live = QueueLive()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val content = ContentSourceReader(context)

  /** The real reader, except that this test package's own provider stands for another app's. */
  val reader: SourceReader = object : SourceReader by content {
    override fun refuses(uri: String): Boolean = !uri.startsWith("content://")
  }

  val work = WorkManagerRequestWork(context)

  val core = QueueCore(
    database = db,
    requests = db.requestDao(),
    ops = db.requestOpDao(),
    files = files,
    handles = handles,
    reader = reader,
    work = work,
    uploads = ActiveUploads(),
    words = Words,
    notifier = SilentNotifier,
    messages = FakeUserMessages(),
    trigger = FakeSyncTrigger(),
    shorts = FakeShortRepository(),
    client = FakeComputerClient(FakeComputer()),
    clock = TestClock(Instant.now()),
    ids = UuidSource.RANDOM,
    scope = scope,
    log = PhonoLog.NONE,
  )

  val intake = FileIntake(
    core = core,
    live = live,
    handles = handles,
    reader = reader,
    space = AndroidFreeSpace(context),
    extraction = MediaAudioExtraction(),
    work = work,
    pairing = NoPairing,
    io = Dispatchers.IO,
    log = PhonoLog.NONE,
  )

  init {
    current = this
    if (!WorkManager.isInitialized()) {
      WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(Factory).build())
    }
  }

  /** Waits until request [id] is in one of [states] (the intake work runs on its own); its state then. */
  suspend fun awaitState(id: RequestId, vararg states: String): String = withTimeout(AWAIT_MS) {
    var state = core.requests.get(id.value)?.state
    while (state !in states) {
      delay(POLL_MS)
      state = core.requests.get(id.value)?.state
    }
    checkNotNull(state)
  }

  override fun close() {
    current = null
    scope.cancel()
    db.close()
    files.root.parentFile?.deleteRecursively()
  }

  private object Words : RequestWords {
    override fun linkTitle(site: Site?): String = "Link"

    override fun fileTitle(video: Boolean): String = "Video"
  }

  private object SilentNotifier : Notifier {
    override fun clipReady(clip: JobId, title: String, shorts: Int) = Unit

    override fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError) = Unit

    override val permissionState: StateFlow<NotificationPermission> = MutableStateFlow(NotificationPermission.GRANTED)
  }

  private object NoPairing : PairingStore {
    override val pairing = MutableStateFlow<PairingRecord?>(null)

    override suspend fun current(): PairingRecord? = null

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

  /** Builds the queue's workers from the queue of the running test. */
  private object Factory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
      val queue = current ?: return null
      val transfers = TransferNotifier { _, progress, _ ->
        NotificationCompat.Builder(appContext, "transfers").setSmallIcon(android.R.drawable.stat_sys_upload)
          .setContentTitle(progress.title).build()
      }
      return when (workerClassName) {
        ShareIntakeWorker::class.java.name -> ShareIntakeWorker(
          appContext,
          workerParameters,
          queue.intake,
          FakeStartupGate(),
          transfers,
          queue.core,
          queue.live,
          PhonoLog.NONE,
        )
        else -> null
      }
    }
  }

  companion object {
    @Volatile var current: DeviceQueue? = null

    private const val AWAIT_MS = 60_000L
    private const val POLL_MS = 50L
  }
}
