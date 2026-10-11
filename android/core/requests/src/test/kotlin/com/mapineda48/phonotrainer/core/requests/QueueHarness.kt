package com.mapineda48.phonotrainer.core.requests

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mapineda48.phonotrainer.core.analysis.links.LinksV1
import com.mapineda48.phonotrainer.core.common.id.UuidSource
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.requests.intake.AudioExtraction
import com.mapineda48.phonotrainer.core.requests.intake.ExtractorInput
import com.mapineda48.phonotrainer.core.requests.intake.FileIntake
import com.mapineda48.phonotrainer.core.requests.intake.FreeSpace
import com.mapineda48.phonotrainer.core.requests.intake.IntakeHandles
import com.mapineda48.phonotrainer.core.requests.intake.SourceFacts
import com.mapineda48.phonotrainer.core.requests.intake.SourceReader
import com.mapineda48.phonotrainer.core.requests.intake.SourceStream
import com.mapineda48.phonotrainer.core.requests.reconcile.QueueReconciler
import com.mapineda48.phonotrainer.core.requests.send.QueueSender
import com.mapineda48.phonotrainer.core.requests.send.UploadForeground
import com.mapineda48.phonotrainer.core.requests.send.UploadPause
import com.mapineda48.phonotrainer.core.requests.send.Uploader
import com.mapineda48.phonotrainer.core.requests.startup.RequestsStartupTask
import com.mapineda48.phonotrainer.core.requests.store.ActiveUploads
import com.mapineda48.phonotrainer.core.requests.store.AppVisibility
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueFiles
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.store.RequestWords
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClock
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSyncTrigger
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.Created
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The device queue wired by hand over an in-memory `library.db` and the in-memory fake computer (work-plan 2.4: core
 * tests wire the fakes they need). Every collaborator the queue does not own is a fake the test can set.
 */
internal class QueueHarness(scope: CoroutineScope) : AutoCloseable {
  val context: Context = ApplicationProvider.getApplicationContext()
  val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  val clock = TestClock(Instant.ofEpochSecond(FakeComputerClock.REFERENCE_EPOCH_S))
  val computer = FakeComputer(clock = FakeComputerClock.frozen())
  val fakeClient = FakeComputerClient(computer, computer.pairDevice())
  val client = GatedClient(fakeClient)
  val status = FakeComputerStatusRepository(CONNECTED)
  val pairing = FakePairingStore(record(computer.serverId))
  val filesDir: File = File(context.filesDir, "queue-${System.nanoTime()}").apply { mkdirs() }
  val files = QueueFiles(filesDir)
  val handles = IntakeHandles()
  val reader = FakeSourceReader()
  val space = FakeFreeSpace()
  val extraction = FakeAudioExtraction()
  val work = RecordingWork()
  val uploads = ActiveUploads()
  val notifier = RecordingNotifier()
  val messages = FakeUserMessages()
  val trigger = FakeSyncTrigger()
  val shorts = FakeShortRepository()
  val live = QueueLive()
  val visibility = FakeVisibility()
  private val counter = AtomicInteger()
  private val ids = UuidSource { "00000000-0000-4000-8000-%012d".format(counter.incrementAndGet()) }

  /** The upload's waits move both clocks, so the fake computer's "newest wins" after 10 s can happen at once. */
  val pause = UploadPause { duration ->
    computer.clock.advance(duration)
    clock.advanceBy(duration)
  }

  val core = QueueCore(
    database = db,
    requests = db.requestDao(),
    ops = db.requestOpDao(),
    files = files,
    handles = handles,
    reader = reader,
    work = work,
    uploads = uploads,
    words = Words,
    notifier = notifier,
    messages = messages,
    trigger = trigger,
    shorts = shorts,
    client = client,
    clock = clock,
    ids = ids,
    scope = scope,
    log = PhonoLog.NONE,
  )
  val intake = FileIntake(core, live, handles, reader, space, extraction, work, pairing, Dispatchers.IO, PhonoLog.NONE)
  val sender = QueueSender(core, live, client, status, pairing, trigger, work, visibility, scope, PhonoLog.NONE)
  val repository = RoomRequestRepository(core, live, handles, intake, sender, status, pairing, LinksV1(), Words, PhonoLog.NONE)
  val reconciler = QueueReconciler(core, live, pairing, PhonoLog.NONE)
  val uploader = Uploader(core, live, sender, client, status, pairing, uploads, FakeStartupGate(), pause, PhonoLog.NONE)
  val startup = RequestsStartupTask(core, work, status, scope, PhonoLog.NONE)

  suspend fun request(id: RequestId): Request = repository.request(id).first()!!

  suspend fun row(id: RequestId): RequestEntity? = core.requests.get(id.value)

