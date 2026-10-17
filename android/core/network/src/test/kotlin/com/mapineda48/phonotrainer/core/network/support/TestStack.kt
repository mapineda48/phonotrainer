package com.mapineda48.phonotrainer.core.network.support

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.binding.NetworkPlatform
import com.mapineda48.phonotrainer.core.network.channel.WebSocketJobChannel
import com.mapineda48.phonotrainer.core.network.client.HttpComputerClient
import com.mapineda48.phonotrainer.core.network.guard.CleartextGuard
import com.mapineda48.phonotrainer.core.network.guard.ComputerSocketFactory
import com.mapineda48.phonotrainer.core.network.guard.NoDns
import com.mapineda48.phonotrainer.core.network.http.ComputerHttp
import com.mapineda48.phonotrainer.core.network.http.NetworkTimeouts
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import com.mapineda48.phonotrainer.core.network.peers.ComputerVerifiedPeers
import com.mapineda48.phonotrainer.core.network.peers.LearnedClockOffset
import com.mapineda48.phonotrainer.core.network.service.ApiFactory
import com.mapineda48.phonotrainer.core.network.signing.PairingRequestSigner
import com.mapineda48.phonotrainer.core.network.signing.SigningInterceptor
import com.mapineda48.phonotrainer.core.network.transfer.HttpArtifactTransfer
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Dns
import okhttp3.Interceptor

/** The address policy of the tests: the release ranges plus the loopback the fake computer listens on. */
internal object LoopbackPolicy : AddressPolicy {
  override fun allowsCandidate(host: String): Boolean = host == FakeComputer.HOST
}

/** The phone's clock, read from the fake computer's clock without its skew, so the two agree unless a test skews. */
internal class PhoneClock(private val source: () -> Instant) : Clock {
  override fun now(): Instant = source()

  override fun zone(): ZoneId = ZoneOffset.UTC
}

/** A binder that is not the client's [NetworkBinding]: sockets are not bound (the end-to-end test binder). */
internal object UnboundBinder : ComputerNetworkBinder {
  override fun bind(networkHandle: Long?) = Unit
}

/** A platform that records which network each socket was bound to; [failWith] makes the next bindings fail. */
internal class RecordingPlatform : NetworkPlatform {
  val bound = CopyOnWriteArrayList<Pair<Long, Socket>>()

  @Volatile var failWith: IOException? = null

  override fun bindSocket(networkHandle: Long, socket: Socket) {
    failWith?.let { throw it }
    bound += networkHandle to socket
  }

  override fun isPermissionDenied(error: IOException): Boolean = "EPERM" in error.message.orEmpty()
}

/** Counts every lookup and refuses it, as the client's resolver does. */
internal class CountingDns : Dns {
  val lookups = CopyOnWriteArrayList<String>()

  override fun lookup(hostname: String): List<InetAddress> {
    lookups += hostname
    return NoDns.lookup(hostname)
  }
}

/**
 * [PairingStore] in memory: the token kept as characters, the key derived for each use and wiped after it, as
 * `DataStorePairingStore` does (architecture 20.3). Updates are atomic, as DataStore's transactions are.
 */
internal class InMemoryPairingStore : PairingStore {
  private val record = MutableStateFlow<PairingRecord?>(null)
  private var token: CharArray? = null

  /** Makes the stored credential unreadable, as after a restore on another phone. */
  @Volatile var unreadable = false

  override val pairing: Flow<PairingRecord?> = record

  override suspend fun current(): PairingRecord? = record.value

  override suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long) {
    token = grant.token.copyOf()
    grant.token.fill('\u0000')
    record.value = PairingRecord(
      serverId = grant.serverId, serverName = grant.name, address = address, deviceId = grant.deviceId,
      deviceName = deviceName, libraryId = grant.libraryId, pairedAt = pairedAt, lastSeen = null,
      apiVersion = grant.apiVersion, capabilities = null, clockOffsetS = clockOffsetS, lastProbe = null,
      lastProbeAt = null, addressChangedAt = null, uploadThroughput = null,
    )
  }

  override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> {
    val secret = token ?: return DeviceKeyResult.NotPaired
    if (unreadable) return DeviceKeyResult.Unreadable
    val key = MessageDigest.getInstance("SHA-256").digest(String(secret).toByteArray(Charsets.UTF_8))
    return try {
      DeviceKeyResult.Ok(block(key))
    } finally {
      key.fill(0)
    }
  }

  override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = Unit

  override suspend fun updateAddress(address: HostPort, at: Instant) {
    record.update { it?.copy(address = address) }
  }

  override suspend fun updateLibraryId(libraryId: LibraryId) = Unit

  override suspend fun updateClockOffset(offsetS: Long) {
    record.update { it?.copy(clockOffsetS = offsetS) }
  }

  override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = Unit

  override suspend fun recordUpload(bytes: Long, duration: Duration) = Unit

  override suspend fun clear() {
    record.value = null
    token = null
  }
}

