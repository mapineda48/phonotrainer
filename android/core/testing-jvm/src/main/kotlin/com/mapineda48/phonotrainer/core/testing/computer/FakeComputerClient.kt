package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.Created
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.network.api.UploadChunk
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okio.Buffer
import okio.ByteString
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel as CoroutineChannel

/**
 * An in-memory [ComputerClient] over [FakeComputer]s (architecture 21.3, work-plan 4.6): what connectivity, sync and
 * requests use before, and beside, the HTTP client of `:core:network`. No socket is opened (the computers need not be
 * started); each call is handed to the computer at the address it names through the same request handling the HTTP
 * fake uses, so every route, error code, knob (`failNext`, `applyThenDrop`, `revokeDevice`, `stallUpload`,
 * `clockSkew`, `impersonate`, `lanForbidden`, `reachability` …) and observation (`requests`, `jobsWithKey`,
 * `pairedDevices`) behaves the same in memory and over HTTP.
 *
 * Like the production client it builds every request of server-contract 6.10, signs it (4.3) with [credentials] and
 * the phone's clock plus the learned offset, retries once after `401 clock_skew`, checks `hello`'s identity proof,
 * and maps answers to [ApiResult]: `Ok` for 2xx, `Refused` with the error envelope's code, detail, `job`, `offset`,
 * `server_time` and `Retry-After` for the rest, and `Failed` for no answer (`Unreachable(NoAnswer)` with the
 * architecture 5.1 timeout), a lost answer (`Unreachable(Reset)`), no current computer (`Guarded`) or an answer it
 * cannot read (`Malformed`). It has no cleartext guard and no network binding.
 *
 * ```
 * val computer = FakeComputer()
 * val client = FakeComputerClient(computer, computer.pairDevice())   // or client.pair(computer.address, …)
 * client.library()                                                  // ApiResult.Ok(RemoteLibrary(…), 200)
 * computer.failNext("/api/phone/library", 503)
 * client.library()                                                  // ApiResult.Refused(503, "", …)
 * client.transfer.download(DownloadRequest(…), onProgress = {})     // the artifacts, with Range and If-Range
 * client.channel.open().collect { … }                               // hello, snapshot, job and deleted events
 * ```
 *
 * @param computers every computer this phone can reach; their impersonators are reachable too.
 * @param credentials the paired phone's identity; a successful [pair] replaces it with the new grant.
 * @param currentPeer the address signed calls go to (`AllowedPeers.current`); the first computer by default. Null
 *   fails them with `Guarded`.
 * @param phoneTimeS the phone's clock in unix seconds; the first computer's clock without its skew by default.
 * @param clockOffset told about every `server_time` a `clock_skew` refusal carries, as the signing interceptor does.
 * @param verifiedPeers when given, requests are signed only for verified addresses (the signing interceptor's rule);
 *   when null every signed call is signed.
 * @param waitForTimeouts when true a call that gets no answer suspends for its timeout first (virtual time under
 *   `runTest`); by default it fails at once.
 */