  /** Runs the intake work the queue asked for, as WorkManager would. */
  suspend fun runIntakes() {
    while (true) {
      val id = work.intakes.removeFirstOrNull() ?: return
      intake.copy(id)
    }
  }

  /** Puts a computer's job straight into the catalogue mirror (what the sync engine's reconcile writes). */
  fun catalogueClip(job: JobId, title: String, sourceUrl: String?) {
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO clip (job_id, server_id, library_id, origin, revision, status, title, source_url, first_seen_at) " +
        "VALUES (?, ?, ?, 'ACTIVE', 'r', 'DONE', ?, ?, 1)",
      arrayOf(job.value, computer.serverId.value, computer.libraryId.value, title, sourceUrl),
    )
  }

  override fun close() {
    db.close()
    filesDir.deleteRecursively()
  }

  companion object {
    val CONNECTED = ComputerStatus.Connected(name = "Desk", analyzing = 0, inLine = 0, lowOnSpace = false)

    fun capabilities(maxUploadBytes: Long = 2L * 1024 * 1024 * 1024, resumable: Boolean = false) = Capabilities(
      linkDownloads = true,
      downloaderVersion = "2026.8.19",
      dialogueSeparation = true,
      renditions = false,
      phoneVideo = false,
      resumableUploads = resumable,
      recordCompare = false,
      maxUploadBytes = maxUploadBytes,
      maxChunkBytes = 8L * 1024 * 1024,
      maxLinkDurationS = 3_600,
      freeBytes = 100L * 1024 * 1024 * 1024,
    )

    fun record(serverId: ServerId, throughput: Double? = null, capabilities: Capabilities? = capabilities()) = PairingRecord(
      serverId = serverId,
      serverName = "Desk",
      address = HostPort("192.0.2.10", 8000),
      deviceId = DeviceId("dev_test"),
      deviceName = "Test phone",
      libraryId = LibraryId("lib_test"),
      pairedAt = Instant.EPOCH,
      lastSeen = null,
      apiVersion = 6,
      capabilities = capabilities,
      clockOffsetS = 0,
      lastProbe = ProbeOutcome.Ok,
      lastProbeAt = null,
      addressChangedAt = null,
      uploadThroughput = throughput,
    )
  }
}

internal object Words : RequestWords {
  override fun linkTitle(site: Site?): String = "${site ?: "Website"} link"

  override fun fileTitle(video: Boolean): String = if (video) "Video" else "Audio file"
}

internal class FakeVisibility(var visible: Boolean = false) : AppVisibility {
  override fun visible(): Boolean = visible
}

/** The fake computer's client, with a gate a test can close in front of the upload body. */
internal class GatedClient(private val delegate: FakeComputerClient) : ComputerClient by delegate {
  /** When set, `putUpload` waits for it after [putStarted] completes. */
  var putGate: CompletableDeferred<Unit>? = null
  var putStarted = CompletableDeferred<Unit>()
  var puts = 0

  override suspend fun putUpload(key: RequestId, file: File, onProgress: (Long) -> Unit): ApiResult<Created<RemoteJob>> {
    puts++
    putStarted.complete(Unit)
    putGate?.await()
    return delegate.putUpload(key, file, onProgress)
  }
}

/** A visible transfer the test allows or refuses. */
internal class FakeForeground(var visible: Boolean = false, var allowed: Boolean = true) : UploadForeground {
  val promoted = mutableListOf<TransferProgress>()

  override fun appVisible(): Boolean = visible

  override suspend fun promote(progress: TransferProgress): Boolean {
    if (!allowed) return false
    promoted += progress
    return true
  }

  override suspend fun progress(progress: TransferProgress) = Unit
}

internal class RecordingWork : RequestWork {
  val intakes = ArrayDeque<RequestId>()
  val shared = mutableListOf<RequestId>()
  val picked = mutableListOf<RequestId>()
  val stopped = mutableListOf<RequestId>()
  val uploads = mutableListOf<Boolean>()

  override fun startShareIntake(id: RequestId) {
    shared += id
    intakes += id
  }

  override fun startFileIntake(id: RequestId) {
    picked += id
    intakes += id
  }

  override fun stopIntake(id: RequestId) {
    stopped += id
    intakes.remove(id)
  }

  override fun scheduleUploads(userVisible: Boolean) {
    uploads += userVisible
  }
}

internal class RecordingNotifier : Notifier {
  val ready = mutableListOf<Triple<JobId, String, Int>>()
  val attention = mutableListOf<Pair<RequestId, RequestError>>()
  val attentionTitles = mutableListOf<String>()

  override fun clipReady(clip: JobId, title: String, shorts: Int) {
    ready += Triple(clip, title, shorts)
  }

  override fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError) {
    attention += request to error
    attentionTitles += title
  }

  override val permissionState: StateFlow<NotificationPermission> = MutableStateFlow(NotificationPermission.GRANTED)
}

