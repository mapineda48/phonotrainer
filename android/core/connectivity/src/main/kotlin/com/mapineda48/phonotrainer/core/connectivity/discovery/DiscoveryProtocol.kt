package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.mapineda48.phonotrainer.core.connectivity.Hex
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.SecureRandom
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One computer's answer to a probe (server-contract 5.3): never a credential, a path or an address. */
data class DiscoveryReply(val serverId: ServerId, val name: String, val httpPort: Int, val apiVersion: Int)

/**
 * The discovery datagrams of server-contract 5.2 and 5.3 and their product constants (architecture 6.1). The port is
 * the same constant as the server's `DISCOVERY_PORT`; if it ever changes, both change together.
 */
object DiscoveryProtocol {
  /** The fixed UDP port computers with phone access on listen on (server-contract 5.1). */
  const val PORT: Int = 45317

  /** Neither a probe nor a reply may be longer (server-contract 5.2). */
  const val MAX_DATAGRAM: Int = 512

  /** The probe goes out at 0 s, 1 s and 3 s, because UDP may lose a datagram (architecture 6.1). */
  val SCHEDULE: List<Duration> = listOf(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(3))

  /** Replies are collected until 10 s after the first probe (spec F-02 behaviour 3). */
  val LISTEN: Duration = Duration.ofSeconds(10)

  /** The limited broadcast address; the directed broadcast of the Wi-Fi network is sent as well. */
  const val LIMITED_BROADCAST: String = "255.255.255.255"

  private const val NONCE_BYTES = 16
  private const val MAX_NAME_CHARS = 120
  private const val MAX_PORT = 65_535
  private val NONCE = Regex("^[0-9a-f]{16,64}$")
  private val SERVER_ID = Regex("^srv_[A-Za-z0-9]{1,64}$")
  private val random = SecureRandom()

  /** A fresh nonce for one search: 32 lower-case hex characters. */
  fun newNonce(): String {
    val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
    return Hex.encode(bytes)
  }

  /** `{"t": "phonotrainer-probe", "v": 1, "nonce": …}` as UTF-8 (server-contract 5.2). */
  fun probe(nonce: String): ByteArray {
    require(NONCE.matches(nonce)) { "a nonce is 16 to 64 lower-case hex characters" }
    return buildJsonObject {
      put("t", "phonotrainer-probe")
      put("v", 1)
      put("nonce", nonce)
    }.toString().toByteArray(Charsets.UTF_8)
  }

  /**
   * The reply in [bytes] if it answers the probe that carried [nonce], or null: too long, not UTF-8 JSON, another type
   * or version, another nonce, or a field without its documented shape (server-contract 5.3). Unknown fields are
   * ignored.
   */
  fun parseReply(bytes: ByteArray, nonce: String): DiscoveryReply? {
    if (bytes.isEmpty() || bytes.size > MAX_DATAGRAM) return null
    val text = try {
      Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
      return null
    }
    val reply = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
    if (reply.string("t") != "phonotrainer-here") return null
    if (reply.integer("v") != 1) return null
    if (reply.string("nonce") != nonce) return null
    val serverId = reply.string("server_id")?.takeIf(SERVER_ID::matches) ?: return null
    val name = reply.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_CHARS } ?: return null
    if (name.any { it.isISOControl() }) return null
    val port = reply.integer("http_port")?.takeIf { it in 1..MAX_PORT } ?: return null
    val apiVersion = reply.integer("api_version")?.takeIf { it >= 0 } ?: return null
    return DiscoveryReply(ServerId(serverId), name, port, apiVersion)
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.integer(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString && !it.content.contains('.') }?.intOrNull
}
