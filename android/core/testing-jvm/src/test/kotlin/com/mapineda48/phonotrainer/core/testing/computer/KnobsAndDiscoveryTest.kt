package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * The failure modes of architecture 21.3 not covered route by route, and discovery (server-contract 5): reachability,
 * impersonation (F-02-AC11), suspension (F-02-AC12), a different computer (QT-11), a workspace switch (QT-14).
 */
class KnobsAndDiscoveryTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)

  @After
  fun tearDown() = computer.close()

  @Test
  fun anUnreachableComputerAnswersNothingUntilItIsBack() {
    val impatient = phone.client.newBuilder().callTimeout(Duration.ofMillis(300)).build()
    computer.reachability = Reachability.Unreachable

    assertThrows(InterruptedIOException::class.java) {
      impatient.newCall(okhttp3.Request.Builder().url(computer.url("/api/phone/hello")).build()).execute()
    }
    assertThat(computer.requests).isEmpty()

    computer.reachability = Reachability.Reachable
    impatient.connectionPool.evictAll()
    impatient.newCall(okhttp3.Request.Builder().url(computer.url("/api/phone/hello")).build()).execute().use {
      assertThat(it.code).isEqualTo(200)
    }
  }

  @Test
  fun aSlowComputerAnswersAfterItsLatency() {
    computer.reachability = Reachability.Slow(Duration.ofMillis(300))

    val started = System.nanoTime()
    val response = phone.get("/api/phone/hello")
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertThat(response.code).isEqualTo(200)
    assertThat(elapsedMs).isAtLeast(300)
  }

  @Test
  fun anImpersonatorAnswersHelloWithThePairedIdentityAndNoProof() {
    computer.impersonate(ProofMode.MISSING).use { impostor ->
      val hello = helloAt(impostor, credentials)

      assertThat(hello.str("server_id")).isEqualTo(computer.serverId.value)
      assertThat(hello.str("name")).isEqualTo(computer.name)
      assertThat(hello["proof"]).isEqualTo(JsonNull)
      assertThat(impostor.address).isNotEqualTo(computer.address)
    }
  }

  @Test
  fun anImpersonatorWithAWrongProofFailsTheCheckAndIsNeverSentACredential() {
    computer.impersonate(ProofMode.WRONG).use { impostor ->
      val challenge = "0123456789abcdef0123456789abcdef"
      val hello = helloAt(impostor, credentials, challenge)
      val expected = PhoneCrypto.helloProof(credentials.key, computer.serverId.value, credentials.deviceId.value, challenge)

      assertThat(hello.str("proof")).matches("[0-9a-f]{64}")
      assertThat(hello.str("proof")).isNotEqualTo(expected)
      assertThat(impostor.requests.single().hadAuthorization).isFalse()
    }
  }

  @Test
  fun anImpersonatorAnswersDiscoveryLikeThePairedComputer() {
    computer.impersonate(ProofMode.MISSING).use { impostor ->
      val reply = Json.parseToJsonElement(impostor.answerProbe(probe())!!.decodeToString()).jsonObject

      assertThat(reply.str("server_id")).isEqualTo(computer.serverId.value)
      assertThat(reply["http_port"]!!.prim().int).isEqualTo(impostor.address.port)
    }
  }

  @Test
  fun aSuspendedComputerIsSilentToDiscovery() {
    assertThat(computer.answerProbe(probe())).isNotNull()

    computer.lanForbidden()

    assertThat(computer.answerProbe(probe())).isNull()
    assertThat(phone.get("/api/phone/hello").errorCode).isEqualTo("lan_forbidden")
  }

  @Test
  fun discoveryRepliesCarryOnlyTheContractFields() {
    val reply = Json.parseToJsonElement(computer.answerProbe(probe("6f1c2a9e4b7d3f80"))!!.decodeToString()).jsonObject

    assertThat(reply.keys).containsExactly("t", "v", "nonce", "server_id", "name", "http_port", "api_version")
    assertThat(reply.str("t")).isEqualTo("phonotrainer-here")
    assertThat(reply.str("nonce")).isEqualTo("6f1c2a9e4b7d3f80")
    assertThat(reply["http_port"]!!.prim().int).isEqualTo(computer.address.port)
  }

  @Test
  fun malformedProbesAndSilentStatesGetNoReply() {
    val bad = listOf(
      """{"t": "phonotrainer-probe", "v": 2, "nonce": "6f1c2a9e4b7d3f80"}""",
      """{"t": "phonotrainer-probe", "v": "1", "nonce": "6f1c2a9e4b7d3f80"}""",
      """{"t": "phonotrainer-probe", "v": 1, "nonce": "SHORT"}""",
      """{"t": "other", "v": 1, "nonce": "6f1c2a9e4b7d3f80"}""",
      """["phonotrainer-probe"]""",
      """{"t": "phonotrainer-probe", "v": 1, "nonce": "6f1c2a9e4b7d3f80", "pad": "${"x".repeat(512)}"}""",
    )
    bad.forEach { assertThat(computer.answerProbe(it.toByteArray())).isNull() }
    assertThat(computer.answerProbe(probe(), source = "203.0.113.9")).isNull() // hygiene: test-vector

    computer.phoneAccessOff()
    assertThat(computer.answerProbe(probe())).isNull()
  }

  @Test
  fun discoveryRepliesAreRateLimitedPerSource() {
    repeat(5) { assertThat(computer.answerProbe(probe())).isNotNull() }

    assertThat(computer.answerProbe(probe())).isNull()
  }

  @Test
  fun theUdpResponderAnswersARealProbe() {
    val port = computer.startDiscoveryResponder()

    DatagramSocket(0, InetAddress.getByName(FakeComputer.HOST)).use { socket ->
      socket.soTimeout = (TestPhone.TIMEOUT_S * 1000).toInt()
      val datagram = probe()
      socket.send(DatagramPacket(datagram, datagram.size, InetAddress.getByName(FakeComputer.HOST), port))
      val buffer = ByteArray(1024)
      val packet = DatagramPacket(buffer, buffer.size)
      socket.receive(packet)

      val reply = Json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject
      assertThat(reply.str("server_id")).isEqualTo(computer.serverId.value)
    }
  }

  @Test
  fun aSuspendedUdpResponderStaysSilent() {
    val port = computer.startDiscoveryResponder()
    computer.lanForbidden()

    DatagramSocket(0, InetAddress.getByName(FakeComputer.HOST)).use { socket ->
      socket.soTimeout = 300
      val datagram = probe()
      socket.send(DatagramPacket(datagram, datagram.size, InetAddress.getByName(FakeComputer.HOST), port))
      assertThrows(SocketTimeoutException::class.java) { socket.receive(DatagramPacket(ByteArray(1024), 1024)) }
    }
  }

  @Test
  fun aDifferentComputerAtTheAddressHasAnotherIdAndDoesNotKnowThePhone() {
    val before = computer.serverId

    computer.changeServerId()

    val hello = helloAt(null, credentials)
    assertThat(hello.str("server_id")).isNotEqualTo(before.value)
    assertThat(hello["proof"]).isEqualTo(JsonNull)
    assertThat(phone.get("/api/phone/library").errorCode).isEqualTo("credential_rejected")
  }

  @Test
  fun aWorkspaceSwitchChangesTheLibraryIdAndTheJobs() {
    computer.addFinishedClip()
    val before = computer.libraryId

    computer.changeLibraryId()

    val library = phone.get("/api/phone/library").json
    assertThat(library.str("library_id")).isNotEqualTo(before.value)
    assertThat(library.arr("jobs")).isEmpty()
  }

  @Test
  fun revokingEveryDeviceRejectsThemAll() {
    val other = TestPhone(computer, computer.pairDevice("Other"))

    computer.revokeDevice()

    assertThat(phone.get("/api/phone/health").errorCode).isEqualTo("credential_rejected")
    assertThat(other.get("/api/phone/health").errorCode).isEqualTo("credential_rejected")
    assertThat(computer.pairedDevices.all { it.revoked }).isTrue()
  }

  @Test
  fun phoneAccessCanComeBack() {
    computer.phoneAccessOff()
    assertThat(phone.get("/api/phone/hello").json["phone_access"]!!.prim().boolean).isFalse()

    computer.phoneAccessOn()

    assertThat(phone.get("/api/phone/library").code).isEqualTo(200)
  }

  private fun helloAt(impostor: Impersonator?, who: PhoneCredentials, challenge: String = "ab".repeat(16)) =
    TestPhone(computer).let { raw ->
      val base = impostor?.let { "http://${it.address.host}:${it.address.port}" } ?: computer.url("/").toString().trimEnd('/')
      val url = "$base/api/phone/hello?device_id=${who.deviceId.value}&challenge=$challenge"
      raw.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
        Json.parseToJsonElement(it.body.string()).jsonObject
      }
    }

  private fun probe(nonce: String = "6f1c2a9e4b7d3f80"): ByteArray =
    """{"t": "phonotrainer-probe", "v": 1, "nonce": "$nonce"}""".toByteArray()
}