internal class FakeFreeSpace(var allocatable: () -> Long = { 50L * 1024 * 1024 * 1024 }, var floor: Long = 1_000_000_000) : FreeSpace {
  override fun allocatableBytes(): Long = allocatable()

  override fun floorBytes(): Long = floor
}

/** The audio of a test video: the extension it reports, or null (whole-file fallback). */
internal class FakeAudioExtraction(var extension: String? = "m4a", var audio: ByteArray = ByteArray(1_000) { 7 }) : AudioExtraction {
  var calls = 0

  override fun extract(input: ExtractorInput, target: File, onProgress: (Long) -> Unit): String? {
    calls++
    val ext = extension ?: return null
    target.writeBytes(audio)
    onProgress(audio.size.toLong())
    return ext
  }
}

/** A provider's item: its real bytes and what the provider claims about it. */
internal class FakeItem(
  val bytes: ByteArray,
  val name: String? = "VID_0001.mp4",
  val declaredSize: Long? = bytes.size.toLong(),
  val mime: String? = "video/mp4",
  val seekable: Boolean = true,
  /** Opening fails (permission gone, file removed). */
  var unreadable: Boolean = false,
  /** Reading fails after this many bytes. */
  var failAfter: Long? = null,
  /** Reading stops after this many bytes until [release] (a slow provider), after saying so on [reached]. */
  val blockAfter: Long? = null,
) {
  val reached = CountDownLatch(1)
  val release = CountDownLatch(1)
}

internal class FakeSourceReader : SourceReader {
  val items = mutableMapOf<String, FakeItem>()
  val own = mutableSetOf<String>()
  val kept = mutableSetOf<String>()
  val released = mutableListOf<String>()
  var opens = 0

  fun add(uri: String, item: FakeItem): String {
    items[uri] = item
    return uri
  }

  override fun refuses(uri: String): Boolean = !uri.startsWith("content://") || uri.substringAfter("content://").substringBefore('/') in own

  override fun facts(uri: String): SourceFacts =
    items[uri]?.let { SourceFacts(it.name, it.declaredSize, it.mime) } ?: SourceFacts(null, null, null)

  override fun open(uri: String): SourceStream {
    val item = items[uri] ?: throw IOException("no such item")
    if (item.unreadable) throw SecurityException("permission revoked")
    opens++
    return FakeStream(item)
  }

  override fun keepReadable(uri: String): Boolean = kept.add(uri) || true

  override fun release(uri: String) {
    released += uri
    kept -= uri
  }
}

internal class FakeStream(private val item: FakeItem) : SourceStream {
  private var consumed = false
  var closed = false

  override val seekable: Boolean = item.seekable

  override fun rewind(): Boolean {
    if (!consumed) return true
    if (!seekable) return false
    consumed = false
    return true
  }

  override fun stream(): InputStream {
    consumed = true
    val base = ByteArrayInputStream(item.bytes)
    item.blockAfter?.let { block ->
      return object : InputStream() {
        var read = 0L

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (read >= block) {
            item.reached.countDown()
            item.release.await(BLOCK_SECONDS, TimeUnit.SECONDS)
          }
          val n = base.read(b, off, if (read < block) minOf(len.toLong(), block - read).toInt() else len)
          if (n > 0) read += n
          return n
        }
      }
    }
    val limit = item.failAfter
    return if (limit == null) base else object : InputStream() {
      var read = 0L

      override fun read(): Int {
        if (read >= limit) throw IOException("the provider stopped answering")
        read++
        return base.read()
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= limit) throw IOException("the provider stopped answering")
        val n = base.read(b, off, minOf(len.toLong(), limit - read).toInt())
        if (n > 0) read += n
        return n
      }
    }
  }

  override fun extractorInput(): ExtractorInput? = if (seekable) ExtractorInput.Path(File("unused")) else null

  private companion object {
    const val BLOCK_SECONDS = 10L
  }

  override fun close() {
    closed = true
  }
}

/** The pairing store of the paired fake computer; [recordUpload] keeps the rates it is told. */
internal class FakePairingStore(initial: PairingRecord?) : PairingStore {
  val state = MutableStateFlow(initial)
  val uploads = mutableListOf<Pair<Long, Duration>>()

  override val pairing = state

  override suspend fun current(): PairingRecord? = state.value

  override suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long) = Unit

  override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> = DeviceKeyResult.NotPaired

  override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = Unit

  override suspend fun updateAddress(address: HostPort, at: Instant) = Unit

  override suspend fun updateLibraryId(libraryId: LibraryId) = Unit

  override suspend fun updateClockOffset(offsetS: Long) = Unit

  override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = Unit

  override suspend fun recordUpload(bytes: Long, duration: Duration) {
    uploads += bytes to duration
  }

  override suspend fun clear() {
    state.value = null
  }
}
