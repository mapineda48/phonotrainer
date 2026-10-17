package com.mapineda48.phonotrainer.core.connectivity.status

import android.app.usage.UsageStatsManager
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.connectivity.support.Harness
import com.mapineda48.phonotrainer.core.connectivity.support.RelayingClient
import com.mapineda48.phonotrainer.core.connectivity.support.relayDatagram
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.ProofMode
import com.mapineda48.phonotrainer.core.testing.computer.Reachability
import com.mapineda48.phonotrainer.core.testing.computer.RecordedCall
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * The status repository end to end against the fake computer (in memory, through `FakeComputerClient`, which checks
 * proofs and signs as the real client does), on virtual time: work-plan WP-07 Proof (status side of F-02-AC5,
 * F-02-AC8, F-02-AC11, F-02-AC12, F-21-AC5, QT-8, QT-11, SC-10, F-22-AC4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComputerStatusRepositoryTest {
  private val computers = mutableListOf<FakeComputer>()

  @After
  fun closeComputers() = computers.forEach { it.close() }

  private fun computer(name: String = "Study desk", seed: Long = 7): FakeComputer =
    FakeComputer(name = name, seed = seed).also { it.rateLimits = false; computers += it }

  private fun TestScope.harness(foreground: Boolean = true, client: ((ComputerClient) -> ComputerClient)? = null) =
    Harness(this, listOf(computer()), foreground, client)

  private fun RecordedCall.route(): String = "$method ${target.substringBefore('?')}"

  private fun RecordedCall.signed(): Boolean = headers.keys.any { it.equals("Authorization", ignoreCase = true) }

  private val connected = ComputerStatus.Connected("Study desk", 0, 0, false)

  @Test
  fun aPairedComputerIsConnectedAfterItsProofAndItsSignedHealthAndNothingElse() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(connected)
    assertThat(h.computer.requests.map { it.route() }).containsExactly("GET /api/phone/hello", "GET /api/phone/health").inOrder()
    val (hello, health) = h.computer.requests
    assertThat(hello.target).contains("challenge=")
    assertThat(hello.signed()).isFalse()
    assertThat(health.signed()).isTrue()
    assertThat(h.announcer.announced).containsExactly("Connected to Study desk")
    assertThat(h.binder.handles).containsExactly(100L)
    assertThat(h.peers.current.value).isEqualTo(h.computer.address)
    assertThat(h.store.value?.lastProbe).isEqualTo(ProbeOutcome.Ok)
    assertThat(h.store.value?.lastSeen).isNotNull()
    assertThat(h.status.paired.value?.serverId).isEqualTo(h.computer.serverId)
  }

  @Test
  fun aNeverPairedPhoneIsNotConnectedAndSendsNothing() = runTest {
    val h = harness()
    h.status
    runCurrent()
    advanceTimeBy(600_000)

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotConnected)
    assertThat(h.computer.requests).isEmpty()
    assertThat(h.status.checkNow()).isEqualTo(ComputerStatus.NotConnected)
  }

  @Test
  fun anAddressThatKnowsTheServerIdButNotThisPhonesKeyIsADifferentComputerAndGetsNothingElse() = runTest {
    ProofMode.entries.forEach { mode ->
      val h = harness()
      val impostor = h.computer.impersonate(mode)
      h.answerDiscovery(h.computer, sourceHost = FakeComputer.HOST)
      h.discovery.respond { probe -> impostor.answerProbe(probe)?.let { com.mapineda48.phonotrainer.core.domain.model.Datagram(it, FakeComputer.HOST, 45317) } }
      h.computer.phoneAccessOff() // the real computer is off its network: only the impostor answers
      h.seedPairing(address = impostor.address)
      h.status
      runCurrent()
      advanceTimeBy(11_000)

      assertThat(h.status.status.value).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))
      assertThat(h.status.diagnostics.value.lastProbe).isEqualTo(ProbeOutcome.ProofFailed)
      // Only the identity question reached it: no signed call, no request, no file (F-02-AC11).
      assertThat(impostor.requests.map { it.route() }.distinct()).containsExactly("GET /api/phone/hello")
      assertThat(impostor.requests.none { it.signed() }).isTrue()
      // Nothing deleted: the pairing is intact, and the address stays the one the learner had.
      assertThat(h.store.value?.address).isEqualTo(impostor.address)
      assertThat(h.store.clears).isEqualTo(0)
      impostor.close()
    }
  }

  @Test
  fun aRevokedPhoneIsConnectAgainAndStopsCallingTheComputer() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    h.computer.revokeDevice()

    assertThat(h.status.checkNow()).isEqualTo(ComputerStatus.ConnectAgain)
    val calls = h.computer.requests.size
    advanceTimeBy(30 * 60_000)
    h.monitor.state.value = FakeNetworkMonitor.otherWifi()
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.ConnectAgain)
    assertThat(h.computer.requests).hasSize(calls)
    assertThat(h.store.value).isNotNull()
  }

  @Test
  fun aCredentialTheComputerRejectsOnHealthIsConnectAgain() = runTest {
    val h = harness(client = { real ->
      object : ComputerClient by real {
        override suspend fun health(): ApiResult<ComputerHealth> =
          ApiResult.Refused(401, "credential_rejected", "This phone is not paired with this computer.")
      }
    })
    h.seedPairing()
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.ConnectAgain)
  }

  @Test
  fun aStoredKeyThatCannotBeReadIsConnectAgainNeverADifferentComputer() = runTest {
    val h = harness()
    h.seedPairing()
    h.store.unreadable = true
    h.fakeClient.credentials = null // the phone cannot compute the proof any more
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.ConnectAgain)
  }

  @Test
  fun phoneAccessNotApprovedForThisNetworkIsNotReachableWithItsOwnLastProbe() = runTest {
    listOf<(FakeComputer) -> Unit>({ it.lanForbidden() }, { it.phoneAccessOff() }).forEach { knob ->
      val h = harness()
      knob(h.computer)
      h.seedPairing()
      h.status
      runCurrent()

      val status = h.status.status.value as ComputerStatus.NotReachable
      assertThat(status.lastProbe).isEqualTo(ProbeOutcome.PhoneAccessOff)
      assertThat(status.addressMayHaveChanged).isFalse()
      assertThat(h.status.diagnostics.value.lastProbe).isEqualTo(ProbeOutcome.PhoneAccessOff)
      assertThat(h.announcer.announced).containsExactly("Your computer isn't reachable")
    }
  }

  @Test
  fun anOlderComputerIsComputerTooOldThroughItsLegacyHealth() = runTest {
    val h = harness()
    h.seedPairing()
    h.computer.apiVersion = 5
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.ComputerTooOld)
    assertThat(h.computer.requests.map { it.route() }).contains("GET /api/health")
    assertThat(h.guarded.refused).isEmpty()
  }

  @Test
  fun aComputerThatNeedsANewerAppIsAppTooOld() = runTest {
    val h = harness()
    h.seedPairing()
    h.computer.minAppVersion = 2
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.AppTooOld)
  }

  @Test
  fun aVpnThatBlocksTheBindingIsNotReachableWithVpnBlocks() = runTest {
    val h = harness(client = { real ->
      object : ComputerClient by real {
        override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> =
          ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.VpnBlocks))
      }
    })
    h.monitor.state.value = FakeNetworkMonitor.wifi().copy(vpnActive = true)
    h.seedPairing()
    h.status
    runCurrent()

    assertThat((h.status.status.value as ComputerStatus.NotReachable).lastProbe).isEqualTo(ProbeOutcome.VpnBlocks)
  }

  @Test
  fun whenTheComputerStopsAnsweringTheStatusChecksAtOnceAndIsNotReachableFiveSecondsLater() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    h.computer.reachability = Reachability.Unreachable

    advanceTimeBy(120_000)
    runCurrent()
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.Checking)
    advanceTimeBy(5_000)
    runCurrent()
    val status = h.status.status.value as ComputerStatus.NotReachable
    assertThat(status.lastProbe).isEqualTo(ProbeOutcome.NoAnswer(3))
    assertThat(h.announcer.announced).containsExactly("Connected to Study desk", "Your computer isn't reachable").inOrder()

    h.computer.reachability = Reachability.Reachable
    assertThat(h.status.checkNow()).isEqualTo(connected)
  }

  @Test
  fun aClockSkewIsLearnedAndTheProbeRepeatedOnce() = runTest {
    val h = harness()
    h.computer.clockSkew(3_600)
    h.seedPairing()
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(connected)
  }

  @Test
  fun theProbeScheduleIsHalfAMinuteWhileRequestsWaitAndTwoMinutesOtherwise() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    fun healthCalls() = h.computer.requests.count { it.route() == "GET /api/phone/health" }

    advanceTimeBy(31_000)
    assertThat(healthCalls()).isEqualTo(1)
    h.requests.countsState.value = RequestCounts(waiting = 1, onComputer = 0, needsAttention = 0, readyNew = 0)
    runCurrent()
    advanceTimeBy(1_000)
    assertThat(healthCalls()).isEqualTo(2)
    advanceTimeBy(30_000)
    assertThat(healthCalls()).isEqualTo(3)
    assertThat(h.status.diagnostics.value.waitingRequests).isEqualTo(1)
  }

  @Test
  fun aStoredAddressThatNoLongerAnswersIsReplacedByTheRediscoveredOneThatProvesItself() = runTest {
    val h = harness()
    val impostor = h.computer.impersonate(ProofMode.WRONG)
    h.discovery.respond { probe -> impostor.answerProbe(probe)?.let { com.mapineda48.phonotrainer.core.domain.model.Datagram(it, FakeComputer.HOST, 45317) } }
    h.answerDiscovery()
    h.seedPairing(address = HostPort(FakeComputer.HOST, 9))
    h.status
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(connected)
    assertThat(h.store.value?.address).isEqualTo(h.computer.address)
    assertThat(h.status.diagnostics.value.addressChangedAt).isNotNull()
    assertThat(impostor.requests.none { it.signed() }).isTrue()
    impostor.close()
  }

  @Test
  fun aRelayThatPassesTheChallengeOnIsNeverStoredAsTheNewAddress() = runTest {
    // A host on the Wi-Fi answers discovery with the paired server id and hands every hello to the real computer, so
    // its proof verifies; the real computer answers discovery too, so two addresses prove and neither is accepted.
    val computer = computer()
    val relay = HostPort(FakeComputer.HOST, 10)
    val h = Harness(this, listOf(computer), clientOverride = { RelayingClient(it, relay, computer) })
    h.discovery.respond { probe -> relayDatagram(probe, computer, relay) }
    h.answerDiscovery()
    val stored = HostPort(FakeComputer.HOST, 9)
    h.seedPairing(address = stored)
    h.status
    runCurrent()
    advanceTimeBy(11_000)

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))
    assertThat(h.store.value?.address).isEqualTo(stored)
    assertThat(h.status.diagnostics.value.addressChangedAt).isNull()
    assertThat(h.peers.current.value).isEqualTo(stored)
    assertThat(h.computer.requests.none { it.signed() }).isTrue()
    assertThat(h.guarded.refused).isEmpty()
  }

  @Test
  fun aNetworkChangeRebindsLooksAgainAndReconnectsWithoutAskingAnything() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    h.monitor.state.value = FakeNetworkMonitor.otherWifi()
    runCurrent()

    assertThat(h.binder.handles).containsExactly(100L, 101L).inOrder()
    assertThat(h.status.status.value).isEqualTo(connected)
    assertThat(h.computer.requests.count { it.route() == "GET /api/phone/hello" }).isEqualTo(2)
  }

  @Test
  fun offTheWifiNothingIsBoundOrProbedAndThisPhonesAddressIsGone() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    assertThat(h.status.diagnostics.value.phoneAddress).isEqualTo("192.0.2.20")
    val calls = h.computer.requests.size

    h.monitor.state.value = FakeNetworkMonitor.mobileOnly()
    runCurrent()
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotOnWifi)
    assertThat(h.status.diagnostics.value.phoneAddress).isNull()
    h.monitor.state.value = FakeNetworkMonitor.offline()
    runCurrent()
    advanceTimeBy(600_000)

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.Offline)
    assertThat(h.binder.handles).containsExactly(100L, null).inOrder()
    assertThat(h.computer.requests).hasSize(calls)
    assertThat(h.announcer.announced.last()).isEqualTo("Offline")

    h.monitor.state.value = FakeNetworkMonitor.otherWifi()
    runCurrent()
    assertThat(h.status.diagnostics.value.phoneAddress).isEqualTo("198.51.100.20")
  }

  @Test
  fun diagnosticsCarryTheShortIdTheApiVersionAndTheBackgroundLimit() = runTest {
    val h = harness()
    h.seedPairing()
    h.backgroundSource.bucket = UsageStatsManager.STANDBY_BUCKET_RARE
    h.status
    h.appForeground.state.value = false
    runCurrent()
    h.appForeground.state.value = true
    runCurrent()

    val diagnostics = h.status.diagnostics.value
    assertThat(diagnostics.shortServerId).isEqualTo(h.computer.serverId.value.take(12))
    assertThat(diagnostics.apiVersion).isEqualTo(6)
    assertThat(diagnostics.address).isEqualTo(h.computer.address)
    assertThat(diagnostics.backgroundRestricted).isTrue()
    assertThat(h.status.backgroundRestricted.value).isTrue()
  }

  @Test
  fun aLostChannelChecksAtOnceAndTheReProbeRestoresConnected() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    MachineChannelLiveness(h.status).channelLost()
    runCurrent()

    assertThat(h.status.status.value).isEqualTo(ComputerStatus.Checking)
    advanceTimeBy(5_001)
    assertThat(h.status.status.value).isEqualTo(connected)
    assertThat(h.announcer.announced).containsExactly("Connected to Study desk")
  }
}
