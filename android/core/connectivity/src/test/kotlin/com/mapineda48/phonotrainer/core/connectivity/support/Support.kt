package com.mapineda48.phonotrainer.core.connectivity.support

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.ConnectivitySession
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryClient
import com.mapineda48.phonotrainer.core.connectivity.pairing.DefaultPairingRepository
import com.mapineda48.phonotrainer.core.connectivity.pairing.ForgetCleanup
import com.mapineda48.phonotrainer.core.connectivity.platform.AppForeground
import com.mapineda48.phonotrainer.core.connectivity.platform.AppVersion
import com.mapineda48.phonotrainer.core.connectivity.platform.BackgroundRestrictionMonitor
import com.mapineda48.phonotrainer.core.connectivity.platform.BackgroundRestrictionSource
import com.mapineda48.phonotrainer.core.connectivity.platform.ComputerWork
import com.mapineda48.phonotrainer.core.connectivity.status.DefaultComputerStatusRepository
import com.mapineda48.phonotrainer.core.connectivity.status.Prober
import com.mapineda48.phonotrainer.core.connectivity.status.Rediscovery
import com.mapineda48.phonotrainer.core.connectivity.status.StatusEngine
import com.mapineda48.phonotrainer.core.connectivity.status.StatusSinks
import com.mapineda48.phonotrainer.core.connectivity.status.StatusWords
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.PhoneCredentials
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeAnnouncer
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSyncTrigger
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeDiscoveryTransport
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeAddressPolicy
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeAllowedPeers
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeComputerNetworkBinder
import com.mapineda48.phonotrainer.core.testing.fake.network.FakeVerifiedPeers
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeDecoderCapabilities
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/** The phone's clock on the coroutine test scheduler: virtual time moves it (architecture 3.3). */
internal class SchedulerClock(
  private val scheduler: TestCoroutineScheduler,
  private val start: Instant = Instant.parse("2026-10-10T09:00:00Z"),
) : Clock {
  override fun now(): Instant = start.plusMillis(scheduler.currentTime)

  override fun zone(): ZoneId = ZoneOffset.UTC
}

/**
 * [PairingStore] in memory, like `DataStorePairingStore`: one record, the token kept apart and the key derived for each
 * use. [unreadable] makes the credential undecryptable (a restore on another phone); [failWrites] makes writes throw
 * DataStore's `IOException`.
 */
internal class InMemoryPairingStore : PairingStore {
  private val record = MutableStateFlow<PairingRecord?>(null)
  private var token: String? = null

  @Volatile var unreadable = false

  @Volatile var failWrites = false
  var clears = 0
    private set
  val saved = mutableListOf<PairingRecord>()

  override val pairing: Flow<PairingRecord?> = record

  val value: PairingRecord? get() = record.value

  override suspend fun current(): PairingRecord? = record.value

  override suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long) {
    try {
      if (failWrites) throw IOException("disk full")
      token = String(grant.token)
      val stored = PairingRecord(
        serverId = grant.serverId, serverName = grant.name, address = address, deviceId = grant.deviceId,
        deviceName = deviceName, libraryId = grant.libraryId, pairedAt = pairedAt, lastSeen = null,
        apiVersion = grant.apiVersion, capabilities = null, clockOffsetS = clockOffsetS, lastProbe = null,
        lastProbeAt = null, addressChangedAt = null, uploadThroughput = null,
      )
      saved += stored
      record.value = stored
    } finally {
      grant.token.fill(Char(0))
    }
  }

  /** The record and token a pairing with [computer] as [phone] leaves (without a pairing flow). */
  fun seed(phone: PhoneCredentials, computer: FakeComputer, address: HostPort = computer.address, name: String = computer.name) {
    token = phone.token
    record.value = PairingRecord(
      serverId = computer.serverId, serverName = name, address = address, deviceId = phone.deviceId,
      deviceName = "Test phone", libraryId = computer.libraryId, pairedAt = Instant.parse("2026-10-01T09:00:00Z"),
      lastSeen = null, apiVersion = 6, capabilities = null, clockOffsetS = 0, lastProbe = null, lastProbeAt = null,
      addressChangedAt = null, uploadThroughput = null,
    )
  }

  /** The pairing file lost to damage: reads as not paired, nothing else changed (integration log, WP-02 carry). */
  fun damage() {
    record.value = null
    token = null
  }

  override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> {
    val secret = token ?: return DeviceKeyResult.NotPaired
    if (record.value == null) return DeviceKeyResult.NotPaired
    if (unreadable) return DeviceKeyResult.Unreadable
    val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    return try {
      DeviceKeyResult.Ok(block(key))
    } finally {
      key.fill(0)
    }
  }

  override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = edit {
    it.copy(lastSeen = at, apiVersion = apiVersion, capabilities = capabilities ?: it.capabilities)
  }

  override suspend fun updateAddress(address: HostPort, at: Instant) = edit {
    if (it.address == address) it else it.copy(address = address, addressChangedAt = at)
  }

  override suspend fun updateLibraryId(libraryId: LibraryId) = edit { it.copy(libraryId = libraryId) }

  override suspend fun updateClockOffset(offsetS: Long) = edit { it.copy(clockOffsetS = offsetS) }

  override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = edit { it.copy(lastProbe = outcome, lastProbeAt = at) }

  override suspend fun recordUpload(bytes: Long, duration: Duration) = Unit

  override suspend fun clear() {
    if (failWrites) throw IOException("disk full")
    clears++
    record.value = null
    token = null
  }

  private fun edit(change: (PairingRecord) -> PairingRecord) {
    if (failWrites) throw IOException("disk full")
    record.update { it?.let(change) }
  }
}

