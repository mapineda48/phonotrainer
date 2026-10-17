package com.mapineda48.phonotrainer.core.connectivity.support

import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A discovery reply to [probe] that announces [computer]'s server id with another [port] and [name]: what a relay or an
 * impostor on the Wi-Fi sends (server-contract 5.3). Null when [probe] is not a probe.
 */
internal fun replyAs(probe: ByteArray, computer: FakeComputer, port: Int, name: String = computer.name): ByteArray? {
  val nonce = runCatching {
    Json.parseToJsonElement(String(probe, Charsets.UTF_8)).jsonObject["nonce"]?.jsonPrimitive?.content
  }.getOrNull() ?: return null
  return buildJsonObject {
    put("t", "phonotrainer-here")
    put("v", 1)
    put("nonce", nonce)
    put("server_id", computer.serverId.value)
    put("name", name)
    put("http_port", port)
    put("api_version", 6)
  }.toString().toByteArray(Charsets.UTF_8)
}

/** The datagram [replyAs] gives, from [FakeComputer.HOST]. */
internal fun relayDatagram(probe: ByteArray, computer: FakeComputer, relay: HostPort, name: String = computer.name) =
  replyAs(probe, computer, relay.port, name)?.let { Datagram(it, relay.host, Harness.DISCOVERY_PORT) }

/**
 * A host at [relay] that passes every `hello` on to [computer] and gives back its answer, as a relay on the Wi-Fi can
 * (the proof does not name the address it was made for). Every address it was asked at is kept in [asked].
 */
internal class RelayingClient(
  private val inner: ComputerClient,
  private val relay: HostPort,
  private val computer: FakeComputer,
) : ComputerClient by inner {
  val asked = mutableListOf<HostPort>()

  override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> {
    asked += at
    return inner.hello(if (at == relay) computer.address else at, challengeFor)
  }
}

/**
 * A [DiscoveryTransport] whose responders answer at scripted moments of virtual time, and whose search lasts the whole
 * listening window, like the UDP transport.
 */
internal class TimedTransport : DiscoveryTransport {
  private val scripted = mutableListOf<Pair<Long, (ByteArray) -> Datagram?>>()

  /** [responder] answers the probe [millis] after it went out. */
  fun at(millis: Long, responder: (ByteArray) -> Datagram?) {
    scripted += millis to responder
  }

  override fun probe(
    datagram: ByteArray,
    broadcastTargets: List<String>,
    schedule: List<Duration>,
    listenFor: Duration,
  ): Flow<Datagram> = flow {
    var elapsed = 0L
    scripted.sortedBy { it.first }.forEach { (at, responder) ->
      delay(at - elapsed)
      elapsed = at
      responder(datagram)?.let { emit(it) }
    }
    delay((listenFor.toMillis() - elapsed).coerceAtLeast(0))
  }
}
