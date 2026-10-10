package com.mapineda48.phonotrainer.core.testing.computer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The discovery datagrams of server-contract 5.2 and 5.3. [answer] is pure (a `FakeDiscovery` transport can call it
 * directly); [DiscoveryResponder] serves it on a real UDP socket bound to loopback for the JVM tests of the phone's
 * discovery client.
 */
internal object DiscoveryProtocol {
  const val MAX_DATAGRAM = 512

  /** The probe's nonce, or null when the datagram is not exactly a probe (it is then dropped without an answer). */
  fun probeNonce(datagram: ByteArray): String? {
    if (datagram.size > MAX_DATAGRAM) return null
    val text = try {
      Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(datagram)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
      return null
    }
    val probe = try {
      Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
    if ((probe["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "phonotrainer-probe") return null
    val version = probe["v"] as? JsonPrimitive
    if (version == null || version.isString || version.content != "1" || version.intOrNull != 1) return null
    return (probe["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { NONCE.matches(it) }
  }

  fun reply(nonce: String, serverId: String, name: String, httpPort: Int, apiVersion: Int): ByteArray =
    buildJsonObject {
      put("t", "phonotrainer-here")
      put("v", 1)
      put("nonce", nonce)
      put("server_id", serverId)
      put("name", name)
      put("http_port", httpPort)
      put("api_version", apiVersion)
    }.toString().toByteArray(Charsets.UTF_8)

  private val NONCE = Regex("^[0-9a-f]{16,64}$")
}

/**
 * A responder on a real UDP socket that answers probes with [answer] (null: stay silent), unicast to the probe's
 * source address and port (server-contract 5.3).
 */
class DiscoveryResponder internal constructor(
  host: String,
  port: Int,
  private val answer: (ByteArray, String) -> ByteArray?,
) : Closeable {
  private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(host), port))

  /** The UDP port the responder listens on. */
  val port: Int get() = socket.localPort

  private val worker = thread(isDaemon = true, name = "fake-computer-discovery") {
    val buffer = ByteArray(DiscoveryProtocol.MAX_DATAGRAM + 1)
    while (!socket.isClosed) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
      } catch (_: SocketException) {
        break
      }
      val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
      val reply = answer(datagram, packet.address.hostAddress) ?: continue
      try {
        socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
      } catch (_: SocketException) {
        break
      }
    }
  }

  override fun close() {
    socket.close()
    worker.join(JOIN_MILLIS)
  }

  private companion object {
    const val JOIN_MILLIS = 1_000L
  }
}

/** Per-second reply limits of server-contract 5.4 (5 per source, 50 in total), on real time: they are network-level. */
internal class DiscoveryLimits {
  private val replies = ArrayDeque<Pair<String, Long>>()

  @Synchronized
  fun allow(source: String): Boolean {
    val now = System.nanoTime()
    while (replies.isNotEmpty() && now - replies.first().second >= SECOND_NANOS) replies.removeFirst()
    if (replies.count { it.first == source } >= PER_SOURCE || replies.size >= TOTAL) return false
    replies.addLast(source to now)
    return true
  }

  private companion object {
    const val SECOND_NANOS = 1_000_000_000L
    const val PER_SOURCE = 5
    const val TOTAL = 50
  }
}