/**
 * The client's whole stack wired by hand exactly as `NetworkModule` wires it, against a [FakeComputer] over real HTTP
 * on the loopback interface. [binder] is the client's own [NetworkBinding] when [platform] is given (sockets bound
 * through it), otherwise a binder that binds nothing.
 */
internal class TestStack(
  val computer: FakeComputer,
  val timeouts: NetworkTimeouts = NetworkTimeouts(),
  val platform: RecordingPlatform? = null,
  val dns: Dns = NoDns,
  val log: PhonoLog = PhonoLog.NONE,
  debugLog: Interceptor? = null,
  val store: InMemoryPairingStore = InMemoryPairingStore(),
  policy: AddressPolicy = LoopbackPolicy,
  phoneClock: Clock? = null,
) : AutoCloseable {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val clock: Clock = phoneClock ?: PhoneClock { computer.clock.now() }
  val binder: ComputerNetworkBinder = platform?.let(::NetworkBinding) ?: UnboundBinder
  val peers = ComputerPeers()
  val verified = ComputerVerifiedPeers(binder, peers)
  val clockOffset = LearnedClockOffset(clock, store, scope)
  val guard = CleartextGuard(peers, policy)
  val pool = ComputerHttp.pool(timeouts).also { pool -> (binder as? NetworkBinding)?.addListener { pool.evictAll() } }
  val http = ComputerHttp.create(
    guard = guard,
    signing = SigningInterceptor(PairingRequestSigner(store, clock, clockOffset), verified, clockOffset),
    socketFactory = ComputerSocketFactory(binder, guard),
    pool = pool,
    timeouts = timeouts,
    debugLog = debugLog,
    dns = dns,
  )
  val apis = ApiFactory(http)
  val client = HttpComputerClient(apis, peers, verified, clockOffset, store)
  val transfer = HttpArtifactTransfer(http, peers, Dispatchers.IO)
  val channel = WebSocketJobChannel(http, peers, binder, log)

  /**
   * Pairs through HTTP as `PairingRepository.pair()` does (architecture 6.3): `hello` and `pair` at the candidate, the
   * grant stored, the address made current, and a `hello` with a challenge that must verify.
   */
  suspend fun pair(at: HostPort = computer.address, deviceName: String = "Test phone"): PairGrantResult {
    val code = computer.newPairingCode()
    val grant = peers.withCandidate(at) {
      client.hello(at)
      client.pair(at, PairRequest(code, deviceName, 1, "1.0.0", null, null))
    }
    check(grant is ApiResult.Ok) { "pairing answered $grant" }
    val serverTime = grant.value.serverTime
    val deviceId = grant.value.deviceId
    store.save(grant.value, at, deviceName, clock.now(), serverTime - clock.now().epochSecond)
    peers.setCurrent(at)
    val proof = client.hello(at, deviceId)
    check(proof is ApiResult.Ok && proof.value.proofValid == true) { "the first proof answered $proof" }
    return PairGrantResult(deviceId.value)
  }

  /**
   * Trusts [at] without a computer behind it, for tests that serve crafted answers: a synthetic pairing in the store,
   * [at] made current and marked verified.
   */
  suspend fun trust(at: HostPort) {
    val grant = PairGrant(
      DeviceId("dev_test"), "pt1-test-token".toCharArray(), ServerId("srv_test"), "Test computer",
      LibraryId("lib_test"), 6, 1, clock.now().epochSecond,
    )
    store.save(grant, at, "Test phone", clock.now(), 0)
    peers.setCurrent(at)
    verified.markVerified(at, ServerId("srv_test"))
  }

  override fun close() {
    scope.cancel()
    http.base.dispatcher.executorService.shutdownNow()
    http.base.connectionPool.evictAll()
  }
}

internal data class PairGrantResult(val deviceId: String)
