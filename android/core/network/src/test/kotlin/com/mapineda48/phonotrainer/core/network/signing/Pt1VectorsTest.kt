package com.mapineda48.phonotrainer.core.network.signing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.network.support.InMemoryPairingStore
import com.mapineda48.phonotrainer.core.network.support.PhoneClock
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeClockOffset
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * The reference vectors of server-contract 4.3 and 6.2, copied verbatim from WP-01's `tests/phone_fixtures.py`, where
 * the server's own tests reproduce them, reproduced by the production signer: the formulas, the request target as
 * OkHttp sends it, and the whole header built from a paired store, the phone's clock and a nonce. Every value is
 * synthetic.
 */
class Pt1VectorsTest {
  private val key = Sha256.digest(TOKEN.toByteArray(Charsets.UTF_8))

  @Test
  fun theDeviceKeyIsTheSha256OfTheToken() {
    assertThat(Sha256.toHex(key)).isEqualTo(KEY_HEX)
  }

  @Test
  fun everyRequestSignatureVectorIsReproduced() {
    for ((method, target, mac) in SIGNATURES) {
      assertThat(Pt1.requestMac(key, method, target, TS, NONCE)).isEqualTo(mac)
    }
  }

  @Test
  fun theWholeHeaderAndTheHelloProofAreReproduced() {
    val (method, target) = SIGNATURES.first()

    assertThat(Pt1.authorization(DEVICE_ID, key, method, target, TS, NONCE)).isEqualTo(HEADER)
    assertThat(Pt1.helloProof(key, SERVER_ID, DEVICE_ID, CHALLENGE)).isEqualTo(HELLO_PROOF)
  }

  @Test
  fun theSignedTargetIsThePathAndQueryAsSent() {
    for ((_, target) in SIGNATURES) {
      assertThat(SigningInterceptor.target("http://127.0.0.1:8000$target".toHttpUrl())).isEqualTo(target)
    }
  }

  @Test
  fun theProductionSignerBuildsTheReferenceHeaderFromThePairingStore() = runTest {
    val store = InMemoryPairingStore()
    store.save(
      PairGrant(
        DeviceId(DEVICE_ID), TOKEN.toCharArray(), ServerId(SERVER_ID), "Study desk", LibraryId("lib_example1"), 6, 1, TS,
      ),
      HostPort("127.0.0.1", 8000), "Test phone", Instant.ofEpochSecond(TS), 0,
    )
    // The phone's clock runs 90 s behind the computer; the learned offset brings ts back to the reference time.
    val clock = PhoneClock { Instant.ofEpochSecond(TS - 90) }
    val offset = FakeClockOffset(phoneNowS = TS - 90).apply { update(TS) }
    val signer = PairingRequestSigner(store, clock, offset) { NONCE }

    val (method, target) = SIGNATURES.first()
    assertThat(signer.authorization(method, target)).isEqualTo(HEADER)
  }

  @Test
  fun aNonceIsThirtyTwoFreshLowerCaseHexCharacters() {
    val nonces = List(64) { Pt1.newNonce() }

    assertThat(nonces.toSet()).hasSize(64)
    nonces.forEach { assertThat(it).matches("^[0-9a-f]{32}$") }
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
  }
}
