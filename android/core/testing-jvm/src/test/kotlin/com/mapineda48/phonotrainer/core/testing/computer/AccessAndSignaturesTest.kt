package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test

/** Access control, Host validation and signatures (server-contract 4.2 to 4.4, 4.7) and their codes. */
class AccessAndSignaturesTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val credentials = computer.pairDevice()
  private val phone = TestPhone(computer, credentials)

  @After
  fun tearDown() = computer.close()

  @Test
  fun aMissingSignatureIsRejected() {
    val response = TestPhone(computer).get("/api/phone/library")

    assertThat(response.code).isEqualTo(401)
    assertThat(response.errorCode).isEqualTo("credential_rejected")
    assertThat(response.headers["WWW-Authenticate"]).isEqualTo("PT1")
  }

  @Test
  fun malformedUnknownAndWrongSignaturesAreRejected() {
    val ts = computer.serverTimeS()
    val nonce = "00112233445566778899aabbccddeeff"
    val good = PhoneCrypto.authorization(credentials.deviceId.value, credentials.key, "GET", LIBRARY, ts, nonce)
    val headers = listOf(
      "Bearer ${credentials.token}",
      good.replace("nonce=", "nonce=zz"),
      good.replace(credentials.deviceId.value, "dev_unknown"),
      PhoneCrypto.authorization(credentials.deviceId.value, ByteArray(32), "GET", LIBRARY, ts, nonce),
      PhoneCrypto.authorization(credentials.deviceId.value, credentials.key, "GET", "/api/phone/health", ts, nonce),
    )
    for (header in headers) {
      val response = TestPhone(computer).get(LIBRARY, mapOf("Authorization" to header))
      assertThat(response.errorCode).isEqualTo("credential_rejected")
    }
  }

  @Test
  fun aReplayedNonceIsRejected() {
    val header = PhoneCrypto.authorization(
      credentials.deviceId.value, credentials.key, "GET", LIBRARY, computer.serverTimeS(), "ab".repeat(16),
    )
    val raw = TestPhone(computer)

    assertThat(raw.get(LIBRARY, mapOf("Authorization" to header)).code).isEqualTo(200)
    assertThat(raw.get(LIBRARY, mapOf("Authorization" to header)).errorCode).isEqualTo("credential_rejected")
  }

  @Test
  fun aTimestampOutsideTheWindowIsClockSkewWithTheServerTime() {
    val ts = computer.serverTimeS() - 301
    val header = PhoneCrypto.authorization(credentials.deviceId.value, credentials.key, "GET", LIBRARY, ts, "cd".repeat(16))

    val response = TestPhone(computer).get(LIBRARY, mapOf("Authorization" to header))

    assertThat(response.code).isEqualTo(401)
    assertThat(response.errorCode).isEqualTo("clock_skew")
    assertThat(response.json["server_time"]!!.prim().long).isEqualTo(computer.serverTimeS())
  }

  @Test
  fun clockSkewMovesTheComputersClockAwayFromWhatThePhoneLearned() {
    val learned = computer.serverTimeS()
    computer.clockSkew(600)

    val header = PhoneCrypto.authorization(credentials.deviceId.value, credentials.key, "GET", LIBRARY, learned, "ef".repeat(16))
    val refused = TestPhone(computer).get(LIBRARY, mapOf("Authorization" to header))

    assertThat(refused.errorCode).isEqualTo("clock_skew")
    assertThat(refused.json["server_time"]!!.prim().long).isEqualTo(learned + 600)
    assertThat(phone.get(LIBRARY).code).isEqualTo(200) // a phone that re-learned the offset signs inside the window
  }

  @Test
  fun aRevokedDeviceIsRejectedAtItsNextRequest() {
    assertThat(phone.get(LIBRARY).code).isEqualTo(200)

    computer.revokeDevice(credentials.deviceId)

    assertThat(phone.get(LIBRARY).errorCode).isEqualTo("credential_rejected")
  }

  @Test
  fun aValidSignatureUpdatesLastSeen() {
    phone.get(LIBRARY)

    assertThat(computer.pairedDevices.single().lastSeenAtEpochS).isEqualTo(computer.serverTimeS())
    assertThat(computer.requests.last().signedBy).isEqualTo(credentials.deviceId)
  }

  @Test
  fun phoneAccessOffRefusesEveryPhoneRouteButHello() {
    computer.phoneAccessOff()

    val response = phone.get(LIBRARY)

    assertThat(response.code).isEqualTo(403)
    assertThat(response.errorCode).isEqualTo("phone_access_off")
    assertThat(phone.get("/api/phone/hello").code).isEqualTo(200)
  }

  @Test
  fun nonPhoneRoutesAreLanForbidden() {
    for (path in listOf("/api/health", "/api/browse", "/api/jobs", "/")) {
      val response = phone.get(path)
      assertThat(response.code).isEqualTo(403)
      assertThat(response.errorCode).isEqualTo("lan_forbidden")
    }
  }

  @Test
  fun anUnknownPhoneRouteIsAJsonUnknownRoute() {
    val response = phone.get("/api/phone/x")

    assertThat(response.code).isEqualTo(404)
    assertThat(response.errorCode).isEqualTo("unknown_route")
  }

  @Test
  fun dotSegmentsAndEncodedSlashesAreInvalidRequests() {
    val host = "Host: ${computer.address.host}:${computer.address.port}"
    for (path in listOf("/api/phone/../browse", "/api/phone/%2e%2e/health", "/api/phone/jobs%2Fx", "/api/phone/./hello")) {
      assertThat(TestPhone.rawStatus(computer, "GET $path HTTP/1.1\r\n$host\r\nConnection: close\r\n\r\n")).isEqualTo(400)
    }
  }

  @Test
  fun aHostThatIsNotTheComputersAddressIsBadHost() {
    for (host in listOf("attacker.example", "localhost:${computer.address.port}", "127.0.0.1:1")) {
      val response = phone.get("/api/phone/hello", mapOf("Host" to host))
      assertThat(response.code).isEqualTo(400)
      assertThat(response.errorCode).isEqualTo("bad_host")
    }
    assertThat(phone.get("/api/phone/hello", mapOf("Host" to "127.0.0.1")).code).isEqualTo(200)
  }

  @Test
  fun jsonBodiesNeedALengthAndAtMost16KiB() {
    val chunked = phone.send("POST", "/api/phone/jobs/link", TestPhone.chunked("{}"))
    assertThat(chunked.code).isEqualTo(411)
    assertThat(chunked.errorCode).isEqualTo("length_required")

    val big = phone.post("/api/phone/jobs/link", """{"pad": "${"x".repeat(16 * 1024)}"}""")
    assertThat(big.code).isEqualTo(413)
    assertThat(big.errorCode).isEqualTo("too_large")
  }

  @Test
  fun lanForbiddenAnswersEveryPhoneRouteHelloIncluded() {
    computer.lanForbidden()
    val unsigned = TestPhone(computer)

    for ((method, path) in ROUTES) {
      val body = if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(TestPhone.JSON)
      val response = unsigned.send(method, path, body)
      assertThat(response.code).isEqualTo(403)
      assertThat(response.errorCode).isEqualTo("lan_forbidden")
    }
  }

  @Test
  fun oldServer404sAndLegacyHealth() {
    computer.apiVersion = 5

    assertThat(phone.get("/api/phone/hello").code).isEqualTo(404)
    val health = phone.get("/api/health")
    assertThat(health.code).isEqualTo(200)
    assertThat(health.json["api_version"]!!.prim().int).isEqualTo(5)
  }

  @Test
  fun aFiveHundredFromFailNextHasNoCodeAndCountsDown() {
    computer.failNext("/api/phone/library", 503, times = 2)

    assertThat(phone.get(LIBRARY).code).isEqualTo(503)
    assertThat(phone.get(LIBRARY).errorCode).isNull()
    assertThat(phone.get(LIBRARY).code).isEqualTo(200)
  }

  @Test
  fun failNextCanCarryAStableCodeAndRetryAfter() {
    computer.failNext("/api/phone/jobs/*/cancel", 429, "rate_limited", method = "POST", retryAfterS = 7)
    val id = computer.addJob(JobSeed(status = JobStatus.QUEUED, deviceId = credentials.deviceId))

    val response = phone.post("/api/phone/jobs/${id.value}/cancel")

    assertThat(response.errorCode).isEqualTo("rate_limited")
    assertThat(response.headers["Retry-After"]).isEqualTo("7")
  }

  @Test
  fun everyJsonAnswerIsNoStore() {
    val response = phone.get(LIBRARY)

    assertThat(response.headers["Cache-Control"]).isEqualTo("no-store")
    assertThat(response.headers["Content-Type"]).startsWith("application/json")
  }

  @Test
  fun theRecordedRequestsKeepTheirOrderAndAuthorization() {
    TestPhone(computer).get("/api/phone/hello")
    phone.get(LIBRARY)

    val calls = computer.requests
    assertThat(calls.map { it.path }).containsExactly("/api/phone/hello", LIBRARY).inOrder()
    assertThat(calls[0].hadAuthorization).isFalse()
    assertThat(calls[1].hadAuthorization).isTrue()
    assertThat(calls.map { it.status }).containsExactly(200, 200).inOrder()
  }

  private companion object {
    const val LIBRARY = "/api/phone/library"
    val ROUTES = listOf(
      "GET" to "/api/phone/hello", "POST" to "/api/phone/pair", "GET" to "/api/phone/health",
      "DELETE" to "/api/phone/devices/self", "GET" to LIBRARY, "GET" to "/api/phone/jobs/20261010-091503-4be1",
      "GET" to "/api/phone/requests/00000000-0000-4000-8000-000000000001",
      "GET" to "/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix", "POST" to "/api/phone/jobs/link",
      "POST" to "/api/phone/uploads", "PUT" to "/api/phone/uploads/00000000-0000-4000-8000-000000000001",
      "DELETE" to "/api/phone/uploads/00000000-0000-4000-8000-000000000001",
      "POST" to "/api/phone/jobs/20261010-091503-4be1/cancel", "DELETE" to "/api/phone/jobs/20261010-091503-4be1",
      "POST" to "/api/phone/jobs/20261010-091503-4be1/rerun", "GET" to "/api/phone/reference",
      "GET" to "/api/phone/events",
    )
  }
}