internal class FixedAppVersion(override val code: Int = 1, override val name: String = "1.0.0") : AppVersion

internal class FakeAppForeground(initial: Boolean = true) : AppForeground {
  val state = MutableStateFlow(initial)
  override val foreground: StateFlow<Boolean> = state
}

internal class FakeBackgroundSource(var restricted: Boolean = false, var bucket: Int = 10) : BackgroundRestrictionSource {
  override fun restricted(): Boolean = restricted

  override fun standbyBucket(): Int = bucket
}

internal class FakeComputerWork : ComputerWork {
  var cancelled = 0
    private set

  override fun cancelAll() {
    cancelled++
  }
}

internal class FakeForgetCleanup : ForgetCleanup {
  val forgotten = mutableListOf<ServerId>()

  override suspend fun forget(serverId: ServerId) {
    forgotten += serverId
  }
}

/** The announced words, as the real strings give them. */
internal object PlainStatusWords : StatusWords {
  override fun of(status: ComputerStatus): String? = when (status) {
    is ComputerStatus.Connected -> "Connected to ${status.name}"
    ComputerStatus.Offline -> "Offline"
    is ComputerStatus.NotReachable -> "Your computer isn't reachable"
    else -> null
  }
}

/**
 * The connectivity core wired by hand as Hilt wires it, over [FakeComputer]s reached in memory through
 * [FakeComputerClient] (which checks identity proofs and signs as the real client does), with the network-api and
 * app fakes of work-plan 4.6 for everything WP-07 does not own. Probes, the 5 s re-probe and the schedule run on the
 * test's virtual time.
 */
internal class Harness(
  val scope: TestScope,
  val computers: List<FakeComputer> = listOf(FakeComputer()),
  foreground: Boolean = true,
  clientOverride: ((ComputerClient) -> ComputerClient)? = null,
) {
  val computer: FakeComputer get() = computers.first()
  val clock = SchedulerClock(scope.testScheduler)
  val store = InMemoryPairingStore()
  val peers = FakeAllowedPeers()
  val verified = FakeVerifiedPeers()
  val binder = FakeComputerNetworkBinder()
  val monitor = FakeNetworkMonitor()
  val discovery = FakeDiscoveryTransport()
  val policy = FakeAddressPolicy()
  val fakeClient = FakeComputerClient(computers, credentials = null, currentPeer = { peers.current.value })
  val client: ComputerClient = clientOverride?.invoke(fakeClient) ?: fakeClient
  val announcer = FakeAnnouncer()
  val syncTrigger = FakeSyncTrigger()
  val requests = FakeRequestRepository()
  val sync = FakeSyncController()
  val decoders = FakeDecoderCapabilities()
  val appForeground = FakeAppForeground(foreground)
  val backgroundSource = FakeBackgroundSource()
  val background = BackgroundRestrictionMonitor(backgroundSource, PhonoLog.NONE)
  val work = FakeComputerWork()
  val cleanup = FakeForgetCleanup()
  val session = ConnectivitySession()
  val appVersion = FixedAppVersion()
  val discoveryClient = DiscoveryClient(discovery, policy)
  val prober = Prober(client, peers, store, appVersion, session)
  val rediscovery = Rediscovery(discoveryClient, client, peers, session)
  val sinks = StatusSinks(store, peers, verified, binder, announcer, PlainStatusWords, clock, scope.backgroundScope, PhonoLog.NONE)
  val engine = StatusEngine(scope.backgroundScope, clock, prober, rediscovery, sinks, PhonoLog.NONE)

  val status: DefaultComputerStatusRepository by lazy {
    DefaultComputerStatusRepository(
      engine, store, monitor, appForeground, background, Provider { requests }, Provider { sync }, Provider { decoders },
      scope.backgroundScope, PhonoLog.NONE,
    )
  }

  val pairing: DefaultPairingRepository by lazy {
    DefaultPairingRepository(
      client, peers, verified, policy, store, monitor, discoveryClient, status, Provider { syncTrigger },
      work, cleanup, appVersion, session, clock, PhonoLog.NONE,
    )
  }

  /** A pairing with [computer] as a fresh device, without the pairing flow: the store's record and the client's key. */
  fun seedPairing(computer: FakeComputer = this.computer, address: HostPort = computer.address): PhoneCredentials {
    val phone = computer.pairDevice()
    fakeClient.credentials = phone
    store.seed(phone, computer, address)
    return phone
  }

  /** Makes every computer answer discovery as it would on the home network (nonce echoed, from its address). */
  fun answerDiscovery(computer: FakeComputer = this.computer, sourceHost: String = FakeComputer.HOST) {
    discovery.respond { probe -> computer.answerProbe(probe)?.let { Datagram(it, sourceHost, DISCOVERY_PORT) } }
  }

  companion object {
    const val DISCOVERY_PORT = 45317
  }
}
