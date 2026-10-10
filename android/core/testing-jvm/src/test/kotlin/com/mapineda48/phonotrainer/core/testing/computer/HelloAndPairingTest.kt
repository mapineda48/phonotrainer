package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Test

/** `GET /api/phone/hello` and `POST /api/phone/pair` (server-contract 2.3, 6.2) and their codes. */
class HelloAndPairingTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val phone = TestPhone(computer)

  @After
  fun tearDown() = computer.close()

  @Test
  fun helloWithoutAChallengeGivesIdentityAndNoProof() {
    val hello = phone.get("/api/phone/hello").json

    assertThat(hello.str("service")).isEqualTo("phonotrainer")
    assertThat(hello["phone_access"]!!.prim().boolean).isTrue()
    assertThat(hello.str("server_id")).isEqualTo(computer.serverId.value)
    assertThat(hello.str("name")).isEqualTo("PhonoTrainer computer")
    assertThat(hello["api_version"]!!.prim().int).isEqualTo(6)
    assertThat(hello["server_time"]!!.prim().long).isEqualTo(FakeComputerClock.REFERENCE_EPOCH_S)
    assertThat(hello["proof"]).isEqualTo(JsonNull)
    assertThat(hello["revoked"]).isEqualTo(JsonNull)
  }

  @Test
  fun helloProvesIdentityToAPairedPhoneAndSaysWhenItIsRevoked() {
    val credentials = computer.pairDevice()
    val challenge = "0123456789abcdef0123456789abcdef"
    val path = "/api/phone/hello?device_id=${credentials.deviceId.value}&challenge=$challenge"

    val hello = phone.get(path).json
    val expected = PhoneCrypto.helloProof(credentials.key, computer.serverId.value, credentials.deviceId.value, challenge)
    assertThat(hello.str("proof")).isEqualTo(expected)
    assertThat(hello["revoked"]!!.prim().boolean).isFalse()

    computer.revokeDevice(credentials.deviceId)
    val revoked = phone.get(path).json
    assertThat(revoked.str("proof")).isEqualTo(expected)
    assertThat(revoked["revoked"]!!.prim().boolean).isTrue()
  }

  @Test
  fun helloForAnUnknownDeviceHasNoProof() {
    val hello = phone.get("/api/phone/hello?device_id=dev_unknown&challenge=${"ab".repeat(16)}").json

    assertThat(hello["proof"]).isEqualTo(JsonNull)
    assertThat(hello["revoked"]).isEqualTo(JsonNull)
  }

  @Test
  fun helloRefusesAMalformedOrHalfChallenge() {
    for (query in listOf("device_id=dev_x&challenge=XYZ", "device_id=dev_x", "challenge=${"ab".repeat(16)}")) {
      val response = phone.get("/api/phone/hello?$query")
      assertThat(response.code).isEqualTo(400)
      assertThat(response.errorCode).isEqualTo("invalid_request")
    }
  }

  @Test
  fun helloWithPhoneAccessOffSaysOnlyThat() {
    computer.phoneAccessOff()

    val hello = phone.get("/api/phone/hello").json

    assertThat(hello.keys).containsExactly("service", "phone_access", "api_version", "server_time")
    assertThat(hello["phone_access"]!!.prim().boolean).isFalse()
  }

  @Test
  fun challengesAreRateLimitedPerSource() {
    val credentials = computer.pairDevice()
    val path = "/api/phone/hello?device_id=${credentials.deviceId.value}&challenge=${"cd".repeat(16)}"
    repeat(20) { assertThat(phone.get(path).code).isEqualTo(200) }

    val limited = phone.get(path)
    assertThat(limited.code).isEqualTo(429)
    assertThat(limited.errorCode).isEqualTo("rate_limited")
    assertThat(limited.headers["Retry-After"]!!.toInt()).isAtLeast(1)

    computer.clock.advanceSeconds(61)
    assertThat(phone.get(path).code).isEqualTo(200)
  }

  @Test
  fun pairingGrantsATokenWhoseKeySignsRequests() {
    val code = computer.newPairingCode()

    val grant = pair(code)

    assertThat(grant.code).isEqualTo(201)
    val body = grant.json
    assertThat(body.str("token")).matches("pt1_[A-Za-z0-9_-]{43}")
    assertThat(body.str("device_id")).matches("dev_[a-z2-7]{26}")
    assertThat(body.str("server_id")).isEqualTo(computer.serverId.value)
    assertThat(body.str("library_id")).isEqualTo(computer.libraryId.value)
    assertThat(body["min_app_version"]!!.prim().int).isEqualTo(1)
    phone.credentials = credentialsOf(grant)
    assertThat(phone.get("/api/phone/health").code).isEqualTo(200)
    assertThat(computer.pairedDevices.single().name).isEqualTo("Pixel 9")
  }

  @Test
  fun aCodeIsSingleUse() {
    val code = computer.newPairingCode()
    assertThat(pair(code).code).isEqualTo(201)

    val again = pair(code)

    assertThat(again.code).isEqualTo(403)
    assertThat(again.errorCode).isEqualTo("code_invalid")
  }

  @Test
  fun withoutACodeEveryAttemptIsCodeInvalid() {
    val response = pair("482913")

    assertThat(response.code).isEqualTo(403)
    assertThat(response.errorCode).isEqualTo("code_invalid")
  }

  @Test
  fun aCodeExpiresAfterFiveMinutes() {
    val code = computer.newPairingCode()
    computer.clock.advanceSeconds(300)

    val response = pair(code)

    assertThat(response.code).isEqualTo(403)
    assertThat(response.errorCode).isEqualTo("code_expired")
  }

  @Test
  fun fiveWrongAttemptsLockTheCodeEvenForTheRightDigits() {
    val code = computer.newPairingCode("482913")
    repeat(5) { assertThat(pair("000000").errorCode).isEqualTo("code_invalid") }

    val locked = pair(code)

    assertThat(locked.code).isEqualTo(403)
    assertThat(locked.errorCode).isEqualTo("code_locked")
  }

  @Test
  fun pairingIsRateLimited() {
    computer.newPairingCode("482913")
    repeat(5) { pair("000000") } // locks the code; attempts keep counting
    repeat(5) { pair("000000") }

    val limited = pair("000000")

    assertThat(limited.code).isEqualTo(429)
    assertThat(limited.errorCode).isEqualTo("rate_limited")
    assertThat(limited.headers["Retry-After"]).isNotNull()
  }

  @Test
  fun anOldAppIsRefusedWithoutConsumingTheCode() {
    computer.minAppVersion = 2
    val code = computer.newPairingCode()

    val old = pair(code, appVersion = 1)
    computer.minAppVersion = 1

    assertThat(old.code).isEqualTo(426)
    assertThat(old.errorCode).isEqualTo("app_too_old")
    assertThat(pair(code).code).isEqualTo(201)
  }

  @Test
  fun pairingWithPhoneAccessOffIsRefused() {
    val code = computer.newPairingCode()
    computer.phoneAccessOff()

    val response = pair(code)

    assertThat(response.code).isEqualTo(403)
    assertThat(response.errorCode).isEqualTo("phone_access_off")
  }

  @Test
  fun malformedPairingRequestsAreInvalid() {
    computer.newPairingCode()
    val bodies = listOf(
      """{"code": "48291", "device_name": "Pixel 9", "app_version": 1}""",
      """{"code": "482913", "device_name": "", "app_version": 1}""",
      """{"code": "482913", "device_name": "Pixel 9", "app_version": "1"}""",
      """{"code": "482913", "device_name": "Pixel 9", "app_version": 1, "previous_device_id": "dev_x"}""",
      """not json""",
    )
    for (body in bodies) {
      val response = phone.post("/api/phone/pair", body)
      assertThat(response.code).isEqualTo(400)
      assertThat(response.errorCode).isEqualTo("invalid_request")
    }
  }

  @Test
  fun connectAgainWithAValidProofKeepsTheDeviceIdAndKillsTheOldKey() {
    val old = computer.pairDevice("Pixel 9")
    computer.revokeDevice(old.deviceId)
    val code = computer.newPairingCode()

    val grant = pair(code, previous = old.deviceId.value, proof = PhoneCrypto.reactivationProof(old.key, code))

    assertThat(grant.json.str("device_id")).isEqualTo(old.deviceId.value)
    phone.credentials = old
    assertThat(phone.get("/api/phone/health").errorCode).isEqualTo("credential_rejected")
    phone.credentials = credentialsOf(grant)
    assertThat(phone.get("/api/phone/health").code).isEqualTo(200)
  }

  @Test
  fun aWrongReactivationProofCreatesANewDevice() {
    val old = computer.pairDevice("Pixel 9")
    val code = computer.newPairingCode()

    val grant = pair(code, previous = old.deviceId.value, proof = "00".repeat(32))

    assertThat(grant.code).isEqualTo(201)
    assertThat(grant.json.str("device_id")).isNotEqualTo(old.deviceId.value)
    assertThat(computer.pairedDevices).hasSize(2)
  }

  @Test
  fun namesAreCleanedOfControlAndFormatCharacters() {
    val code = computer.newPairingCode()

    pair(code, name = "Pix\\u202Eel\\u0007 9\\u200B") // JSON escapes: RLO, BEL, ZWSP

    assertThat(computer.pairedDevices.single().name).isEqualTo("Pixel 9")
  }

  @Test
  fun unpairingSelfRevokesTheCallerAndARepeatIsRejected() {
    phone.credentials = computer.pairDevice()

    assertThat(phone.delete("/api/phone/devices/self").code).isEqualTo(204)
    val again = phone.delete("/api/phone/devices/self")

    assertThat(again.code).isEqualTo(401)
    assertThat(again.errorCode).isEqualTo("credential_rejected")
    assertThat(computer.pairedDevices.single().revoked).isTrue()
  }

  private fun pair(
    code: String,
    appVersion: Int = 1,
    previous: String? = null,
    proof: String? = null,
    name: String = "Pixel 9",
  ): TestResponse {
    val previousJson = previous?.let { "\"$it\"" } ?: "null"
    val proofJson = proof?.let { "\"$it\"" } ?: "null"
    return phone.post(
      "/api/phone/pair",
      """{"code": "$code", "device_name": "$name", "app_version": $appVersion, "app_version_name": "1.0.0", """ +
        """"previous_device_id": $previousJson, "previous_token_proof": $proofJson}""",
    )
  }

  private fun credentialsOf(grant: TestResponse) = PhoneCredentials(
    DeviceId(grant.json.str("device_id")!!), grant.json.str("token")!!, computer.serverId,
  )
}
