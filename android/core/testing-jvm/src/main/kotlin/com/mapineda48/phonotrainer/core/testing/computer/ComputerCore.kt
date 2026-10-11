package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/** A refused request: the error envelope of server-contract 6.1 with its status, extra fields and headers. */
internal class Refusal(
  val status: Int,
  val code: String?,
  val detail: String,
  val extra: Map<String, JsonElement> = emptyMap(),
  val headers: Map<String, String> = emptyMap(),
) : RuntimeException(code ?: detail)

/**
 * One request being dispatched, from the network layer or from the in-memory exchange; [watch] is its upload body
 * when the body was followed byte by byte.
 */
internal class Call(val request: RecordedRequest, val peer: Peer?, val watch: BodyWatch?) {
  val method: String = request.method.uppercase(Locale.ROOT)
  val target: String = request.target
  val rawPath: String = target.substringBefore('?')
  val body: ByteArray = request.body?.toByteArray() ?: ByteArray(0)
  var device: Device? = null
  var segments: List<String> = emptyList()

  fun header(name: String): String? = request.headers[name]

  fun query(name: String): String? = request.url.queryParameter(name)

  val wantsWebSocket: Boolean get() = header("Upgrade")?.equals("websocket", ignoreCase = true) == true

  /** The source address for the rate limits. */
  val source: String get() = peer?.remoteAddress ?: "unknown"
}

/**
 * The fake computer's state and its `MockWebServer` dispatcher: server-contract sections 4.2 to 4.4 (access, Host,
 * signatures), 6 (every phone route), 7 (the channel) and 8 (codes), written from the contract alone.
 *
 * Every request is handled under [lock]; the network layer reports streamed upload bodies through [NetworkHost].
 */