class FakeComputerClient(
  private val computers: List<FakeComputer>,
  credentials: PhoneCredentials? = null,
  private val currentPeer: () -> HostPort? = { computers.first().address },
  private val phoneTimeS: () -> Long = { computers.first().clock.nowSeconds() },
  private val clockOffset: ClockOffset? = null,
  private val verifiedPeers: VerifiedPeers? = null,
  private val waitForTimeouts: Boolean = false,
) : ComputerClient {
  constructor(computer: FakeComputer, credentials: PhoneCredentials? = null) : this(listOf(computer), credentials)

  init {
    require(computers.isNotEmpty()) { "a fake client needs at least one computer" }
  }

  private val wire = InMemoryWire { computers }

  // Nonces and challenges must never repeat across clients of one device (server-contract 4.3), so they are random.
  private val random: Random = Random.Default

  /** The identity signed calls use; set by a successful [pair]. */
  @Volatile var credentials: PhoneCredentials? = credentials

  /** The computer's clock minus the phone's, learned from `hello`, pairing and `clock_skew` (architecture 5.3). */
  @Volatile var clockOffsetS: Long = 0
    private set

  // ---------------------------------------------------------------------------------------------------------------
  // Identity, pairing, health (server-contract 6.2)

  override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> {
    val challenge = challengeFor?.let { PhoneCrypto.newNonce(random) }
    val url = url(at, "hello") {
      if (challengeFor != null) {
        addQueryParameter("device_id", challengeFor.value)
        addQueryParameter("challenge", challenge)
      }
    }
    val answer = send(at, Outgoing("GET", url, headers(at)), Timeout.PROBE)
    return result(answer, Timeout.PROBE) { response ->
      val body = PhoneJson.parse(response.body)
      val checked = if (challengeFor != null && challenge != null) proofValid(body, challengeFor, challenge) else null
      val identity = PhoneJson.identity(body, proofValid = checked)
      learnServerTime(identity.serverTime)
      identity
    }
  }

  override suspend fun legacyHealth(at: HostPort): ApiResult<Int> {
    val url = HttpUrl.Builder().scheme("http").host(at.host).port(at.port).addPathSegments("api/health").build()
    val answer = send(at, Outgoing("GET", url, headers(at)), Timeout.PROBE)
    return result(answer, Timeout.PROBE) { response ->
      (PhoneJson.parse(response.body)["api_version"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: throw MalformedAnswer("api_version")
    }
  }

  override suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant> {
    val body = buildJsonObject {
      put("code", request.code)
      put("device_name", request.deviceName)
      put("app_version", request.appVersion)
      put("app_version_name", request.appVersionName)
      put("previous_device_id", request.previousDeviceId?.value)
      put("previous_token_proof", request.previousTokenProof)
    }
    val answer = send(at, json("POST", url(at, "pair"), at, body), Timeout.API)
    return result(answer, Timeout.API) { response ->
      PhoneJson.grant(PhoneJson.parse(response.body)).also { grant ->
        credentials = grant.toCredentials()
        learnServerTime(grant.serverTime)
      }
    }
  }

  override suspend fun health(): ApiResult<ComputerHealth> =
    signed("GET", listOf("health"), timeout = Timeout.PROBE) { PhoneJson.health(PhoneJson.parse(it.body)) }

  override suspend fun unpairSelf(): ApiResult<Unit> = signed("DELETE", listOf("devices", "self")) { }

  // ---------------------------------------------------------------------------------------------------------------
  // Library, jobs and reference (6.3, 6.8)

  override suspend fun library(): ApiResult<RemoteLibrary> =
    signed("GET", listOf("library")) { PhoneJson.library(PhoneJson.parse(it.body)) }

  override suspend fun job(id: JobId): ApiResult<RemoteJob> =
    signed("GET", listOf("jobs", id.value)) { PhoneJson.job(PhoneJson.parse(it.body)) }

  override suspend fun requestLookup(key: RequestId): ApiResult<RemoteJob> =
    signed("GET", listOf("requests", key.value)) { jobOf(it) }

  override suspend fun reference(): ApiResult<String> =
    signed("GET", listOf("reference")) { it.body.toString(Charsets.UTF_8) }

  // ---------------------------------------------------------------------------------------------------------------
  // Links and uploads (6.5, 6.6)

  override suspend fun createLink(body: LinkCreate): ApiResult<Created<RemoteJob>> {
    val json = buildJsonObject {
      put("client_request_id", body.clientRequestId.value)
      put("url", body.url)
      put("audio_only", body.audioOnly)
      body.separateDialogue?.let { put("separate_dialogue", it) }
    }
    return signed("POST", listOf("jobs", "link"), json = json) { created(it) }
  }

  override suspend fun openUpload(body: UploadDeclaration): ApiResult<UploadState> {
    val json = buildJsonObject {
      put("client_request_id", body.clientRequestId.value)
      put("file_name", body.fileName)
      put("bytes", body.bytes)
      put("sha256", body.sha256)
      body.separateDialogue?.let { put("separate_dialogue", it) }
    }
    return signed("POST", listOf("uploads"), json = json) { PhoneJson.uploadState(PhoneJson.parse(it.body)) }
  }

  override suspend fun putUpload(
    key: RequestId,
    file: File,
    onProgress: (Long) -> Unit,
  ): ApiResult<Created<RemoteJob>> {
    val bytes = file.readBytes()
    val extra = Headers.headersOf("Content-Type", OCTETS)
    val path = listOf("uploads", key.value)
    return signed("PUT", path, body = bytes, extra = extra, timeout = Timeout.UPLOAD, onBody = onProgress) {
      created(it)
    }
  }

  override suspend fun uploadStatus(key: RequestId): ApiResult<UploadState> =
    signed("GET", listOf("uploads", key.value)) { PhoneJson.uploadState(PhoneJson.parse(it.body)) }

  override suspend fun patchUpload(key: RequestId, file: File, offset: Long, length: Long): ApiResult<UploadChunk> {
    val chunk = readRange(file, offset, length)
    val extra = Headers.headersOf("Content-Type", OFFSET_OCTETS, "Upload-Offset", offset.toString())
    return signed("PATCH", listOf("uploads", key.value), body = chunk, extra = extra, timeout = Timeout.UPLOAD) {
      if (it.status == ComputerCore.HTTP_NO_CONTENT) {
        UploadChunk.Accepted(it.headers["Upload-Offset"]?.toLongOrNull() ?: throw MalformedAnswer("Upload-Offset"))
      } else {
        UploadChunk.Finished(created(it))
      }
    }
  }

  override suspend fun deleteUpload(key: RequestId): ApiResult<Unit> =
    signed("DELETE", listOf("uploads", key.value)) { }

  // ---------------------------------------------------------------------------------------------------------------
  // Cancel, delete, re-run (6.7)

  override suspend fun cancel(id: JobId): ApiResult<RemoteJob> =
    signed("POST", listOf("jobs", id.value, "cancel"), body = ByteArray(0)) { jobOf(it) }

  override suspend fun deleteJob(id: JobId): ApiResult<Unit> = signed("DELETE", listOf("jobs", id.value)) { }

  override suspend fun rerun(id: JobId, body: RerunBody): ApiResult<Created<RemoteJob>> {
    val json = buildJsonObject {
      put("client_request_id", body.clientRequestId.value)
      body.audioOnly?.let { put("audio_only", it) }
    }
    return signed("POST", listOf("jobs", id.value, "rerun"), json = json) { created(it) }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // v1-complete: phone video, record yourself (6.4.4, 6.9)

  override suspend fun requestPhoneVideo(id: JobId): ApiResult<RemoteArtifactStatus> =
    signed("POST", listOf("jobs", id.value, "renditions", "video_phone"), body = ByteArray(0)) {
      PhoneJson.artifactStatus((PhoneJson.parse(it.body)["status"] as? JsonPrimitive)?.content)
    }

  override suspend fun contour(id: JobId, startS: Double, endS: Double): ApiResult<String> =
    signed("GET", listOf("jobs", id.value, "contour"), params = listOf("start" to "$startS", "end" to "$endS")) {
      it.body.toString(Charsets.UTF_8)
    }

  override suspend fun compare(
    id: JobId,
    take: File,
    contentType: String,
    startS: Double,
    endS: Double,
  ): ApiResult<String> {
    val form = MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart("file", take.name, take.readBytes().toRequestBody(contentType.toMediaType()))
      .addFormDataPart("start", "$startS")
      .addFormDataPart("end", "$endS")
      .build()
    val bytes = Buffer().also(form::writeTo).readByteArray()
    val extra = Headers.headersOf("Content-Type", form.contentType().toString())
    return signed("POST", listOf("jobs", id.value, "compare"), body = bytes, extra = extra, timeout = Timeout.UPLOAD) {
      it.body.toString(Charsets.UTF_8)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Artifact transfer (architecture 7.4) and the job channel (5.5)

  /**
   * Downloads artifacts from the current computer in memory, signed, with the resume rules of architecture 7.4: a
   * part already [DownloadRequest.expectedBytes] long is never requested; `Range` and `If-Range` when resuming; `206`
   * appends to the part; a `200` where `206` was expected empties the part and gives
   * [DownloadOutcome.RestartedFromZero] (the caller downloads again from 0); `416` gives
   * [DownloadOutcome.RangeNotSatisfiable]; a `truncate` knob writes what arrived and fails with
   * `Unreachable(Reset)`. [DownloadRequest.url] is the listing's `url` (relative) or an absolute `http://` URL.
   * `onProgress` receives the part's length after each chunk.
   */
  val transfer: ArtifactTransfer = object : ArtifactTransfer {
    override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome =
      this@FakeComputerClient.download(request, onProgress)
  }

  /**
   * The job channel of the current computer, in memory: `hello`, `snapshot`, then `job` and `deleted` events as the
   * computer changes (server-contract 7). The flow fails with an [IOException] when the upgrade is refused or gets no
   * answer, and when the computer drops the channel (`dropChannel`, a restart, phone access off, a revoked device,
   * `reachability = Unreachable`). Cancelling the collection closes the channel on the computer.
   */
  val channel: JobChannel = JobChannel { openChannel() }

  // ---------------------------------------------------------------------------------------------------------------
  // Internals

  /** The call timeouts of architecture 5.1, in seconds: what `NoAnswer` reports. */
  private enum class Timeout(val seconds: Int) { PROBE(3), API(30), UPLOAD(60), DOWNLOAD(30), CHANNEL(5) }

  private suspend fun <T> signed(
    method: String,
    path: List<String>,
    params: List<Pair<String, String>> = emptyList(),
    json: JsonObject? = null,
    body: ByteArray? = null,
    extra: Headers = Headers.headersOf(),
    timeout: Timeout = Timeout.API,
    onBody: ((Long) -> Unit)? = null,
    parse: (Answer.Response) -> T,
  ): ApiResult<T> {
    val at = currentPeer() ?: return ApiResult.Failed(AppError.Computer.Guarded)
    val url = url(at, *path.toTypedArray()) { params.forEach { (name, value) -> addQueryParameter(name, value) } }
    val payload = json?.toString()?.toByteArray(Charsets.UTF_8) ?: body
    val headers = Headers.Builder().addAll(extra).apply {
      if (json != null) set("Content-Type", JSON)
    }.build()
    val answer = sendSigned(at, method, url, headers, payload, timeout, onBody)
    return result(answer, timeout, parse)
  }

  /** A signed exchange, retried once after `401 clock_skew` with the offset the refusal teaches (4.3). */
  private suspend fun sendSigned(
    at: HostPort,
    method: String,
    url: HttpUrl,
    extra: Headers,
    body: ByteArray?,
    timeout: Timeout,
    onBody: ((Long) -> Unit)? = null,
    peer: InMemoryPeer = InMemoryPeer(),
  ): Answer {
    fun build(): Outgoing {
      val headers = headers(at).newBuilder().addAll(extra)
      if (body != null) headers.set("Content-Length", body.size.toString())
      val holder = credentials
      if (holder != null && verifiedPeers?.isVerified(at) != false) {
        val target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val ts = phoneTimeS() + clockOffsetS
        val nonce = PhoneCrypto.newNonce(random)
        val authorization = PhoneCrypto.authorization(holder.deviceId.value, holder.key, method, target, ts, nonce)
        headers.set("Authorization", authorization)
      }
      return Outgoing(method, url, headers.build(), body)
    }
    val first = send(at, build(), timeout, peer, onBody)
    val skew = (first as? Answer.Response)?.takeIf { it.status == ComputerCore.HTTP_UNAUTHORIZED }
      ?.let { refusalBody(it) }
      ?.takeIf { (it["code"] as? JsonPrimitive)?.content == "clock_skew" }
      ?.let { (it["server_time"] as? JsonPrimitive)?.longOrNull }
      ?: return first
    learnServerTime(skew)
    clockOffset?.update(skew)
    return send(at, build(), timeout, peer, onBody)
  }

  private suspend fun send(
    at: HostPort,
    request: Outgoing,
    timeout: Timeout,
    peer: InMemoryPeer = InMemoryPeer(),
    onBody: ((Long) -> Unit)? = null,
  ): Answer {
    val answer = wire.exchange(at, request, peer, onBody)
    if (answer == Answer.NoAnswer && waitForTimeouts) delay(timeout.seconds * MILLIS)
    return answer
  }

  private fun <T> result(answer: Answer, timeout: Timeout, parse: (Answer.Response) -> T): ApiResult<T> =
    when (answer) {
      Answer.NoAnswer -> ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(timeout.seconds)))
      Answer.Dropped -> ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset))
      is Answer.Response -> when {
        answer.cut -> ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset))
        answer.status in SUCCESS -> try {
          ApiResult.Ok(parse(answer), answer.status)
        } catch (_: MalformedAnswer) {
          ApiResult.Failed(AppError.Computer.Malformed)
        }
        else -> refused(answer)
      }
    }

  /** The error envelope of server-contract 6.1 and 8.1 with its optional fields (work-plan 4.3, D-14). */
  private fun refused(response: Answer.Response): ApiResult.Refused {
    val body = refusalBody(response)
    val detail = when (val value = body?.get("detail")) {
      is JsonPrimitive -> value.content
      is JsonArray -> value.joinToString("; ") { (it as? JsonObject)?.get("msg")?.let(::text) ?: it.toString() }
      else -> ""
    }
    return ApiResult.Refused(
      status = response.status,
      code = (body?.get("code") as? JsonPrimitive)?.content.orEmpty(),
      detail = detail,
      job = (body?.get("job") as? JsonObject)?.let { runCatching { PhoneJson.job(it) }.getOrNull() },
      offset = (body?.get("offset") as? JsonPrimitive)?.longOrNull,
      serverTime = (body?.get("server_time") as? JsonPrimitive)?.longOrNull,
      retryAfterS = response.headers["Retry-After"]?.trim()?.toIntOrNull(),
    )
  }

  private fun refusalBody(response: Answer.Response): JsonObject? =
    runCatching { PhoneJson.parse(response.body) }.getOrNull()

  private fun text(element: JsonElement): String =
    (element as? JsonPrimitive)?.content ?: element.toString()

  private fun jobOf(response: Answer.Response): RemoteJob =
    PhoneJson.job(PhoneJson.parse(response.body)["job"] as? JsonObject ?: throw MalformedAnswer("job"))

  /** `{"job": …}` answers of creation routes: 201 created, 200 found (`created` in the link answer agrees). */
  private fun created(response: Answer.Response): Created<RemoteJob> =
    Created(jobOf(response), created = response.status == ComputerCore.HTTP_CREATED)

  private fun proofValid(body: JsonObject, device: DeviceId, challenge: String): Boolean {
    val proof = (body["proof"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    val serverId = (body["server_id"] as? JsonPrimitive)?.content ?: return false
    val holder = credentials?.takeIf { it.deviceId == device } ?: return false
    return PhoneCrypto.constantTimeEquals(PhoneCrypto.helloProof(holder.key, serverId, device.value, challenge), proof)
  }

  private fun learnServerTime(serverTime: Long) {
    clockOffsetS = serverTime - phoneTimeS()
  }

  private fun headers(at: HostPort): Headers = Headers.headersOf("Host", "${at.host}:${at.port}")

  private fun json(method: String, url: HttpUrl, at: HostPort, body: JsonObject): Outgoing {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    val headers = headers(at).newBuilder()
      .set("Content-Type", JSON)
      .set("Content-Length", bytes.size.toString())
      .build()
    return Outgoing(method, url, headers, bytes)
  }

  private fun url(at: HostPort, vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
    val builder = HttpUrl.Builder().scheme("http").host(at.host).port(at.port).addPathSegments("api/phone")
    segments.forEach { builder.addPathSegment(it) }
    return builder.apply(query).build()
  }

  private fun readRange(file: File, offset: Long, length: Long): ByteArray = RandomAccessFile(file, "r").use { raf ->
    val bytes = ByteArray(length.toInt())
    raf.seek(offset)
    raf.readFully(bytes)
    bytes
  }

  private suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
    val target = request.target
    if (request.expectedBytes > 0 && request.resumeFrom == request.expectedBytes) {
      return DownloadOutcome.Complete(request.ifRange, request.resumeFrom)
    }
    val (at, url) = artifactUrl(request.url) ?: return DownloadOutcome.Failed(AppError.Computer.Guarded)
    val extra = Headers.Builder()
    if (request.resumeFrom > 0) {
      extra.set("Range", "bytes=${request.resumeFrom}-")
      request.ifRange?.let { extra.set("If-Range", it) }
    }
    val answer = sendSigned(at, "GET", url, extra.build(), body = null, timeout = Timeout.DOWNLOAD)
    val response = when (answer) {
      Answer.NoAnswer ->
        return DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(Timeout.DOWNLOAD.seconds)))
      Answer.Dropped -> return DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset))
      is Answer.Response -> answer
    }
    val start = when (response.status) {
      ComputerCore.HTTP_PARTIAL -> request.resumeFrom
      ComputerCore.HTTP_OK -> {
        if (request.resumeFrom > 0) {
          writePart(target, 0, ByteArray(0), onProgress = {})
          return DownloadOutcome.RestartedFromZero
        }
        0L
      }
      ComputerCore.HTTP_RANGE -> return DownloadOutcome.RangeNotSatisfiable
      else -> return DownloadOutcome.Failed(computerError(refused(response)))
    }
    writePart(target, start, response.body, onProgress)
    if (response.cut) return DownloadOutcome.Failed(AppError.Computer.Unreachable(ProbeOutcome.Reset))
    return DownloadOutcome.Complete(response.headers["ETag"], target.length())
  }

  /** The current computer and the URL of an artifact named by a listing's relative `url` or an absolute one. */
  private fun artifactUrl(url: String): Pair<HostPort, HttpUrl>? {
    url.toHttpUrlOrNull()?.let { return HostPort(it.host, it.port) to it }
    val at = currentPeer() ?: return null
    val absolute = "http://${at.host}:${at.port}$url".toHttpUrlOrNull() ?: return null
    return at to absolute
  }

  /** Writes [bytes] at [start] and cuts the file there, reporting the part's length as chunks land. */
  private fun writePart(target: File, start: Long, bytes: ByteArray, onProgress: (Long) -> Unit) {
    target.parentFile?.mkdirs()
    RandomAccessFile(target, "rw").use { raf ->
      raf.setLength(start)
      raf.seek(start)
      var written = 0
      while (written < bytes.size) {
        val length = minOf(CHUNK_BYTES, bytes.size - written)
        raf.write(bytes, written, length)
        written += length
        onProgress(start + written)
      }
    }
  }

  /** A refusal as the error model of architecture 19 names it. */
  private fun computerError(refusal: ApiResult.Refused): AppError.Computer = when (refusal.code) {
    "credential_rejected" -> AppError.Computer.CredentialRejected
    "clock_skew" -> AppError.Computer.ClockSkew(refusal.serverTime ?: 0L)
    "phone_access_off", "lan_forbidden" -> AppError.Computer.PhoneAccessOff
    else -> AppError.Computer.Refused(refusal.code, refusal.detail)
  }

  private fun openChannel(): Flow<ChannelEvent> = callbackFlow {
    val at = currentPeer()
    val peer = InMemoryPeer()
    peer.onAbort = { close(IOException("The computer closed the channel.")) }
    val eventsUrl = at?.let { url(it, "events") }
    val answer = if (at == null || eventsUrl == null) {
      Answer.NoAnswer
    } else {
      sendSigned(at, "GET", eventsUrl, UPGRADE, body = null, timeout = Timeout.CHANNEL, peer = peer)
    }
    val listener = (answer as? Answer.Response)?.webSocket
    if (eventsUrl == null || listener == null) {
      val why = (answer as? Answer.Response)?.let { "refused with HTTP ${it.status}" } ?: "got no answer"
      close(IOException("The channel $why."))
      awaitClose()
      return@callbackFlow
    }
    val request = Request.Builder().url(eventsUrl).build()
    val socket = InMemoryWebSocket(request) { text ->
      runCatching { PhoneJson.event(text) }.getOrNull()?.let { trySend(it) }
    }
    val upgrade = Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(SWITCHING_PROTOCOLS)
      .message("Switching Protocols")
      .build()
    listener.onOpen(socket, upgrade)
    awaitClose {
      socket.close(NORMAL_CLOSURE, null)
      listener.onClosed(socket, NORMAL_CLOSURE, "")
    }
  }.buffer(CoroutineChannel.UNLIMITED)

  /** The phone's end of an in-memory WebSocket: text frames from the computer go to [onText] until it is closed. */
  private class InMemoryWebSocket(private val request: Request, private val onText: (String) -> Unit) : WebSocket {
    @Volatile private var closed = false

    override fun request(): Request = request

    override fun queueSize(): Long = 0

    override fun send(text: String): Boolean {
      if (closed) return false
      onText(text)
      return true
    }

    override fun send(bytes: ByteString): Boolean = !closed

    override fun close(code: Int, reason: String?): Boolean {
      val wasOpen = !closed
      closed = true
      return wasOpen
    }

    override fun cancel() {
      closed = true
    }
  }

  private companion object {
    const val MILLIS = 1_000L
    const val CHUNK_BYTES = 64 * 1024
    const val SWITCHING_PROTOCOLS = 101
    const val NORMAL_CLOSURE = 1000
    const val JSON = "application/json; charset=utf-8"
    const val OCTETS = "application/octet-stream"
    const val OFFSET_OCTETS = "application/offset+octet-stream"
    val SUCCESS = 200..299
    val UPGRADE: Headers = Headers.headersOf(
      "Connection", "Upgrade",
      "Upgrade", "websocket",
      "Sec-WebSocket-Version", "13",
      "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==",
    )
  }
}

/** The phone's identity as a paired [FakeComputerClient] holds it, from a [PairGrant]. */
fun PairGrant.toCredentials(): PhoneCredentials = PhoneCredentials(deviceId, String(token), serverId)
