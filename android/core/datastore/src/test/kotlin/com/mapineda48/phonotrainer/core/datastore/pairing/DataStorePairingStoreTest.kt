package com.mapineda48.phonotrainer.core.datastore.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.datastore.credential.FakeAndroidKeyStore
import com.mapineda48.phonotrainer.core.datastore.credential.KeystoreCredentialCipher
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pairing store of architecture 4.5 and 6.3 on a real preferences file and the keystore cipher (over the fake
 * keystore): one write at pairing, the token only encrypted, the device key only inside the call, no partial record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStorePairingStoreTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val scope = TestScope(UnconfinedTestDispatcher())
  private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
  private val storeScope = CoroutineScope(dispatcher)
  private val file by lazy { File(folder.root, "pairing.preferences_pb") }
  private val dataStore: DataStore<Preferences> by lazy { PreferenceDataStoreFactory.create(scope = storeScope) { file } }
  private val cipher = KeystoreCredentialCipher()
  private val store by lazy { DataStorePairingStore(dataStore, cipher, dispatcher) }

  private val pairedAt = Instant.parse("2026-10-10T09:00:00Z")
  private val address = HostPort("192.0.2.10", 8000)
  private val tokenText = "test-token-one"

  private fun grant(token: String = tokenText, server: String = "srv_one") = PairGrant(
    deviceId = DeviceId("dev_one"),
    token = token.toCharArray(),
    serverId = ServerId(server),
    name = "Study computer",
    libraryId = LibraryId("lib_one"),
    apiVersion = 6,
    minAppVersion = 1,
    serverTime = 1_791_622_800,
  )

  private val capabilities = Capabilities(
    linkDownloads = true,
    downloaderVersion = "2026.8.19",
    dialogueSeparation = true,
    renditions = false,
    phoneVideo = false,
    resumableUploads = false,
    recordCompare = false,
    maxUploadBytes = 4_000_000_000,
    maxChunkBytes = 8_388_608,
    maxLinkDurationS = 7_200,
    freeBytes = 120_000_000_000,
  )

  @Before
  fun setUp() = FakeAndroidKeyStore.install()

  @After
  fun tearDown() {
    FakeAndroidKeyStore.uninstall()
    storeScope.cancel()
    scope.cancel()
  }

  @Test
  fun nothingIsPairedAtFirstAndUpdatesWriteNothing() = runTest(scope.testScheduler) {
    assertThat(store.current()).isNull()
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.NotPaired)

    store.recordSeen(pairedAt, 6, capabilities)
    store.updateAddress(address, pairedAt)
    store.updateLibraryId(LibraryId("lib_two"))
    store.updateClockOffset(5)
    store.recordProbe(ProbeOutcome.Ok, pairedAt)
    store.recordUpload(1_000_000, Duration.ofSeconds(1))

    assertThat(store.current()).isNull()
    assertThat(dataStore.data.first().asMap()).isEmpty()
  }

  @Test
  fun aPairingIsStoredInOneWriteAndTheTokenIsWiped() = runTest(scope.testScheduler) {
    val grant = grant()
    store.save(grant, address, "Pixel test phone", pairedAt, clockOffsetS = -3)

    assertThat(store.current()).isEqualTo(
      PairingRecord(
        serverId = ServerId("srv_one"),
        serverName = "Study computer",
        address = address,
        deviceId = DeviceId("dev_one"),
        deviceName = "Pixel test phone",
        libraryId = LibraryId("lib_one"),
        pairedAt = pairedAt,
        lastSeen = null,
        apiVersion = 6,
        capabilities = null,
        clockOffsetS = -3,
        lastProbe = null,
        lastProbeAt = null,
        addressChangedAt = null,
        uploadThroughput = null,
      ),
    )
    assertThat(grant.token.all { it == Char(0) }).isTrue()
  }

  @Test
  fun theFileHoldsTheTokenOnlyEncrypted() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)

    val raw = String(file.readBytes(), Charsets.ISO_8859_1)
    assertThat(raw).doesNotContain(tokenText)
    assertThat(raw).contains("dev_one")
  }

  @Test
  fun theDeviceKeyIsTheHashOfTheTokenAndIsWipedAfterTheCall() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    var seen: ByteArray? = null

    val result = store.withDeviceKey { key ->
      seen = key
      Sha256.toHex(key)
    }

    assertThat(result).isEqualTo(DeviceKeyResult.Ok(Sha256.hex(tokenText.toByteArray())))
    assertThat(seen!!.all { it == 0.toByte() }).isTrue()
  }

  @Test
  fun anUndecryptableCredentialReadsAsUnreadable() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    FakeAndroidKeyStore.keys[KeystoreCredentialCipher.ALIAS] = SecretKeySpec(ByteArray(32) { 1 }, "AES")

    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.Unreadable)
    // The pairing itself is still there: "Connect again" needs its server and device ids (architecture 6.3).
    assertThat(store.current()?.deviceId).isEqualTo(DeviceId("dev_one"))
  }

  @Test
  fun updatesChangeOnlyTheirFields() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    val later = pairedAt.plusSeconds(60)

    store.recordSeen(later, 7, capabilities)
    store.updateLibraryId(LibraryId("lib_two"))
    store.updateClockOffset(42)
    store.recordProbe(ProbeOutcome.NoAnswer(3), later)

    val record = store.current()!!
    assertThat(record.lastSeen).isEqualTo(later)
    assertThat(record.apiVersion).isEqualTo(7)
    assertThat(record.capabilities).isEqualTo(capabilities)
    assertThat(record.libraryId).isEqualTo(LibraryId("lib_two"))
    assertThat(record.clockOffsetS).isEqualTo(42)
    assertThat(record.lastProbe).isEqualTo(ProbeOutcome.NoAnswer(3))
    assertThat(record.lastProbeAt).isEqualTo(later)
    assertThat(record.address).isEqualTo(address)
    assertThat(record.addressChangedAt).isNull()

    // A health answer without capabilities keeps the last ones.
    store.recordSeen(later.plusSeconds(1), 7)
    assertThat(store.current()!!.capabilities).isEqualTo(capabilities)
  }

  @Test
  fun anAddressChangeIsDatedOnlyWhenTheAddressDiffers() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)

    store.updateAddress(address, pairedAt.plusSeconds(5))
    assertThat(store.current()!!.addressChangedAt).isNull()

    val moved = HostPort("192.0.2.11", 8000)
    store.updateAddress(moved, pairedAt.plusSeconds(10))
    assertThat(store.current()!!.address).isEqualTo(moved)
    assertThat(store.current()!!.addressChangedAt).isEqualTo(pairedAt.plusSeconds(10))
  }

  @Test
  fun everyProbeOutcomeRoundTrips() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    val outcomes = listOf(
      ProbeOutcome.Ok,
      ProbeOutcome.NoAnswer(10),
      ProbeOutcome.Refused,
      ProbeOutcome.NoRoute,
      ProbeOutcome.Reset,
      ProbeOutcome.VpnBlocks,
      ProbeOutcome.PhoneAccessOff,
      ProbeOutcome.NotPhonoTrainer,
      ProbeOutcome.ProofFailed,
    )
    outcomes.forEach { outcome ->
      store.recordProbe(outcome, pairedAt)
      assertThat(store.current()!!.lastProbe).isEqualTo(outcome)
    }
    assertThat(ProbeCodec.decode("a_future_outcome")).isNull()
  }

  @Test
  fun uploadThroughputIsTheAverageOfTheLastFiveUploads() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    assertThat(store.current()!!.uploadThroughput).isNull()

    store.recordUpload(2_000_000, Duration.ofSeconds(2)) // 1 MB/s
    assertThat(store.current()!!.uploadThroughput).isEqualTo(1_000_000.0)

    store.recordUpload(3_000_000, Duration.ofSeconds(1)) // 3 MB/s
    assertThat(store.current()!!.uploadThroughput).isEqualTo(2_000_000.0)

    listOf(5L, 5L, 5L, 5L).forEach { store.recordUpload(it * 1_000_000, Duration.ofSeconds(1)) }
    // The last five: 3, 5, 5, 5, 5 MB/s; the first sample left the average.
    assertThat(store.current()!!.uploadThroughput).isEqualTo(4_600_000.0)

    // Nothing measured is not a sample.
    store.recordUpload(0, Duration.ofSeconds(1))
    store.recordUpload(1_000_000, Duration.ZERO)
    store.recordUpload(1_000_000, Duration.ofSeconds(-1))
    assertThat(store.current()!!.uploadThroughput).isEqualTo(4_600_000.0)
  }

  @Test
  fun aNewStoreOnTheSameFileReadsTheSamePairing() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 9)
    store.recordSeen(pairedAt, 6, capabilities)
    store.recordUpload(1_000_000, Duration.ofSeconds(1))
    val before = store.current()

    val reopened = DataStorePairingStore(dataStore, KeystoreCredentialCipher(), dispatcher)
    assertThat(reopened.current()).isEqualTo(before)
    assertThat(reopened.withDeviceKey { Sha256.toHex(it) })
      .isEqualTo(DeviceKeyResult.Ok(Sha256.hex(tokenText.toByteArray())))
  }

  @Test
  fun aNewPairingReplacesEverythingOfTheOldOne() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)
    store.recordSeen(pairedAt, 6, capabilities)
    store.recordUpload(1_000_000, Duration.ofSeconds(1))
    store.recordProbe(ProbeOutcome.Refused, pairedAt)

    store.save(grant(token = "test-token-two", server = "srv_two"), address, "Pixel test phone", pairedAt, 0)

    val record = store.current()!!
    assertThat(record.serverId).isEqualTo(ServerId("srv_two"))
    assertThat(record.capabilities).isNull()
    assertThat(record.uploadThroughput).isNull()
    assertThat(record.lastProbe).isNull()
    assertThat(store.withDeviceKey { Sha256.toHex(it) })
      .isEqualTo(DeviceKeyResult.Ok(Sha256.hex("test-token-two".toByteArray())))
  }

  @Test
  fun forgettingRemovesThePairingAndTheKey() = runTest(scope.testScheduler) {
    store.save(grant(), address, "Pixel test phone", pairedAt, 0)

    store.clear()

    assertThat(store.current()).isNull()
    assertThat(store.pairing.first()).isNull()
    assertThat(dataStore.data.first().asMap()).isEmpty()
    assertThat(FakeAndroidKeyStore.keys).isEmpty()
    assertThat(store.withDeviceKey { it.size }).isEqualTo(DeviceKeyResult.NotPaired)
  }
}
