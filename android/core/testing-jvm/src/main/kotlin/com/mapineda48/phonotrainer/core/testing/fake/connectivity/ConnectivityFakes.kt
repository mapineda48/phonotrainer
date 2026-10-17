package com.mapineda48.phonotrainer.core.testing.fake.connectivity

import com.mapineda48.phonotrainer.core.domain.api.ChannelLiveness
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow

// Settable fakes of the :core:connectivity interfaces (work-plan 4.6; WP-07 owns this folder after B1). The real
// implementations live in :core:connectivity; these stand in for them in other modules' unit and screen tests.

class FakeComputerStatusRepository(status: ComputerStatus = ComputerStatus.NotConnected) : ComputerStatusRepository {
  val statusState = MutableStateFlow(status)
  val pairedState = MutableStateFlow<PairedComputer?>(null)
  val restricted = MutableStateFlow(false)
  val diagnosticsState = MutableStateFlow(
    ConnectionDiagnostics(null, null, null, null, null, null, 0, 0, 0, false, emptyList()),
  )
  var checks = 0
    private set

  override val status: StateFlow<ComputerStatus> = statusState
  override val paired: StateFlow<PairedComputer?> = pairedState
  override val backgroundRestricted: StateFlow<Boolean> = restricted
  override val diagnostics: StateFlow<ConnectionDiagnostics> = diagnosticsState

  override suspend fun checkNow(): ComputerStatus {
    checks++
    return statusState.value
  }
}

/**
 * Settable [PairingRepository]: [searchStates] is what every search emits, in order; the results are functions of
 * their arguments; [paired], [checked], [newAddresses] and [forgets] record the calls.
 */
class FakePairingRepository : PairingRepository {
  var searchStates: List<SearchState> = listOf(SearchState.Searching, SearchState.NothingFound)
  var checkResult: (HostPort) -> CheckResult = { CheckResult.NoAnswer }

  /** The answer of [useNewAddress]; the same as [checkResult] unless set. */
  var useNewAddressResult: ((HostPort) -> CheckResult)? = null
  var pairResult: (PairingTarget, String) -> PairResult = { _, _ -> PairResult.CodeInvalid }
  var forgetResult: ForgetResult = ForgetResult.Forgotten
  val paired = mutableListOf<Triple<PairingTarget, String, String>>()
  val checked = mutableListOf<HostPort>()
  val newAddresses = mutableListOf<HostPort>()
  var searches = 0
    private set
  var forgets = 0
    private set

  override fun search(): Flow<SearchState> {
    searches++
    return searchStates.asFlow()
  }

  override suspend fun check(address: HostPort): CheckResult {
    checked += address
    return checkResult(address)
  }

  override suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult {
    paired += Triple(target, code, deviceName)
    return pairResult(target, code)
  }

  override suspend fun useNewAddress(address: HostPort): CheckResult {
    newAddresses += address
    return (useNewAddressResult ?: checkResult)(address)
  }

  override suspend fun forget(): ForgetResult {
    forgets++
    return forgetResult
  }
}

class FakeChannelLiveness : ChannelLiveness {
  var alive = 0
    private set
  var lost = 0
    private set

  override fun channelAlive() {
    alive++
  }

  override fun channelLost() {
    lost++
  }
}

/** A scripted network: set [state] to Wi-Fi, offline or mobile only. */
class FakeNetworkMonitor(initial: NetworkSnapshot = wifi()) : NetworkMonitor {
  val state = MutableStateFlow(initial)

  override val network: StateFlow<NetworkSnapshot> = state

  companion object {
    /** A validated Wi-Fi network with a documentation address (RFC 5737). */
    fun wifi(): NetworkSnapshot = NetworkSnapshot(NetworkKind.WIFI, 100L, "192.0.2.20", 24, vpnActive = false, validated = true)

    fun offline(): NetworkSnapshot = NetworkSnapshot(NetworkKind.NONE, null, null, null, vpnActive = false, validated = false)

    fun mobileOnly(): NetworkSnapshot =
      NetworkSnapshot(NetworkKind.MOBILE_ONLY, 200L, null, null, vpnActive = false, validated = true)

    /** Another Wi-Fi network (a new handle and address): a network change for the status machine. */
    fun otherWifi(): NetworkSnapshot =
      NetworkSnapshot(NetworkKind.WIFI, 101L, "198.51.100.20", 24, vpnActive = false, validated = false)
  }
}

/**
 * Discovery that answers with the fixed [answers], then with each responder's reply to the probe (a responder sees the
 * probe datagram, so it can echo its nonce, as `FakeComputer.answerProbe` does). [probes] and [calls] record each
 * search as it is asked for.
 */
class FakeDiscoveryTransport(var answers: List<Datagram> = emptyList()) : DiscoveryTransport {
  /** One search as asked for: where the probe goes, when, and how long replies are collected. */
  data class Call(val broadcastTargets: List<String>, val schedule: List<Duration>, val listenFor: Duration)

  private val responders = mutableListOf<(ByteArray) -> Datagram?>()
  val probes = mutableListOf<ByteArray>()
  val calls = mutableListOf<Call>()

  /** Adds a responder: its reply to a probe, or null to stay silent. */
  fun respond(responder: (ByteArray) -> Datagram?) {
    responders += responder
  }

  override fun probe(datagram: ByteArray, broadcastTargets: List<String>, schedule: List<Duration>, listenFor: Duration): Flow<Datagram> {
    probes += datagram
    calls += Call(broadcastTargets, schedule, listenFor)
    val replies = answers + responders.toList().mapNotNull { it(datagram) }
    return replies.asFlow()
  }
}
