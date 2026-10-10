package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.io.Closeable
import java.net.InetAddress
import java.util.Collections
import kotlin.random.Random

/**
 * A second responder that claims to be the paired computer (architecture 21.3 `impersonate`, security review H-1,
 * spec D-37 and F-02-AC11): it answers discovery and `hello` with the paired server id and name, but it lacks the
 * phone's key, so its `proof` is missing or wrong. Everything else it is sent is recorded and refused, so a test can
 * assert that no request, and no `Authorization` header, ever reached it.
 */
class Impersonator internal constructor(
  private val proof: ProofMode,
  private val serverId: String,
  private val name: String,
  private val apiVersion: Int,
  private val serverTime: () -> Long,
  private val host: String,
) : Closeable {
  private val recorded = Collections.synchronizedList(mutableListOf<RecordedCall>())
  private val random = Random(proof.ordinal + 1L)
  private val server = MockWebServer()
  private var responder: DiscoveryResponder? = null

  init {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
    }
    server.start(InetAddress.getByName(host), 0)
  }

  /** Where the impersonator listens (another port of the loopback interface). */
  val address: HostPort get() = HostPort(host, server.port)

  /** Every request it received, in order. */
  val requests: List<RecordedCall> get() = synchronized(recorded) { recorded.toList() }

  /** Answers a discovery probe exactly as the paired computer would, from this responder's port. */
  fun answerProbe(datagram: ByteArray): ByteArray? = DiscoveryProtocol.probeNonce(datagram)?.let { nonce ->
    DiscoveryProtocol.reply(nonce, serverId, name, server.port, apiVersion)
  }

  /** Serves [answerProbe] on a UDP socket of the loopback interface; returns its port. */
  fun startDiscoveryResponder(port: Int = 0): Int {
    val started = responder ?: DiscoveryResponder(host, port) { datagram, _ -> answerProbe(datagram) }
    responder = started
    return started.port
  }

  private fun answer(request: RecordedRequest): MockResponse {
    val headers = request.headers.toMultimap().mapValues { it.value.joinToString(", ") }
    val path = request.target.substringBefore('?')
    val response = if (request.method == "GET" && path == "/api/phone/hello") hello(request) else refused()
    recorded += RecordedCall(
      request.method, request.target, headers, request.bodySize, System.currentTimeMillis(), response.code, null,
    )
    return response
  }

  private fun hello(request: RecordedRequest): MockResponse {
    val challenged = request.url.queryParameter("device_id") != null && request.url.queryParameter("challenge") != null
    val body = buildJsonObject {
      put("service", "phonotrainer")
      put("phone_access", true)
      put("server_id", serverId)
      put("name", name)
      put("api_version", apiVersion)
      put("server_time", serverTime())
      when {
        !challenged || proof == ProofMode.MISSING -> put("proof", JsonNull)
        else -> put("proof", PhoneCrypto.hex(random.nextBytes(PROOF_BYTES)))
      }
      put("revoked", if (challenged && proof == ProofMode.WRONG) false else null)
    }
    return MockResponse.Builder()
      .code(ComputerCore.HTTP_OK)
      .setHeader("Content-Type", "application/json; charset=utf-8")
      .body(Buffer().writeUtf8(body.toString()))
      .build()
  }

  private fun refused(): MockResponse = MockResponse.Builder()
    .code(ComputerCore.HTTP_UNAUTHORIZED)
    .setHeader("Content-Type", "application/json; charset=utf-8")
    .setHeader("WWW-Authenticate", "PT1")
    .body("{\"detail\": \"This phone is not paired with this computer.\", \"code\": \"credential_rejected\"}")
    .build()

  override fun close() {
    responder?.close()
    server.close()
  }

  private companion object {
    const val PROOF_BYTES = 32
  }
}
