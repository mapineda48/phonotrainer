package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import org.junit.Test

/**
 * The reference vectors of server-contract 4.3 and 6.2, copied verbatim from WP-01's `tests/phone_fixtures.py`
 * (branch wp/WP-01 at c9ead42), where the server's own tests reproduce them. Every value is synthetic.
 */
class ReferenceVectorsTest {
  @Test
  fun theKeyIsTheSha256OfTheToken() {
    assertThat(PhoneCrypto.hex(PhoneCrypto.keyFromToken(TOKEN))).isEqualTo(KEY_HEX)
  }

  @Test
  fun everyRequestSignatureVectorIsReproduced() {
    val key = PhoneCrypto.keyFromToken(TOKEN)
    for ((method, target, mac) in SIGNATURES) {
      assertThat(PhoneCrypto.requestMac(key, method, target, TS, NONCE)).isEqualTo(mac)
    }
  }

  @Test
  fun theWholeHeaderOfTheFirstVectorIsReproduced() {
    val (method, target) = SIGNATURES.first()
    val header = PhoneCrypto.authorization(DEVICE_ID, PhoneCrypto.keyFromToken(TOKEN), method, target, TS, NONCE)

    assertThat(header).isEqualTo(HEADER)
  }

  @Test
  fun theHelloProofAndTheReactivationProofAreReproduced() {
    val key = PhoneCrypto.keyFromToken(TOKEN)

    assertThat(PhoneCrypto.helloProof(key, SERVER_ID, DEVICE_ID, CHALLENGE)).isEqualTo(HELLO_PROOF)
    assertThat(PhoneCrypto.reactivationProof(key, CODE)).isEqualTo(REACTIVATION_PROOF)
  }

  @Test
  fun theFakeComputerAcceptsTheReferenceHeaderAtTheReferenceTime() {
    FakeComputer(clock = FakeComputerClock.frozen()).start().use { computer ->
      val phone = computer.pairDevice()
      val response = TestPhone(computer).get(
        "/api/phone/health",
        mapOf(
          "Authorization" to PhoneCrypto.authorization(
            phone.deviceId.value, phone.key, "GET", "/api/phone/health", TS, NONCE,
          ),
        ),
      )

      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun theReferenceSignerSignsTheTargetAsSent() {
    val credentials = PhoneCredentials(DeviceId(DEVICE_ID), TOKEN, ServerId(SERVER_ID))
    val target = SIGNATURES[3].second

    FakeComputer().start().use { computer ->
      val client = okhttp3.OkHttpClient.Builder()
        .addInterceptor(ReferenceSigner(credentials) { TS })
        .build()
      client.newCall(okhttp3.Request.Builder().url(computer.url(target)).build()).execute().close()
      val header = computer.requests.single().headers.getValue("authorization")

      val nonce = Regex("nonce=([0-9a-f]{32})").find(header)!!.groupValues[1]
      val mac = Regex("mac=([0-9a-f]{64})").find(header)!!.groupValues[1]
      assertThat(header).startsWith("PT1 device=$DEVICE_ID, ts=$TS, nonce=")
      assertThat(mac).isEqualTo(PhoneCrypto.requestMac(credentials.key, "GET", target, TS, nonce))
    }
  }

  private companion object {
    // hygiene: test-vector (the next lines hold a synthetic token and its MACs)
    const val TOKEN = "pt1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" // hygiene: test-vector
    const val KEY_HEX = "b5f85503ce688bacc6fba9efa682fc78d6ea1cc93de4765e213f5af7176de5a6" // hygiene: test-vector
    const val DEVICE_ID = "dev_example1"
    const val SERVER_ID = "srv_example1"
    const val TS = 1_791_622_800L
    const val NONCE = "00112233445566778899aabbccddeeff"
    val SIGNATURES = listOf(
      Triple(
        "GET", "/api/phone/jobs/20261010-091503-4be1/artifacts/audio_mix",
        "a8b96a295b5eca4413be81636b8b80d004d488b19a3c668b6098f10ea8204440", // hygiene: test-vector
      ),
      Triple(
        "POST", "/api/phone/jobs/link",
        "37b0f8f47b41f3f51dbc435b77c4dc77bc932a711650a8f5a1e93ca1b1af23e3", // hygiene: test-vector
      ),
      Triple(
        "GET", "/api/phone/events",
        "fbadc4187b93dca65ddbbde0575679a13347727982d5192e9b43ba1772e4f406", // hygiene: test-vector
      ),
      Triple(
        "GET", "/api/phone/jobs/20261010-091503-4be1/contour?start=1.5&end=2.25",
        "417ee7d0a66de6004e9c10c246bd99a04d1b7833afef3980d36018f5ba747c20", // hygiene: test-vector
      ),
    )
    const val HEADER = "PT1 device=dev_example1, ts=1791622800, nonce=00112233445566778899aabbccddeeff, " + // hygiene: test-vector
      "mac=a8b96a295b5eca4413be81636b8b80d004d488b19a3c668b6098f10ea8204440" // hygiene: test-vector
    const val CHALLENGE = "0123456789abcdef0123456789abcdef"
    const val HELLO_PROOF = "f6113ae8ea4e0474d3b247c49e6a053e4d593ace49e2b3e745bf4da58391b296" // hygiene: test-vector
    const val CODE = "482913"
    const val REACTIVATION_PROOF = "6c25019dd33f72685983eea58450f519b69da49f1b31857490345bdd61e497b7" // hygiene: test-vector
  }
}
