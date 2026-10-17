package com.mapineda48.phonotrainer.e2e.flows

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pairing through the real stack on the device (work-plan WP-07; wave-1 review, WP-02 partial 2): the real
 * `PairingRepository` and `ComputerStatusRepository` (`:core:connectivity`), the real HTTP client (`:core:network`)
 * and the real pairing store, whose token is sealed by `KeystoreCredentialCipher` with a key in the platform
 * AndroidKeyStore. "Connected" proves the round trip: the computer's `hello` proof is checked, and `health` signed,
 * with the key derived from the token the keystore decrypted. "Forget" deletes that key.
 */
@HiltAndroidTest
class PairingOnDeviceTest {
  @get:Rule
  val hilt = HiltAndroidRule(this)

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var computer: FakeComputer

  @Inject lateinit var status: ComputerStatusRepository

  @Inject lateinit var pairing: PairingRepository

  @Inject lateinit var store: PairingStore

  @Before
  fun inject() = hilt.inject()

  @Test
  fun aPairingSealedInTheAndroidKeyStoreProvesTheComputerAndForgetDeletesItsKey() = runBlocking {
    val paired = seeder.pair()

    withTimeout(10.seconds) { status.status.first { it is ComputerStatus.Connected } }
    assertThat(status.checkNow()).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.Ok(32))
    assertThat(computer.requests.filter { it.path == "/api/phone/health" }.map { it.signedBy }.distinct())
      .containsExactly(paired.deviceId)

    assertThat(pairing.forget()).isEqualTo(ForgetResult.Forgotten)
    assertThat(status.status.value).isEqualTo(ComputerStatus.NotConnected)
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.NotPaired)
    assertThat(computer.pairedDevices.single { it.deviceId == paired.deviceId }.revoked).isTrue()
  }
}
