package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.util.Locale

/**
 * The stateful fake PhonoTrainer computer of architecture 21.3 (AD-23), written from `server-contract.md` alone:
 * hello with identity proofs, pairing, signed requests, health, the listing, artifacts with `Range` and `If-Range`,
 * links, uploads (single `PUT` with the idle and "newest wins" rules and, v1-complete, `GET`/`PATCH` offsets), request
 * lookup, cancel, delete, re-run, reference, the job channel, renditions and record-yourself (v1-complete), and
 * discovery answers. It runs on the loopback interface on an ephemeral port; every failure mode of 21.3 is a one-line
 * call.
 *
 * ```
 * FakeComputer().start().use { computer ->
 *   val phone = computer.pairDevice()
 *   val id = computer.addFinishedClip("Ordering coffee")
 *   // point the code under test at computer.address, sign with computer.signerFor(phone) …
 *   computer.failNext("/api/phone/library", 503)
 * }
 * ```
 *
 * Every LAN client is treated as on an approved network (4.2); `lanForbidden()` models a suspended one. Times come
 * from [clock]; real time only bounds what is network-level (the `Slow` latency, discovery reply rates).
 */
class FakeComputer(
  val clock: FakeComputerClock = FakeComputerClock.running(),
  seed: Long = DEFAULT_SEED,
  name: String = DEFAULT_NAME,
  clips: ClipContentProvider = MinimalClip.provider,
) : AutoCloseable {
  private val core = ComputerCore(clock, seed, name, clips)
  private val network = FakeNetwork(core)
  private val server = MockWebServer()
  private val discoveryLimits = DiscoveryLimits()
  private var responder: DiscoveryResponder? = null
  private val impersonators = mutableListOf<Impersonator>()

  /** Starts listening on the loopback interface (an ephemeral port). */
  fun start(): FakeComputer {
    server.serverSocketFactory = network
    server.dispatcher = core
    server.start(InetAddress.getByName(HOST), 0)
    locked { core.port = server.port }
    return this
  }

  override fun close() {
    impersonators.forEach { it.close() }
    responder?.close()
    locked { core.dropChannels() }
    network.closeAll()
    server.close()
  }

  /** The computer's address, as discovery or the learner would give it. */
  val address: HostPort get() = HostPort(HOST, server.port)

  /** `http://<address><path>`. */
  fun url(path: String): HttpUrl = "http://$HOST:${server.port}$path".toHttpUrl()

  /** A signer for [phone] whose `ts` is this computer's own clock (always inside the window, server-contract 4.3). */
  fun signerFor(phone: PhoneCredentials): ReferenceSigner = ReferenceSigner(phone) { serverTimeS() }

  // ---------------------------------------------------------------------------------------------------------------
  // Identity and settings

  val serverId: ServerId get() = locked { ServerId(core.serverId) }
  val libraryId: LibraryId get() = locked { LibraryId(core.libraryId) }

  var name: String
    get() = locked { core.name }
    set(value) = locked { core.name = value }

  /** 6 by default; below 6 the computer is an old server (phone routes 404, `/api/health` answers, 6.2). */
  var apiVersion: Int
    get() = locked { core.apiVersion }
    set(value) = locked { core.apiVersion = value }

  var minAppVersion: Int
    get() = locked { core.minAppVersion }
    set(value) = locked { core.minAppVersion = value }

  var capabilities: FakeCapabilities
    get() = locked { core.capabilities }
    set(value) = locked { core.capabilities = value }

  var maxUploadBytes: Long
    get() = capabilities.maxUploadBytes
    set(value) = locked { core.capabilities = core.capabilities.copy(maxUploadBytes = value) }

  var maxLinkDurationS: Long
    get() = capabilities.maxLinkDurationS
    set(value) = locked { core.capabilities = core.capabilities.copy(maxLinkDurationS = value) }

  /** Free space of the workspace's filesystem before upload reservations (3.4); 50 GiB by default. */
  var freeBytes: Long
    get() = locked { core.freeBytes }
    set(value) = locked { core.freeBytes = value }

  var reachability: Reachability
    get() = core.reachability
    set(value) {
      core.reachability = value
    }

  /** The body of `GET /api/phone/reference`. */
  var referenceJson: String
    get() = locked { core.referenceJson }
    set(value) = locked { core.referenceJson = value }

  /** The pairing and challenge rate limits of 2.3 and 4.7 (on by default). */
  var rateLimits: Boolean
    get() = locked { core.rateLimits }
    set(value) = locked { core.rateLimits = value }

  /** When true a running job flagged by `cancel` stays `running` until its next [progress] or [finishJob] (6.7). */
  var deferRunningCancel: Boolean
    get() = locked { core.deferRunningCancel }
    set(value) = locked { core.deferRunningCancel = value }

  /** When true, renditions of jobs that finish start as `preparing` until [finishRenditions] (6.4.5). */
  var renditionsPreparing: Boolean
    get() = locked { core.renditionStatus == ArtifactStatus.PREPARING }
    set(value) = locked { core.renditionStatus = if (value) ArtifactStatus.PREPARING else ArtifactStatus.READY }

  /** What jobs that finish serve (MinimalClip until WP-12's AnalysisFixtures is merged). */
  var clips: ClipContentProvider
    get() = locked { core.clips }
    set(value) = locked { core.clips = value }

  /** API 7 with the four v1-complete capability flags on (server-contract 1.2, 3.4). */
  fun enableCompleteTier() = locked {
    core.apiVersion = ComputerCore.API_VERSION_COMPLETE
    core.capabilities = core.capabilities.copy(
      renditions = true, phoneVideo = true, resumableUploads = true, recordCompare = true,
    )
  }

  /** The computer's clock in unix seconds, including any [clockSkew]. */
  fun serverTimeS(): Long = locked { core.serverTimeS() }

  // ---------------------------------------------------------------------------------------------------------------
  // Pairing (2.3, 6.2)

  /** Creates the pairing code (`phonotrainer phone pair`), replacing any previous one; returns its six digits. */
  fun newPairingCode(code: String? = null): String = locked {
    val digits = code ?: String.format(Locale.ROOT, "%06d", core.random.nextInt(CODE_SPACE))
    require(digits.length == CODE_DIGITS && digits.all(Char::isDigit)) { "a pairing code is 6 digits" }
    core.pairing = PairingCode(digits, clock.nowMillis())
    digits
  }

  /** Pairs a phone directly in the registry (no HTTP), for tests that start paired. */
  fun pairDevice(name: String = "Test phone", appVersion: Int = 1): PhoneCredentials = locked {
    val token = core.newToken()
    val device = Device(core.newId("dev_"), name, PhoneCrypto.keyFromToken(token), clock.nowMillis(), appVersion, "1.0.0")
    core.devices[device.id] = device
    PhoneCredentials(DeviceId(device.id), token, ServerId(core.serverId))
  }

  val pairedDevices: List<PairedDevice>
    get() = locked {
      core.devices.values.map {
        PairedDevice(
          deviceId = DeviceId(it.id), name = it.name, appVersion = it.appVersion, appVersionName = it.appVersionName,
          pairedAtEpochS = it.pairedAtMillis / MILLIS, lastSeenAtEpochS = it.lastSeenAtMillis?.let { at -> at / MILLIS },
          revoked = it.revoked,
        )
      }
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Failure modes (architecture 21.3)

  /**
   * The next [times] requests whose path matches [path] (`*` matches one segment; [method] optional) get [status]
   * with the error envelope `{detail, code}` ([code] null leaves `code` out, as a plain 5xx does) and, for 429,
   * `Retry-After`.
   */
  fun failNext(
    path: String,
    status: Int,
    code: String? = null,
    times: Int = 1,
    method: String? = null,
    retryAfterS: Int? = null,
  ) = locked {
    core.faults += FaultRule(method, path, times, status = status, code = code, retryAfterS = retryAfterS)
  }

  /** The computer applies the next [times] matching requests, then the connection closes without an answer (QT-3). */
  fun applyThenDrop(path: String, times: Int = 1, method: String? = null) = locked {
    core.drops += FaultRule(method, path, times)
  }

  /** The next [times] downloads of [artifact] (a name of 6.4.1, or `*`) stop at file offset [atBytes] (QT-9). */
  fun truncate(artifact: String, atBytes: Long, times: Int = 1) = locked {
    core.truncations += FaultRule("GET", artifact, times, atBytes = atBytes)
  }

  /** The next [times] downloads of [artifact] have the right size and the wrong bytes (verification). */
  fun corrupt(artifact: String, times: Int = 1) = locked {
    core.corruptions += FaultRule("GET", artifact, times)
  }

  /**
   * The next [times] upload bodies (`PUT` or `PATCH`) stop arriving after [afterBytes] bytes: a Wi-Fi drop mid-upload
   * (QT-4). The computer then sees an idle body: a newer body for the same key replaces it after 10 s, and it is
   * aborted after 30 s, both on [clock] (server-contract 4.7, 6.6).
   */
  fun stallUpload(afterBytes: Long, times: Int = 1) = locked {
    core.stalls += FaultRule(null, "api/phone/uploads/*", times, atBytes = afterBytes)
  }

  /** Revokes [deviceId], or every paired phone (`phonotrainer phone revoke`, QT-8, F-02-AC8). */
  fun revokeDevice(deviceId: DeviceId? = null) = locked {
    core.devices.values.filter { deviceId == null || it.id == deviceId.value }.forEach {
      it.revokedAtMillis = clock.nowMillis()
    }
  }

  /** A different computer at this address (QT-11): a new server id and, by default, no paired phones. */
  fun changeServerId(forgetDevices: Boolean = true) = locked {
    core.serverId = core.newId("srv_")
    if (forgetDevices) core.devices.clear()
    core.dropChannels()
  }

  /** A workspace switch (QT-14): a new library id and, by default, none of the old jobs. */
  fun changeLibraryId(keepJobs: Boolean = false) = locked {
    core.libraryId = core.newId("lib_")
    if (!keepJobs) core.jobs.clear()
    core.dropChannels()
  }

  /** A second responder claiming this computer's server id and name, with no proof or a wrong one (F-02-AC11). */
  fun impersonate(proof: ProofMode): Impersonator {
    val (id, display, version) = locked { Triple(core.serverId, core.name, core.apiVersion) }
    return Impersonator(proof, id, display, version, ::serverTimeS, HOST).also { impersonators += it }
  }

  /** The computer's clock runs [seconds] ahead of what the phone learned (negative: behind), server-contract 4.3. */
  fun clockSkew(seconds: Long) = locked { core.skewS = seconds }

  /** Phone access switched off live: `hello` says so, every other phone route answers `403 phone_access_off`. */
  fun phoneAccessOff() = locked {
    core.phoneAccess = false
    core.dropChannels()
  }

  fun phoneAccessOn() = locked { core.phoneAccess = true }

  /** Phone access on but not approved for this network: every phone route, `hello` included, answers `403
   * lan_forbidden` and discovery is silent (server-contract 2.1, 4.2; F-02-AC12). */
  fun lanForbidden(forbidden: Boolean = true) = locked {
    core.lanForbidden = forbidden
    if (forbidden) core.dropChannels()
  }

  /**
   * The computer process restarts: connections and channels close, nonce and rate-limit memory is lost and, with
   * [interrupt], queued and running jobs fail with `interrupted` (8.2; QT-5, QT-12). Upload sessions survive.
   */
  fun restart(interrupt: Boolean = true) {
    locked {
      core.inFlight.toList().forEach(core::abortWatch)
      core.dropChannels()
      core.resetLimits()
      core.devices.values.forEach { it.nonces.clear() }
      if (interrupt) {
        core.jobs.values.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }.forEach {
          it.status = JobStatus.ERROR
          it.cancelRequested = false
          it.errorCode = "interrupted"
          it.errorMessage = JobCodes.message("interrupted")
          it.finishedAtMillis = core.serverNowMillis()
        }
      }
    }
    network.closeAll()
  }

  /** Lowers free space so uploads are refused (`507 no_space`) and `health` reports `low_on_space`. */
  fun lowOnSpace(low: Boolean = true) = locked {
    core.freeBytes = if (low) LOW_FREE_BYTES else ComputerCore.DEFAULT_FREE_BYTES
  }

  /** Closes every phone channel (the phone moves to Checking, architecture 5.5). */
  fun dropChannel() = locked { core.dropChannels() }

  /** How the SSRF guard resolves [host] in links (server-contract 4.5); names are public by default. */
  fun linkHost(host: String, answer: LinkHostAnswer) = locked { core.linkHosts[host.lowercase(Locale.ROOT)] = answer }

  // ---------------------------------------------------------------------------------------------------------------
  // Computer-side job events

  /** Seeds a job as the browser, the command line or a phone made it. */
  fun addJob(seed: JobSeed = JobSeed()): JobId = locked {
    val job = core.newJob(
      seed.kind, seed.url, seed.fileName, seed.audioOnly, seed.separateDialogue, seed.deviceId?.value,
      seed.clientRequestId?.value?.lowercase(Locale.ROOT), rerunOf = null,
    )
    job.source = if (seed.kind == SourceKind.IMPORT) null else FakeMediaBytes.video(salt = job.seq.toInt())
    when (seed.status) {
      JobStatus.QUEUED -> Unit
      JobStatus.RUNNING -> applyProgress(job, JobStage.TRANSCRIBE, SEEDED_PERCENT)
      JobStatus.DONE -> complete(job, seed.title, seed.media, seed.content)
      JobStatus.ERROR -> fail(job, seed.errorCode ?: "processing_failed", null)
      JobStatus.CANCELLED -> core.cancelNow(job, JobCodes.message("cancelled"))
    }
    core.publishChange(job)
    JobId(job.id)
  }

  /** Seeds a finished clip (a link from the browser unless [ownedBy] names a phone). */
  fun addFinishedClip(title: String = "Seeded clip", video: Boolean = true, ownedBy: DeviceId? = null): JobId =
    addJob(JobSeed(title = title, audioOnly = !video, deviceId = ownedBy))

  /** The analysis moves on: the job runs at [stage] with [percent] (a flagged cancel takes effect here). */
  fun progress(
    id: JobId,
    stage: JobStage,
    percent: Int,
    segment: Int? = null,
    segments: Int? = null,
    downloadPercent: Int? = null,
    separationSkipped: Boolean = false,
  ) = locked {
    val job = job(id.value)
    if (job.cancelRequested) {
      core.cancelNow(job, "Cancelled during the analysis.")
      return@locked
    }
    applyProgress(job, stage, percent)
    job.segment = segment
    job.segments = segments
    job.downloadPercent = downloadPercent
    if (separationSkipped) job.separationSkipped = true
    core.publishChange(job)
  }

  /** The job finishes (`done`, with its study pack); a flagged cancel makes it `cancelled` instead. */
  fun finishJob(id: JobId, title: String? = null, media: FakeMedia? = null, content: ClipContent? = null) = locked {
    val job = job(id.value)
    if (job.cancelRequested) {
      core.cancelNow(job, "Cancelled during the analysis.")
    } else {
      complete(job, title, media, content)
      core.publishChange(job)
    }
  }

  /** The job fails with a job code of server-contract 8.2 (`live_stream`, `too_long`, `interrupted`, …). */
  fun failJob(id: JobId, code: String, message: String? = null) = locked {
    require(code in JobCodes.ALL) { "unknown job code $code" }
    val job = job(id.value)
    fail(job, code, message)
    core.publishChange(job)
  }

  /** The job is deleted on the computer (the browser deletes it): a `deleted` event follows. */
  fun deleteJob(id: JobId) = locked { core.removeJob(id.value) }

  /** The stored file (an upload, or the original) is gone: re-runs get `410 source_gone`, `original` is unavailable. */
  fun removeSource(id: JobId) = locked {
    val job = job(id.value)
    job.source = null
    job.sourceGone = true
    job.artifacts[ArtifactNames.ORIGINAL]?.let {
      it.status = ArtifactStatus.UNAVAILABLE
      it.bytes = null
    }
    core.publish(job)
  }

  /** Sets one artifact's status (a rendition `failed` with [error], `preparing` after a restart, …). */
  fun setArtifactStatus(id: JobId, artifact: String, status: ArtifactStatus, error: String? = null) = locked {
    val job = job(id.value)
    val entry = job.artifacts[artifact] ?: error("job ${id.value} has no artifact $artifact")
    entry.status = status
    entry.error = error
    core.publish(job)
  }

  /** Every `preparing` rendition of the job becomes `ready` (the rendition worker finished, 6.4.5). */
  fun finishRenditions(id: JobId) = locked {
    val job = job(id.value)
    job.artifacts.values.filter { it.status == ArtifactStatus.PREPARING }.forEach { artifact ->
      artifact.status = ArtifactStatus.READY
      if (artifact.name == ArtifactNames.VIDEO_PHONE) {
        artifact.bytes = FakeMediaBytes.video(salt = job.seq.toInt() + 1)
        artifact.video = Triple("h264", PHONE_VIDEO_SHORT_SIDE * 16 / 9, PHONE_VIDEO_SHORT_SIDE)
      }
    }
    core.publish(job)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Observation

  /** Every request that reached the computer, in arrival order (and upload bodies that ended without an answer). */
  val requests: List<RecordedCall> get() = locked { core.calls.toList() }

  val jobs: List<FakeJobSnapshot> get() = locked { core.jobs.values.map(::snapshot) }

  fun job(id: JobId): FakeJobSnapshot? = locked { core.jobs[id.value]?.let(::snapshot) }

  /** The jobs created with [key] (idempotency: at most one, QT-2, QT-3). */
  fun jobsWithKey(key: RequestId): List<FakeJobSnapshot> = locked {
    core.jobs.values.filter { it.clientRequestId == key.value.lowercase(Locale.ROOT) }.map(::snapshot)
  }

  val uploadSessions: List<UploadSessionSnapshot>
    get() = locked {
      core.sessions.values.map {
        UploadSessionSnapshot(RequestId(it.key), DeviceId(it.deviceId), it.fileName, it.bytes, it.offset)
      }
    }

  val uploadsInFlight: List<UploadInFlight>
    get() = locked {
      core.inFlight.filter { !it.ended }.map {
        UploadInFlight(RequestId(it.key), it.head.method, it.receivedBytes, it.contentLength)
      }
    }

  val openChannels: Int get() = locked { core.channels.count { it.socket != null } }

  // ---------------------------------------------------------------------------------------------------------------
  // Discovery (5)

  /**
   * The reply this computer sends to [datagram] from [source], or null when it stays silent: not a valid probe,
   * phone access off or not approved here, an old server, unreachable, or over the reply rate (5.2 to 5.4).
   */
  fun answerProbe(datagram: ByteArray, source: String = HOST): ByteArray? {
    val nonce = DiscoveryProtocol.probeNonce(datagram) ?: return null
    if (!privateSource(source)) return null
    val reply = locked {
      val silent = !core.phoneAccess || core.lanForbidden || core.apiVersion < ComputerCore.API_VERSION_CORE ||
        core.unreachable || !core.capabilities.discovery
      if (silent) null else DiscoveryProtocol.reply(nonce, core.serverId, core.name, core.port, core.apiVersion)
    } ?: return null
    return reply.takeIf { discoveryLimits.allow(source) }
  }

  /** Serves [answerProbe] on a UDP socket of the loopback interface ([port] 0: ephemeral); returns its port. */
  fun startDiscoveryResponder(port: Int = 0): Int {
    val started = responder ?: DiscoveryResponder(HOST, port, ::answerProbe)
    responder = started
    return started.port
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Internals

  private inline fun <T> locked(block: () -> T): T = synchronized(core.lock, block)

  private fun job(id: String): Job = core.jobs[id] ?: error("no job $id on the fake computer")

  private fun applyProgress(job: Job, stage: JobStage, percent: Int) {
    job.status = JobStatus.RUNNING
    job.startedAtMillis = job.startedAtMillis ?: core.serverNowMillis()
    job.stage = stage
    job.percent = percent.coerceIn(0, MAX_PERCENT)
  }

  private fun complete(job: Job, title: String?, media: FakeMedia?, content: ClipContent?) {
    job.status = JobStatus.DONE
    job.stage = null
    job.percent = MAX_PERCENT
    job.segment = null
    job.segments = null
    job.downloadPercent = null
    job.errorCode = null
    job.errorMessage = null
    job.startedAtMillis = job.startedAtMillis ?: core.serverNowMillis()
    job.finishedAtMillis = core.serverNowMillis()
    job.title = title ?: job.title ?: "Clip ${job.id}"
    job.media = media ?: defaultMedia(job)
    val separate = job.separateDialogue && core.capabilities.dialogueSeparation && !job.separationSkipped
    val clip = content ?: core.clips.contentFor(job.title, separate)
    job.content = clip
    buildArtifacts(job, clip)
  }

  private fun fail(job: Job, code: String, message: String?) {
    job.status = if (code == "cancelled") JobStatus.CANCELLED else JobStatus.ERROR
    job.cancelRequested = false
    job.errorCode = code
    job.errorMessage = message ?: JobCodes.message(code)
    job.finishedAtMillis = core.serverNowMillis()
  }

  private fun defaultMedia(job: Job): FakeMedia = when (job.kind) {
    SourceKind.LINK -> if (job.audioOnly) FakeMedia.AUDIO else FakeMedia()
    SourceKind.IMPORT -> FakeMedia(MediaKind.NONE, null, null, null)
    else -> if (videoSuffix(job.fileName)) FakeMedia() else FakeMedia.AUDIO
  }

  private fun buildArtifacts(job: Job, clip: ClipContent) {
    job.artifacts.clear()
    fun add(name: String, role: String, type: String, bytes: ByteArray?, status: ArtifactStatus = ArtifactStatus.READY) {
      job.artifacts[name] = Artifact(name, role, type, if (bytes == null) ArtifactStatus.UNAVAILABLE else status, bytes)
    }
    add(ArtifactNames.ANALYSIS, "clip_data", "application/json", clip.analysisJson)
    add(ArtifactNames.AUDIO_MIX, "audio_mix", "audio/wav", clip.audioMix)
    clip.audioDialogue?.let { add(ArtifactNames.AUDIO_DIALOGUE, "audio_dialogue", "audio/wav", it) }
    val originalSuffix = if (job.kind == SourceKind.LINK) "mp4" else job.fileName?.substringAfterLast('.', "")
    val hasVideoOriginal = when (job.kind) {
      SourceKind.LINK -> !job.audioOnly
      SourceKind.IMPORT -> false
      else -> videoSuffix(job.fileName)
    }
    if (hasVideoOriginal) {
      add(ArtifactNames.ORIGINAL, "video", mediaType(originalSuffix), job.source?.takeIf { !job.sourceGone })
    }
    if (core.capabilities.renditions) {
      val rendition = core.renditionStatus
      val compact = Json.parseToJsonElement(clip.analysisJson.toString(Charsets.UTF_8)).toString()
      add(
        ArtifactNames.ANALYSIS_COMPACT, "clip_data", "application/gzip",
        FakeMediaBytes.gzip(compact.toByteArray(Charsets.UTF_8)), rendition,
      )
      add(ArtifactNames.AUDIO_MIX_OPUS, "audio_mix", "audio/ogg", FakeMediaBytes.opus(salt = job.seq.toInt()), rendition)
      if (clip.audioDialogue != null) {
        add(
          ArtifactNames.AUDIO_DIALOGUE_OPUS, "audio_dialogue", "audio/ogg",
          FakeMediaBytes.opus(salt = job.seq.toInt() + 1), rendition,
        )
      }
      if (job.media?.kind == MediaKind.VIDEO) {
        add(ArtifactNames.POSTER, "poster", "image/jpeg", FakeMediaBytes.jpeg(salt = job.seq.toInt()), rendition)
      }
    }
  }

  private fun snapshot(job: Job) = FakeJobSnapshot(
    id = JobId(job.id), status = job.status, stage = job.stage, percent = job.percent, kind = job.kind, url = job.url,
    fileName = job.fileName, title = job.title, deviceId = job.deviceId?.let(::DeviceId),
    clientRequestId = job.clientRequestId?.let(::RequestId), rerunOf = job.rerunOf?.let(::JobId),
    audioOnly = job.audioOnly, separateDialogue = job.separateDialogue, errorCode = job.errorCode,
    revision = job.revision, artifacts = job.artifacts.mapValues { it.value.status }, sourceBytes = job.source,
  )

  companion object {
    const val HOST = "127.0.0.1"
    const val DEFAULT_SEED = 20_261_010L
    const val DEFAULT_NAME = "PhonoTrainer computer"

    private const val MILLIS = 1_000L
    private const val CODE_SPACE = 1_000_000
    private const val CODE_DIGITS = 6
    private const val MAX_PERCENT = 100
    private const val SEEDED_PERCENT = 40
    private const val PHONE_VIDEO_SHORT_SIDE = 720
    private const val LOW_FREE_BYTES = 1L * FakeCapabilities.GIB

    private val VIDEO_SUFFIXES = setOf("webm", "mp4", "mkv", "mov", "avi", "m4v")
    private val MEDIA_TYPES = mapOf(
      "webm" to "video/webm", "mp4" to "video/mp4", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
      "avi" to "video/x-msvideo", "m4v" to "video/x-m4v",
    )

    private fun videoSuffix(fileName: String?): Boolean =
      fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in VIDEO_SUFFIXES

    private fun mediaType(suffix: String?): String = MEDIA_TYPES[suffix?.lowercase(Locale.ROOT)] ?: "video/mp4"

    /** A loopback, private or link-local IPv4 source (5.4; the tests' loopback counts as the home network). */
    private fun privateSource(source: String): Boolean {
      val octets = source.split('.').mapNotNull { it.toIntOrNull() }
      if (octets.size != 4) return false
      val (a, b) = octets
      return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
  }
}
