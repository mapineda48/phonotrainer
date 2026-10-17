package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The datagrams of server-contract 5.2 and 5.3, the broadcast targets and the list's conflict rules (spec D-39). */
class DiscoveryProtocolTest {
  private val nonce = "6f1c2a9e4b7d3f80"

  private fun reply(
    nonce: String = this.nonce,
    t: String = "\"phonotrainer-here\"",
    v: String = "1",
    serverId: String = "\"srv_example1\"",
    name: String = "\"Study desk\"",
    port: String = "8000",
    api: String = "6",
    extra: String = "",
  ) = """{"t": $t, "v": $v, "nonce": "$nonce", "server_id": $serverId, "name": $name, "http_port": $port, "api_version": $api$extra}"""
    .toByteArray(Charsets.UTF_8)

  @Test
  fun theProbeCarriesItsTypeVersionAndNonceOnly() {
    val probe = Json.parseToJsonElement(DiscoveryProtocol.probe(nonce).toString(Charsets.UTF_8)).jsonObject

    assertThat(probe.keys).containsExactly("t", "v", "nonce")
    assertThat(probe["t"]?.jsonPrimitive?.content).isEqualTo("phonotrainer-probe")
    assertThat(probe["v"]?.jsonPrimitive?.content).isEqualTo("1")
    assertThat(probe["nonce"]?.jsonPrimitive?.content).isEqualTo(nonce)
    assertThat(DiscoveryProtocol.newNonce()).matches("^[0-9a-f]{32}$")
    assertThat(DiscoveryProtocol.newNonce()).isNotEqualTo(DiscoveryProtocol.newNonce())
  }

  @Test
  fun aReplyIsReadWhenItAnswersThisProbeWithEveryFieldInShape() {
    assertThat(DiscoveryProtocol.parseReply(reply(extra = ", \"future\": true"), nonce))
      .isEqualTo(DiscoveryReply(ServerId("srv_example1"), "Study desk", 8000, 6))
  }

  @Test
  fun repliesToAnotherProbeOrOutOfShapeAreDropped() {
    listOf(
      reply(nonce = "00112233445566778899aabbccddeeff"),
      reply(t = "\"phonotrainer-probe\""),
      reply(v = "2"),
      reply(v = "\"1\""),
      reply(serverId = "\"dev_example1\""),
      reply(serverId = "42"),
      reply(name = "\"\""),
      reply(name = "\"Desk\\u0007\""),
      reply(port = "0"),
      reply(port = "65536"),
      reply(port = "\"8000\""),
      reply(api = "6.5"),
      "not json".toByteArray(),
      byteArrayOf(0xff.toByte(), 0xfe.toByte()),
      ByteArray(DiscoveryProtocol.MAX_DATAGRAM + 1) { ' '.code.toByte() },
    ).forEach { bytes -> assertThat(DiscoveryProtocol.parseReply(bytes, nonce)).isNull() }
  }

  @Test
  fun theProductConstantsMatchTheServer() {
    assertThat(DiscoveryProtocol.PORT).isEqualTo(45317)
    assertThat(DiscoveryProtocol.SCHEDULE).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(3)).inOrder()
    assertThat(DiscoveryProtocol.LISTEN).isEqualTo(Duration.ofSeconds(10))
  }

  @Test
  fun theProbeGoesToTheLimitedAndTheDirectedBroadcastOfTheWifi() {
    assertThat(Broadcast.targets(FakeNetworkMonitor.wifi())).containsExactly("255.255.255.255", "192.0.2.255").inOrder()
    assertThat(Broadcast.directed("198.51.100.77", 26)).isEqualTo("198.51.100.127")
    assertThat(Broadcast.directed("203.0.113.70", 28)).isEqualTo("203.0.113.79")
    assertThat(Broadcast.directed("192.0.2.20", 32)).isNull()
    assertThat(Broadcast.directed(null, 24)).isNull()
    assertThat(Broadcast.directed("192.0.2", 24)).isNull()
    assertThat(Broadcast.targets(FakeNetworkMonitor.wifi().copy(ipv4 = null))).containsExactly("255.255.255.255")
  }

  // --- the list (spec F-02 behaviour 3, D-39, F-02-AC10) ---------------------------------------------------------

  private fun row(id: String, name: String, host: String = "192.0.2.10", port: Int = 8000, tooOld: Boolean = false) =
    FoundComputer(ServerId(id), name, HostPort(host, port), 6, phoneAccess = true, tooOld = tooOld, conflict = false)

  @Test
  fun exactlyOneComputerWithoutAConflictIsPreselected() {
    val state = FoundList.state(listOf(row("srv_a", "Study desk")))

    assertThat(state.preselected).isEqualTo(row("srv_a", "Study desk"))
    assertThat(state.conflict).isFalse()
  }

  @Test
  fun twoComputersWithOneNameAreBothConflictsAndNoneIsPreselected() {
    val state = FoundList.state(listOf(row("srv_a", "PhonoTrainer computer"), row("srv_b", " phonotrainer COMPUTER ", host = "192.0.2.11")))

    assertThat(state.computers.map { it.conflict }).containsExactly(true, true)
    assertThat(state.conflict).isTrue()
    assertThat(state.preselected).isNull()
  }

  @Test
  fun oneServerIdFromTwoAddressesIsAConflictNeverMerged() {
    val state = FoundList.state(listOf(row("srv_a", "Study desk"), row("srv_a", "Study desk", host = "192.0.2.11")))

    assertThat(state.computers).hasSize(2)
    assertThat(state.conflict).isTrue()
    assertThat(state.preselected).isNull()
  }

  @Test
  fun twoDifferentComputersAreListedWithoutAConflictAndWithoutAPreselection() {
    val state = FoundList.state(listOf(row("srv_a", "Study desk"), row("srv_b", "Kitchen", host = "192.0.2.11")))

    assertThat(state.conflict).isFalse()
    assertThat(state.computers.none { it.conflict }).isTrue()
    assertThat(state.preselected).isNull()
  }

  @Test
  fun aComputerTooOldIsNeverPreselected() {
    assertThat(FoundList.state(listOf(row("srv_a", "Study desk", tooOld = true))).preselected).isNull()
  }
}