internal class ComputerCore(
  val clock: FakeComputerClock,
  seed: Long,
  initialName: String,
  var clips: ClipContentProvider,
) : Dispatcher(), NetworkHost {
  val lock = Any()
  val random = Random(seed)

  // Identity and settings (server.json, 3.2; capabilities, 3.4).
  var serverId = newId("srv_")
  var name = initialName
  var libraryId = newId("lib_")
  var apiVersion = API_VERSION_CORE
  var minAppVersion = 1
  var capabilities = FakeCapabilities()
  var freeBytes = DEFAULT_FREE_BYTES
  var phoneAccess = true
  var lanForbidden = false
  var skewS = 0L
  var rateLimits = true
  var deferRunningCancel = false
  var renditionStatus = ArtifactStatus.READY
  var referenceJson: String = DEFAULT_REFERENCE
  val linkHosts = HashMap<String, LinkHostAnswer>()

  @Volatile var reachability: Reachability = Reachability.Reachable

  // Registry (devices.json, pairing.json) and rate limits (2.3, 4.7).
  val devices = LinkedHashMap<String, Device>()
  var pairing: PairingCode? = null
  private val pairAttempts = ArrayDeque<Pair<String, Long>>()
  private val challengeAttempts = ArrayDeque<Pair<String, Long>>()

  // Job store and uploads (6.3 to 6.7).
  val jobs = TreeMap<String, Job>()
  private var nextSeq = 0L
  val sessions = LinkedHashMap<Pair<String, String>, UploadSession>()
  val inFlight = mutableListOf<BodyWatch>()
  val channels = mutableListOf<Channel>()

  // Knob rules (architecture 21.3) and observations.
  val faults = mutableListOf<FaultRule>()
  val drops = mutableListOf<FaultRule>()
  val truncations = mutableListOf<FaultRule>()
  val corruptions = mutableListOf<FaultRule>()
  val stalls = mutableListOf<FaultRule>()
  val calls = mutableListOf<RecordedCall>()

  var bindHost = "127.0.0.1"
  var port = 0

  override val unreachable: Boolean get() = reachability == Reachability.Unreachable

  fun serverTimeS(): Long = clock.nowSeconds() + skewS

  fun serverNowMillis(): Long = clock.nowMillis() + skewS * MILLIS

  // ---------------------------------------------------------------------------------------------------------------
  // Dispatch

  override fun dispatch(request: RecordedRequest): MockResponse {
    val connection = FakeNetwork.current.get()
    val watch = connection?.completedBody
      ?.takeIf { it.head.target == request.target && it.head.method == request.method }
    val response = exchange(Call(request, connection, watch))
    return when (val slow = reachability) {
      is Reachability.Slow -> response.newBuilder().headersDelay(slow.latency.toMillis(), TimeUnit.MILLISECONDS).build()
      else -> response
    }
  }

  /**
   * Handles one request and records it, whichever way it arrived. Reachability and latency are the caller's: the
   * network layer moves no byte while unreachable, and the in-memory exchange checks it before calling.
   */
  fun exchange(call: Call): MockResponse = synchronized(lock) {
    val response = try {
      handle(call)
    } catch (refusal: Refusal) {
      // A refused WebSocket upgrade is closed before accept: the phone sees HTTP 403 and nothing else (7).
      if (call.wantsWebSocket) MockResponse.Builder().code(HTTP_FORBIDDEN).build() else refusalResponse(refusal)
    } finally {
      call.watch?.let(::endWatch)
    }
    val dropped = response.onResponseStart != null
    record(call, if (dropped) null else response.code, call.device?.id)
    response
  }

  /**
   * The order of the server's middleware and routing (server-contract 4.2 to 4.4, 4.7; WP-01's
   * `PhoneAccessMiddleware`): suspension, Host, non-phone paths, unsafe paths, phone access, body framing, the route,
   * the signature, the handler.
   */
  private fun handle(call: Call): MockResponse {
    sweepIdleBodies()
    expireUploads()
    takeRule(faults, call.method, call.rawPath)?.let { return faultResponse(it) }
    if (apiVersion < API_VERSION_CORE) return oldServer(call)
    if (lanForbidden) throw lanForbiddenRefusal()
    checkHost(call)
    val raw = call.rawPath.split('/').filter { it.isNotEmpty() }
    if (raw.size < 2 || raw[0] != "api" || raw[1] != "phone") throw lanForbiddenRefusal()
    call.segments = checkedSegments(call.rawPath)
    val hello = call.segments == listOf("api", "phone", "hello")
    if (!phoneAccess && !hello) {
      throw Refusal(HTTP_FORBIDDEN, "phone_access_off", "Phone access is off on this computer.")
    }
    val route = Route.match(call.method, call.segments.drop(2))
    checkBodyFraming(call, route)
    if (route == null || !route.available(capabilities)) {
      throw Refusal(HTTP_NOT_FOUND, "unknown_route", "There is no such phone route.")
    }
    if (route.signed) authenticate(call)
    val response = route.handler(this, call)
    takeRule(drops, call.method, call.rawPath)?.let {
      return response.newBuilder().onResponseStart(SocketEffect.CloseSocket()).build()
    }
    return response
  }

  /** A server older than API 6 has no phone routes: `/api/health` answers with its version (6.2). */
  private fun oldServer(call: Call): MockResponse {
    if (call.method != "GET" || call.rawPath != "/api/health") throw Refusal(HTTP_NOT_FOUND, null, "Not Found")
    return json(
      HTTP_OK,
      buildJsonObject {
        put("ok", true)
        put("version", "0.1.0")
        put("api_version", apiVersion)
        put("workspace", "workspace")
        put("web_built", true)
      },
    )
  }

  /** Percent-decoded segments; `.`/`..` segments and encoded slashes are refused (4.2). */
  private fun checkedSegments(rawPath: String): List<String> {
    val raw = rawPath.split('/').filter { it.isNotEmpty() }
    val invalid = Refusal(HTTP_BAD_REQUEST, "invalid_request", "The request path is not valid.")
    return raw.map { segment ->
      if (segment.lowercase(Locale.ROOT).contains("%2f") || segment.lowercase(Locale.ROOT).contains("%5c")) {
        throw invalid
      }
      val decoded = percentDecode(segment) ?: throw invalid
      if (decoded == "." || decoded == "..") throw invalid
      decoded
    }
  }

  /** The LAN Host rule (4.4): an IPv4 literal equal to the address the connection arrived on, the port optional. */
  private fun checkHost(call: Call) {
    val host = call.header("Host") ?: throw badHost()
    val name = host.substringBefore(':')
    val hostPort = host.substringAfter(':', "")
    if (name != bindHost) throw badHost()
    if (hostPort.isNotEmpty() && hostPort != port.toString()) throw badHost()
  }

  private fun badHost() = Refusal(HTTP_BAD_REQUEST, "bad_host", "Unexpected Host header.")

  fun lanForbiddenRefusal() =
    Refusal(HTTP_FORBIDDEN, "lan_forbidden", "This part of PhonoTrainer is only available on the computer itself.")

  /**
   * Body framing before any route runs (4.7): a JSON route needs `Content-Length`, a chunked body needs one too,
   * and only the file routes (upload bodies, compare) may exceed 16 KiB.
   */
  private fun checkBodyFraming(call: Call, route: Route?) {
    if (route != null && route.fileBody) return
    val length = call.header("Content-Length")
    val chunked = call.header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
    if (length == null) {
      if (route?.jsonBody == true || chunked) {
        throw Refusal(HTTP_LENGTH_REQUIRED, "length_required", "The request must declare its length.")
      }
      return
    }
    val bytes = length.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
      ?: throw invalidRequest("Content-Length is not valid.")
    if (bytes > MAX_JSON_BYTES) throw Refusal(HTTP_TOO_LARGE, "too_large", "The request body is too large.")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Signatures (4.3)

  private class Signature(val deviceId: String, val ts: Long, val nonce: String, val mac: String)

  /** The header exactly as server-contract 4.3 writes it: fields in order, lower-case hex. */
  private fun parseSignature(header: String?): Signature? {
    val match = header?.trim()?.let(SIGNATURE_HEADER::matchEntire) ?: return null
    val (device, ts, nonce, mac) = match.destructured
    return Signature(device, ts.toLong(), nonce, mac)
  }

  private fun credentialRejected() = Refusal(
    HTTP_UNAUTHORIZED, "credential_rejected", "This phone is not paired with this computer.",
    headers = mapOf("WWW-Authenticate" to "PT1"),
  )

  fun authenticate(call: Call) {
    val signature = parseSignature(call.header("Authorization")) ?: throw credentialRejected()
    val device = devices[signature.deviceId]?.takeIf { !it.revoked } ?: throw credentialRejected()
    val expected = PhoneCrypto.requestMac(device.key, call.method, call.target, signature.ts, signature.nonce)
    if (!PhoneCrypto.constantTimeEquals(expected, signature.mac)) throw credentialRejected()
    val now = serverTimeS()
    if (abs(signature.ts - now) > CLOCK_WINDOW_S) {
      throw Refusal(
        HTTP_UNAUTHORIZED, "clock_skew", "This phone's clock differs from this computer's.",
        extra = mapOf("server_time" to JsonPrimitive(now)), headers = mapOf("WWW-Authenticate" to "PT1"),
      )
    }
    val nowMillis = clock.nowMillis()
    device.nonces.entries.removeIf { nowMillis - it.value > NONCE_WINDOW_MS }
    if (device.nonces.containsKey(signature.nonce)) throw credentialRejected()
    device.nonces[signature.nonce] = nowMillis
    while (device.nonces.size > MAX_NONCES) device.nonces.remove(device.nonces.keys.first())
    val lastSeen = device.lastSeenAtMillis
    if (lastSeen == null || nowMillis - lastSeen >= LAST_SEEN_THROTTLE_MS) device.lastSeenAtMillis = nowMillis
    call.device = device
  }

  /** The device of a valid signature on a streamed head, without consuming its nonce (uploads, 4.7). */
  private fun peekSignature(head: RequestHead): String? {
    val signature = parseSignature(head.header("authorization")) ?: return null
    val device = devices[signature.deviceId]?.takeIf { !it.revoked } ?: return null
    val expected = PhoneCrypto.requestMac(device.key, head.method, head.target, signature.ts, signature.nonce)
    return device.id.takeIf { PhoneCrypto.constantTimeEquals(expected, signature.mac) }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Rate limits (2.3, 4.7): sliding one-minute windows on the computer's clock.

  /** Records an attempt; refuses with `429 rate_limited` above [perSource] or [total] per minute. */
  fun rateLimit(kind: String, source: String, perSource: Int, total: Int) {
    if (!rateLimits) return
    val attempts = if (kind == "pair") pairAttempts else challengeAttempts
    val now = clock.nowMillis()
    while (attempts.isNotEmpty() && now - attempts.first().second >= RATE_WINDOW_MS) attempts.removeFirst()
    val fromSource = attempts.filter { it.first == source }
    val limiting = when {
      fromSource.size >= perSource -> fromSource.first().second
      attempts.size >= total -> attempts.first().second
      else -> null
    }
    if (limiting != null) {
      val retryAfter = ((limiting + RATE_WINDOW_MS - now + MILLIS - 1) / MILLIS).coerceAtLeast(1)
      throw Refusal(
        HTTP_TOO_MANY, "rate_limited", "Too many attempts. Try again in a moment.",
        headers = mapOf("Retry-After" to retryAfter.toString()),
      )
    }
    attempts.addLast(source to now)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Jobs, events and the channel (6.3, 7)

  fun newJob(
    kind: SourceKind, url: String?, fileName: String?, audioOnly: Boolean, separateDialogue: Boolean,
    deviceId: String?, clientRequestId: String?, rerunOf: String?,
  ): Job {
    val job = Job(
      id = newJobId(), seq = nextSeq++, kind = kind, url = url, fileName = fileName, audioOnly = audioOnly,
      separateDialogue = separateDialogue, deviceId = deviceId, clientRequestId = clientRequestId, rerunOf = rerunOf,
      createdAtMillis = serverNowMillis(),
    )
    job.title = fileName?.substringBeforeLast('.')
    jobs[job.id] = job
    return job
  }

  fun queuePosition(job: Job): Int? {
    if (job.status != JobStatus.QUEUED || job.cancelRequested) return null
    val line = jobs.values.filter { it.status == JobStatus.QUEUED && !it.cancelRequested }.sortedBy { it.seq }
    return line.indexOf(job) + 1
  }

  fun jobJson(job: Job, viewer: String?, lean: Boolean = false): JsonObject =
    PhoneShapes.job(job, viewer, queuePosition(job), lean, capabilities.downloaderVersion)

  fun jobForKey(deviceId: String, key: String): Job? =
    jobs.values.firstOrNull { it.deviceId == deviceId && it.clientRequestId == key }

  /** Sends the job to every channel (and the queue positions it may have moved). */
  fun publish(job: Job) {
    // Over a copy: an in-memory channel's collector may run in place and close its channel.
    channels.toList().forEach { channel ->
      channel.socket?.send(event("job") { put("job", jobJson(job, channel.deviceId, lean = true)) })
    }
  }

  /** Sends [job] and every queued job, whose queue positions may have moved with it. */
  fun publishChange(job: Job) {
    publish(job)
    jobs.values.filter { it !== job && it.status == JobStatus.QUEUED }.forEach(::publish)
  }

  fun publishDeleted(id: String) {
    channels.toList().forEach { channel -> channel.socket?.send(event("deleted") { put("id", id) }) }
  }

  fun channelHello(channel: Channel): List<String> = listOf(
    event("hello") {
      put("server_id", serverId)
      put("library_id", libraryId)
      put("api_version", apiVersion)
    },
    event("snapshot") {
      put("jobs", kotlinx.serialization.json.JsonArray(jobs.values.map { jobJson(it, channel.deviceId, lean = true) }))
    },
  )

  private fun event(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
      put("type", type)
      fields()
    }.toString()

  fun removeJob(id: String) {
    if (jobs.remove(id) != null) publishDeleted(id)
  }

  /** Closes every phone channel abruptly (the phone sees the channel fail, architecture 5.5). */
  fun dropChannels() {
    val all = channels.toList()
    channels.clear()
    all.forEach(Channel::drop)
  }

  /** Forgets the in-memory rate-limit windows (a process restart). */
  fun resetLimits() {
    pairAttempts.clear()
    challengeAttempts.clear()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Uploads streamed through the network layer (4.7, 6.6)

  override fun bodyStarting(peer: Peer, head: RequestHead): BodyWatch? = synchronized(lock) {
    val method = head.method.uppercase(Locale.ROOT)
    if (method != "PUT" && method != "PATCH") return null
    val segments = head.path.split('/').filter { it.isNotEmpty() }
    if (segments.size != 4 || segments.take(3) != listOf("api", "phone", "uploads")) return null
    val key = (percentDecode(segments[3]) ?: return null).lowercase(Locale.ROOT)
    val length = head.contentLength ?: return null
    sweepIdleBodies()
    expireUploads()
    val deviceId = peekSignature(head)
    val session = deviceId?.let { sessions[it to key] }
    val acceptable = session != null && phoneAccess && !lanForbidden && apiVersion >= API_VERSION_CORE &&
      when (method) {
        "PUT" -> length == session.bytes
        else -> capabilities.resumableUploads && length in 1..capabilities.maxChunkBytes &&
          head.header("upload-offset")?.toLongOrNull() == session.offset && session.offset + length <= session.bytes
      }
    var admitted = false
    var busy = false
    var stallAt: Long? = null
    if (acceptable) {
      val now = clock.nowMillis()
      val others = inFlight.filter { it.deviceId == deviceId && !it.ended }
      if (others.all { it.key == key && now - it.lastByteAtMillis >= NEWEST_WINS_IDLE_MS }) {
        others.forEach { abortWatch(it) }
        admitted = true
        stallAt = takeRule(stalls, method, head.path)?.atBytes
      } else {
        busy = true
      }
    }
    BodyWatch(peer, head, key, deviceId, length, admitted, busy, stallAt, clock.nowMillis()).also { watch ->
      if (admitted) {
        inFlight += watch
        watch.startOffset = session?.offset ?: 0L
        if (capabilities.resumableUploads && session != null) {
          // v1-complete keeps what reached the disk: a PUT rewrites the part from 0, a PATCH appends to it.
          if (method == "PUT") session.part.reset()
          watch.startOffset = session.offset
          watch.streamsToPart = true
        }
      }
    }
  }

  override fun bodyBytes(watch: BodyWatch, bytes: ByteArray, offset: Int, length: Int): Unit = synchronized(lock) {
    if (watch.ended) return
    watch.receivedBytes += length
    if (!watch.admitted) return
    watch.lastByteAtMillis = clock.nowMillis()
    val session = watch.deviceId?.let { sessions[it to watch.key] } ?: return
    session.lastActivityMillis = watch.lastByteAtMillis
    if (watch.streamsToPart) session.part.write(bytes, offset, length)
  }

  override fun bodyInterrupted(watch: BodyWatch): Unit = synchronized(lock) {
    if (watch.ended) return
    finishInterrupted(watch)
  }

  override fun abortIfIdle(watch: BodyWatch): Boolean = synchronized(lock) {
    if (!watch.admitted || watch.ended) return false
    if (clock.nowMillis() - watch.lastByteAtMillis < IDLE_ABORT_MS) return false
    finishInterrupted(watch)
    true
  }

  /** Newest wins: an older idle body for the same key is cancelled (4.7). */
  fun abortWatch(watch: BodyWatch) {
    if (watch.ended) return
    finishInterrupted(watch)
    watch.peer.abort()
  }

  /**
   * The 30 s idle abort for bodies of the in-memory exchange, which has no reading thread to notice idleness (the
   * network layer's bodies are checked by their own connection, [abortIfIdle]).
   */
  fun sweepIdleBodies() {
    val now = clock.nowMillis()
    inFlight.filter { it.peer.inMemory && it.admitted && !it.ended && now - it.lastByteAtMillis >= IDLE_ABORT_MS }
      .forEach(::finishInterrupted)
  }

  /** Closes the in-memory channels (an unreachable computer answers no ping, so the phone's channel fails). */
  fun dropInMemoryChannels() {
    val lost = channels.filter { it.peer?.inMemory == true }
    channels -= lost.toSet()
    lost.forEach(Channel::drop)
  }

  private fun finishInterrupted(watch: BodyWatch) {
    watch.ended = true
    inFlight -= watch
    if (watch.admitted) {
      val session = watch.deviceId?.let { sessions[it to watch.key] }
      if (session != null) {
        session.lastActivityMillis = clock.nowMillis()
        // Core: the part is deleted. Complete: the bytes that reached the disk are kept (they are already there).
        if (!watch.streamsToPart) session.part.reset()
      }
    }
    calls += RecordedCall(
      method = watch.head.method, target = watch.head.target, headers = watch.head.headers,
      bodySize = watch.receivedBytes, atEpochMillis = clock.nowMillis(), status = null,
      signedBy = watch.deviceId?.let(::DeviceId),
    )
  }

  /** The dispatcher handled the request: its body no longer holds the device's upload slot. */
  private fun endWatch(watch: BodyWatch) {
    watch.ended = true
    inFlight -= watch
  }

  /** Sessions untouched for 48 h are deleted (the janitor of 6.6). */
  fun expireUploads() {
    val now = clock.nowMillis()
    sessions.entries.removeIf { (_, session) ->
      now - session.lastActivityMillis >= SESSION_TTL_MS && inFlight.none { it.key == session.key && !it.ended }
    }
  }

  /** Free space minus the open reservations (3.4, 4.7). */
  fun freeForUploads(excluding: UploadSession? = null): Long =
    freeBytes - sessions.values.filter { it !== excluding }.sumOf { it.bytes }

  // ---------------------------------------------------------------------------------------------------------------
  // Knob rules and responses

  fun takeRule(rules: MutableList<FaultRule>, method: String, rawPath: String): FaultRule? {
    val path = rawPath.split('/').joinToString("/") { percentDecode(it) ?: it }
    val rule = rules.firstOrNull { it.matches(method, path) } ?: return null
    rule.times -= 1
    if (rule.times <= 0) rules.remove(rule)
    return rule
  }

  private fun faultResponse(rule: FaultRule): MockResponse {
    val headers = buildMap {
      rule.retryAfterS?.let { put("Retry-After", it.toString()) }
    }
    val detail = if (rule.status >= HTTP_SERVER_ERROR) "Internal Server Error" else "Refused by the fake computer."
    return refusalResponse(Refusal(rule.status, rule.code, detail, headers = headers))
  }

  private fun record(call: Call, status: Int?, deviceId: String?) {
    calls += RecordedCall(
      method = call.request.method,
      target = call.target,
      headers = call.request.headers.toMultimap().mapValues { it.value.joinToString(", ") },
      bodySize = call.request.bodySize,
      atEpochMillis = clock.nowMillis(),
      status = status,
      signedBy = deviceId?.let(::DeviceId),
    )
  }

  fun refusalResponse(refusal: Refusal): MockResponse {
    val body = buildMap<String, JsonElement> {
      put("detail", JsonPrimitive(refusal.detail))
      refusal.code?.let { put("code", JsonPrimitive(it)) }
      putAll(refusal.extra)
    }
    return json(refusal.status, JsonObject(body), refusal.headers)
  }

  fun json(status: Int, body: JsonElement, headers: Map<String, String> = emptyMap()): MockResponse {
    val builder = MockResponse.Builder()
      .code(status)
      .setHeader("Content-Type", "application/json; charset=utf-8")
      .setHeader("Cache-Control", "no-store")
      .body(Buffer().writeUtf8(body.toString()))
    headers.forEach { (name, value) -> builder.setHeader(name, value) }
    return builder.build()
  }

  fun noContent(headers: Map<String, String> = emptyMap()): MockResponse {
    val builder = MockResponse.Builder().code(HTTP_NO_CONTENT)
    headers.forEach { (name, value) -> builder.setHeader(name, value) }
    return builder.build()
  }

  /** The JSON object of a request body, or `400 invalid_request`. */
  fun jsonBody(call: Call): JsonObject = try {
    Json.parseToJsonElement(call.body.toString(Charsets.UTF_8)) as? JsonObject ?: throw invalidRequest()
  } catch (_: IllegalArgumentException) {
    throw invalidRequest()
  }

  fun invalidRequest(detail: String = "The request is not valid.") = Refusal(HTTP_BAD_REQUEST, "invalid_request", detail)

  // ---------------------------------------------------------------------------------------------------------------
  // Ids

  fun newId(prefix: String): String = prefix + (1..ID_CHARS).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

  fun newToken(): String = "pt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(TOKEN_BYTES))

  private fun newJobId(): String {
    val stamp = JOB_STAMP.format(Instant.ofEpochMilli(serverNowMillis()))
    while (true) {
      val id = "$stamp-${PhoneCrypto.hex(random.nextBytes(2))}"
      if (id !in jobs) return id
    }
  }

  companion object {
    const val API_VERSION_CORE = 6
    const val API_VERSION_COMPLETE = 7

    const val HTTP_OK = 200
    const val HTTP_CREATED = 201
    const val HTTP_ACCEPTED = 202
    const val HTTP_NO_CONTENT = 204
    const val HTTP_PARTIAL = 206
    const val HTTP_BAD_REQUEST = 400
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
    const val HTTP_CONFLICT = 409
    const val HTTP_GONE = 410
    const val HTTP_LENGTH_REQUIRED = 411
    const val HTTP_TOO_LARGE = 413
    const val HTTP_UNSUPPORTED_MEDIA = 415
    const val HTTP_RANGE = 416
    const val HTTP_UNPROCESSABLE = 422
    const val HTTP_UPGRADE_REQUIRED = 426
    const val HTTP_TOO_MANY = 429
    const val HTTP_SERVER_ERROR = 500
    const val HTTP_UNAVAILABLE = 503
    const val HTTP_NO_SPACE = 507

    const val MILLIS = 1_000L
    const val CLOCK_WINDOW_S = 300L
    const val NONCE_WINDOW_MS = 600_000L
    const val MAX_NONCES = 4_096
    const val LAST_SEEN_THROTTLE_MS = 60_000L
    const val RATE_WINDOW_MS = 60_000L
    const val MAX_JSON_BYTES = 16L * 1024L
    const val NEWEST_WINS_IDLE_MS = 10_000L
    const val IDLE_ABORT_MS = 30_000L
    const val SESSION_TTL_MS = 48L * 3_600_000L
    const val MAX_OPEN_SESSIONS = 4
    const val MIN_FREE_BYTES = 2L * FakeCapabilities.GIB
    const val LOW_SPACE_BYTES = 4L * FakeCapabilities.GIB
    const val DEFAULT_FREE_BYTES = 50L * FakeCapabilities.GIB
    const val MAX_CHANNELS = 8
    const val MAX_CHANNELS_PER_DEVICE = 2

    private const val ID_CHARS = 26
    private const val TOKEN_BYTES = 32
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private val JOB_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    val SIGNATURE_HEADER = Regex(
      "^PT1 device=([A-Za-z0-9_]{1,64}),\\s*ts=([0-9]{1,12}),\\s*nonce=([0-9a-f]{32}),\\s*mac=([0-9a-f]{64})$",
    )
    val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val JOB_ID_RE = Regex("^\\d{8}-\\d{6}-[0-9a-f]{4}$")

    /** A minimal `/api/reference` body; tests that need the real one set [FakeComputer.referenceJson]. */
    const val DEFAULT_REFERENCE = """{"api_version": 6, "families": [], "phenomena": []}"""

    /** Decodes `%XX` escapes (not `+`); null when an escape is malformed. */
    fun percentDecode(segment: String): String? {
      if (!segment.contains('%')) return segment
      val out = java.io.ByteArrayOutputStream()
      var i = 0
      while (i < segment.length) {
        val c = segment[i]
        if (c == '%') {
          if (i + 2 >= segment.length) return null
          val value = segment.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
          out.write(value)
          i += 3
        } else {
          out.write(c.toString().toByteArray(Charsets.UTF_8))
          i++
        }
      }
      return out.toString(Charsets.UTF_8)
    }
  }
}
