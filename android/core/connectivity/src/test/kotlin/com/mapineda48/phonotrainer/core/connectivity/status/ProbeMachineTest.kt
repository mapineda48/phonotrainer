package com.mapineda48.phonotrainer.core.connectivity.status

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import java.time.Duration
import java.time.Instant
import org.junit.Test

/** The probe reducer of architecture 6.2 against every row of spec 7.5, the schedule and SC-10's bounds. */
class ProbeMachineTest {
  private val t0: Instant = Instant.parse("2026-10-10T09:00:00Z")

  private class Driver(var now: Instant) {
    var state = MachineState()
    val effects = mutableListOf<MachineEffect>()
    val statuses = mutableListOf<Pair<Instant, ComputerStatus>>()

    fun on(input: MachineInput, at: Instant = now): List<MachineEffect> {
      now = at
      val transition = ProbeMachine.reduce(state, input, now)
      state = transition.state
      effects += transition.effects
      statuses += now to state.status
      return transition.effects
    }

    fun after(duration: Duration, input: MachineInput): List<MachineEffect> = on(input, now.plus(duration))

    val status: ComputerStatus get() = state.status

    fun probe(): MachineEffect.Probe = effects.filterIsInstance<MachineEffect.Probe>().last()

    fun answer(report: ProbeReport, after: Duration = Duration.ZERO): List<MachineEffect> =
      after(after, MachineInput.ProbeDone(probe().id, report))

    fun announced(): List<ComputerStatus> = effects.filterIsInstance<MachineEffect.Announce>().map { it.status }
  }

  private val target = ProbeTarget(ServerId("srv_example1"), "Study desk", DeviceId("dev_example1"), HostPort("192.0.2.10", 8000))

  private fun health(analyzing: Int = 0, inLine: Int = 0) = ComputerHealth(
    serverId = target.serverId, name = "Study desk", libraryId = LibraryId("lib_example1"), apiVersion = 6,
    minAppVersion = 1, version = "0.1.0", serverTime = 1_791_622_800L, deviceId = target.deviceId,
    analyzing = analyzing, inLine = inLine, lowOnSpace = false,
    capabilities = Capabilities(true, "2026.8.19", true, false, false, false, false, 1L, 1L, 3_600L, 1L),
  )

  private val ok get() = ProbeReport.Success(health())
  private val noAnswer = ProbeReport.Failure(ProbeOutcome.NoAnswer(3))

  private fun connected(foreground: Boolean = true): Driver = Driver(t0).apply {
    on(MachineInput.Foreground(foreground))
    on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    on(MachineInput.Pairing(target, lastSeen = null))
    answer(ok)
    assertThat(status).isInstanceOf(ComputerStatus.Connected::class.java)
  }

  // --- the rows of spec 7.5 -----------------------------------------------------------------------------------------

  @Test
  fun aPhoneNeverPairedIsNotConnectedOnEveryNetworkAndNeverProbes() {
    val driver = Driver(t0)
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    driver.on(MachineInput.Pairing(null, null))
    assertThat(driver.status).isEqualTo(ComputerStatus.NotConnected)
    driver.on(MachineInput.Network(FakeNetworkMonitor.offline()))
    driver.on(MachineInput.Network(FakeNetworkMonitor.mobileOnly()))
    driver.on(MachineInput.CheckNow)

    assertThat(driver.status).isEqualTo(ComputerStatus.NotConnected)
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).isEmpty()
  }

  @Test
  fun withoutAnyNetworkThePairedPhoneIsOfflineAndNothingIsTried() {
    val driver = Driver(t0)
    driver.on(MachineInput.Network(FakeNetworkMonitor.offline()))
    driver.on(MachineInput.Pairing(target, null))
    driver.on(MachineInput.CheckNow)

    assertThat(driver.status).isEqualTo(ComputerStatus.Offline)
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).isEmpty()
    assertThat(driver.effects).contains(MachineEffect.Bind(null))
  }

  @Test
  fun onMobileDataOnlyThePhoneIsNotOnWifiAndNothingIsTried() {
    val driver = Driver(t0)
    driver.on(MachineInput.Network(FakeNetworkMonitor.mobileOnly()))
    driver.on(MachineInput.Pairing(target, null))

    assertThat(driver.status).isEqualTo(ComputerStatus.NotOnWifi)
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).isEmpty()
    // Mobile data is never bound to the computer client (NFR-05).
    assertThat(driver.effects).contains(MachineEffect.Bind(null))
  }

  @Test
  fun onTheHomeWifiTheStartLooksThenConnectsWithTheComputersCounts() {
    val driver = Driver(t0)
    driver.on(MachineInput.Foreground(true))
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)
    driver.on(MachineInput.Pairing(target, null))

    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)
    assertThat(driver.effects).contains(MachineEffect.Bind(100L))
    assertThat(driver.effects).contains(MachineEffect.SetPeer(target.address))
    assertThat(driver.probe().target).isEqualTo(target)

    driver.answer(ProbeReport.Success(health(analyzing = 1, inLine = 2)))
    assertThat(driver.status).isEqualTo(ComputerStatus.Connected("Study desk", 1, 2, false))
    assertThat(driver.announced()).containsExactly(ComputerStatus.Connected("Study desk", 1, 2, false))
    assertThat(driver.state.lastSeen).isEqualTo(t0)
  }

  @Test
  fun everyTwoMinutesInTheForegroundAndEveryHalfMinuteWhileSomethingIsPending() {
    val driver = connected()
    assertThat(driver.state.wakeAt).isEqualTo(t0.plusSeconds(120))

    driver.on(MachineInput.Pending(true))
    assertThat(driver.state.wakeAt).isEqualTo(t0.plusSeconds(30))
    driver.after(Duration.ofSeconds(30), MachineInput.Tick)
    assertThat(driver.probe().id).isEqualTo(2)
    driver.answer(ok)
    assertThat(driver.state.wakeAt).isEqualTo(t0.plusSeconds(60))

    // Nothing pending any more: the probe already due runs, the next one is 2 min after it.
    driver.on(MachineInput.Pending(false))
    assertThat(driver.state.wakeAt).isEqualTo(t0.plusSeconds(60))
    driver.after(Duration.ofSeconds(30), MachineInput.Tick)
    assertThat(driver.probe().id).isEqualTo(3)
    driver.answer(ok)
    assertThat(driver.state.wakeAt).isEqualTo(t0.plusSeconds(60 + 120))
  }

  @Test
  fun inTheBackgroundNothingIsProbedOnScheduleAndTheResumeProbesAtOnce() {
    val driver = connected()
    driver.on(MachineInput.Foreground(false))
    assertThat(driver.state.wakeAt).isNull()

    driver.after(Duration.ofMinutes(10), MachineInput.Foreground(true))
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).hasSize(2)
  }

  @Test
  fun aFailedProbeWhileConnectedChecksAtOnceAndASecondFailureFiveSecondsLaterIsNotReachable() {
    val driver = connected()
    driver.after(Duration.ofMinutes(2), MachineInput.Tick)
    driver.answer(noAnswer, after = Duration.ofSeconds(3))

    assertThat(driver.status).isEqualTo(ComputerStatus.Checking)
    assertThat(driver.state.wakeAt).isEqualTo(driver.now.plusSeconds(5))

    driver.after(Duration.ofSeconds(5), MachineInput.Tick)
    driver.answer(noAnswer, after = Duration.ofSeconds(3))
    assertThat(driver.status).isEqualTo(
      ComputerStatus.NotReachable("Study desk", lastSeen = t0, addressMayHaveChanged = false, lastProbe = ProbeOutcome.NoAnswer(3)),
    )
    assertThat(driver.announced().map { it::class }).containsExactly(
      ComputerStatus.Connected::class,
      ComputerStatus.NotReachable::class,
    ).inOrder()
  }

  @Test
  fun aReProbeThatSucceedsRestoresConnectedWithoutAnyAnnouncement() {
    val driver = connected()
    driver.on(MachineInput.CheckNow)
    driver.answer(noAnswer)
    driver.after(Duration.ofSeconds(5), MachineInput.Tick)
    driver.answer(ok)

    assertThat(driver.status).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(driver.announced()).hasSize(1)
  }

  @Test
  fun theProbeWaitsTenSecondsWhileTheComputerWasLastKnownToBeAnalyzing() {
    val driver = connected()
    assertThat(driver.state.probeTimeout).isEqualTo(Duration.ofSeconds(3))
    driver.on(MachineInput.CheckNow)
    assertThat(driver.probe().slow).isFalse()
    driver.answer(ProbeReport.Success(health(analyzing = 1)))

    driver.on(MachineInput.CheckNow)
    assertThat(driver.probe().slow).isTrue()
    assertThat(driver.state.probeTimeout).isEqualTo(Duration.ofSeconds(10))
    driver.answer(ProbeReport.Success(health(analyzing = 0)))

    driver.on(MachineInput.CheckNow)
    assertThat(driver.probe().slow).isFalse()
  }

  @Test
  fun aNetworkChangeLooksForAtMost10sThenOffersTheNewAddress() {
    val driver = connected()
    driver.after(Duration.ofMinutes(1), MachineInput.Network(FakeNetworkMonitor.otherWifi()))
    val changedAt = driver.now

    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)
    assertThat(driver.effects).contains(MachineEffect.Bind(101L))
    driver.answer(noAnswer, after = Duration.ofSeconds(3))
    // The stored address failed: a re-discovery looks for the paired computer, still "Looking".
    assertThat(driver.effects.last()).isInstanceOf(MachineEffect.Rediscover::class.java)
    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)

    driver.on(MachineInput.Tick, changedAt.plusSeconds(10))
    val status = driver.status as ComputerStatus.NotReachable
    assertThat(status.addressMayHaveChanged).isTrue()
    assertThat(status.lastSeen).isEqualTo(t0)
    assertThat(driver.statuses.filter { it.second == ComputerStatus.Looking }.maxOf { it.first })
      .isLessThan(changedAt.plusSeconds(10))
  }

  @Test
  fun aRediscoveredAddressThatProvesItselfIsStoredAndConnects() {
    val driver = connected()
    driver.on(MachineInput.Network(FakeNetworkMonitor.otherWifi()))
    driver.answer(noAnswer)
    val rediscover = driver.effects.filterIsInstance<MachineEffect.Rediscover>().single()
    val moved = HostPort("192.0.2.11", 8000)

    driver.on(MachineInput.RediscoveryDone(rediscover.id, RediscoveryResult.Found(moved)))
    assertThat(driver.effects).contains(MachineEffect.AddressChanged(moved))
    assertThat(driver.probe().target.address).isEqualTo(moved)
    driver.answer(ok)

    assertThat(driver.status).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(driver.state.target?.address).isEqualTo(moved)
  }

  @Test
  fun aMissingOrWrongProofIsADifferentComputerAndOnlyItsAnswerIsRecorded() {
    val driver = connected(foreground = false)
    driver.on(MachineInput.CheckNow)
    val probes = driver.effects.filterIsInstance<MachineEffect.Probe>().size
    driver.answer(ProbeReport.DifferentComputer)

    assertThat(driver.status).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))
    assertThat(driver.effects).contains(MachineEffect.RecordProbe(ProbeOutcome.ProofFailed))
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).hasSize(probes)
    // No discovery runs in the background (NFR-04), and "a different computer" is never announced as a transition.
    assertThat(driver.effects.filterIsInstance<MachineEffect.Rediscover>()).isEmpty()
    assertThat(driver.announced()).hasSize(1)
  }

  @Test
  fun inTheForegroundADifferentComputerStartsARediscoveryThatMayFindTheRealOne() {
    val driver = connected()
    driver.on(MachineInput.CheckNow)
    driver.answer(ProbeReport.DifferentComputer)
    val rediscover = driver.effects.filterIsInstance<MachineEffect.Rediscover>().single()
    assertThat(driver.status).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))

    driver.on(MachineInput.RediscoveryDone(rediscover.id, RediscoveryResult.Impostor))
    assertThat(driver.status).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))
  }

  @Test
  fun lookingWithOnlyAnImpostorAnsweringEndsAsADifferentComputer() {
    val driver = Driver(t0)
    driver.on(MachineInput.Foreground(true))
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    driver.on(MachineInput.Pairing(target, null))
    driver.answer(ProbeReport.DifferentComputer)
    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)
    val rediscover = driver.effects.filterIsInstance<MachineEffect.Rediscover>().single()

    driver.on(MachineInput.RediscoveryDone(rediscover.id, RediscoveryResult.NothingFound))
    assertThat(driver.status).isEqualTo(ComputerStatus.DifferentComputer("Study desk"))
  }

  @Test
  fun aRevokedPhoneIsConnectAgainUntilPairedAgainWhateverTheNetwork() {
    val driver = connected()
    driver.on(MachineInput.CheckNow)
    driver.answer(ProbeReport.ConnectAgain)
    val probes = driver.effects.filterIsInstance<MachineEffect.Probe>().size

    assertThat(driver.status).isEqualTo(ComputerStatus.ConnectAgain)
    assertThat(driver.state.wakeAt).isNull()
    driver.on(MachineInput.Network(FakeNetworkMonitor.offline()))
    assertThat(driver.status).isEqualTo(ComputerStatus.ConnectAgain)
    driver.on(MachineInput.Network(FakeNetworkMonitor.otherWifi()))
    driver.on(MachineInput.Foreground(false))
    driver.on(MachineInput.Foreground(true))
    assertThat(driver.status).isEqualTo(ComputerStatus.ConnectAgain)
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).hasSize(probes)

    // "Check now" still asks; pairing again (a new device id) starts over.
    driver.on(MachineInput.CheckNow)
    assertThat(driver.effects.filterIsInstance<MachineEffect.Probe>()).hasSize(probes + 1)
    driver.on(MachineInput.Adopted(target.copy(deviceId = DeviceId("dev_example2")), health()))
    assertThat(driver.status).isInstanceOf(ComputerStatus.Connected::class.java)
  }

  @Test
  fun phoneAccessOffOrNotApprovedIsNotReachableWithItsOwnLastProbe() {
    val looking = Driver(t0)
    looking.on(MachineInput.Foreground(true))
    looking.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    looking.on(MachineInput.Pairing(target, null))
    looking.answer(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))

    assertThat(looking.status).isEqualTo(
      ComputerStatus.NotReachable("Study desk", null, addressMayHaveChanged = false, lastProbe = ProbeOutcome.PhoneAccessOff),
    )
    // The computer answered at its address: no re-discovery.
    assertThat(looking.effects.filterIsInstance<MachineEffect.Rediscover>()).isEmpty()

    val connected = connected()
    connected.on(MachineInput.CheckNow)
    connected.answer(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))
    assertThat(connected.status).isEqualTo(ComputerStatus.Checking)
    connected.after(Duration.ofSeconds(5), MachineInput.Tick)
    connected.answer(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))
    assertThat((connected.status as ComputerStatus.NotReachable).lastProbe).isEqualTo(ProbeOutcome.PhoneAccessOff)
  }

  @Test
  fun aVpnThatBlocksTheBindingIsNotReachableWithVpnBlocks() {
    val driver = Driver(t0)
    driver.on(MachineInput.Foreground(true))
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi().copy(vpnActive = true)))
    driver.on(MachineInput.Pairing(target, null))
    driver.answer(ProbeReport.Failure(ProbeOutcome.VpnBlocks))

    assertThat(driver.status).isEqualTo(ComputerStatus.NotReachable("Study desk", null, false, ProbeOutcome.VpnBlocks))
    assertThat(driver.effects.filterIsInstance<MachineEffect.Rediscover>()).isEmpty()
  }

  @Test
  fun versionsTheAppCannotUseSettleAsComputerTooOldOrAppTooOld() {
    val old = connected()
    old.on(MachineInput.CheckNow)
    old.answer(ProbeReport.ComputerTooOld)
    assertThat(old.status).isEqualTo(ComputerStatus.ComputerTooOld)
    assertThat(old.state.wakeAt).isNotNull()

    val newer = connected()
    newer.on(MachineInput.CheckNow)
    newer.answer(ProbeReport.AppTooOld)
    assertThat(newer.status).isEqualTo(ComputerStatus.AppTooOld)
  }

  @Test
  fun aLostChannelChecksAtOnceAndReprobesIn5s() {
    val driver = connected()
    driver.after(Duration.ofSeconds(10), MachineInput.ChannelLost)

    assertThat(driver.status).isEqualTo(ComputerStatus.Checking)
    assertThat(driver.state.wakeAt).isEqualTo(driver.now.plusSeconds(5))
    driver.after(Duration.ofSeconds(5), MachineInput.Tick)
    assertThat(driver.state.probe).isNotNull()
  }

  @Test
  fun aNetworkChangeMakesTheProbeInFlightStale() {
    val driver = connected()
    driver.on(MachineInput.CheckNow)
    val stale = driver.probe().id
    driver.on(MachineInput.Network(FakeNetworkMonitor.otherWifi()))
    assertThat(driver.effects).contains(MachineEffect.CancelProbe)

    driver.on(MachineInput.ProbeDone(stale, ok))
    assertThat(driver.status).isEqualTo(ComputerStatus.Looking)
    assertThat(driver.probe().id).isGreaterThan(stale)
  }

  @Test
  fun aRefusalWithRetryAfterIsNotProbedAgainBeforeIt() {
    val driver = connected()
    driver.on(MachineInput.CheckNow)
    driver.answer(ProbeReport.Failure(ProbeOutcome.Refused, retryAfterS = 40))

    assertThat(driver.status).isEqualTo(ComputerStatus.Checking)
    assertThat(driver.state.wakeAt).isEqualTo(driver.now.plusSeconds(40))
  }

  @Test
  fun forgettingIsNotConnectedAndClearsTheCurrentAddress() {
    val driver = connected()
    driver.on(MachineInput.Pairing(null, null))

    assertThat(driver.status).isEqualTo(ComputerStatus.NotConnected)
    assertThat(driver.effects).contains(MachineEffect.SetPeer(null))
    assertThat(driver.state.wakeAt).isNull()
  }

  @Test
  fun announcementsAreOnlyConnectedOfflineAndNotReachableAndNeverRepeated() {
    val driver = connected()
    driver.on(MachineInput.ChannelLost)
    driver.after(Duration.ofSeconds(5), MachineInput.Tick)
    driver.answer(ok)
    driver.on(MachineInput.Network(FakeNetworkMonitor.offline()))
    driver.on(MachineInput.Network(FakeNetworkMonitor.mobileOnly()))
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    driver.answer(noAnswer)
    driver.after(Duration.ofSeconds(10), MachineInput.Tick)
    driver.on(MachineInput.CheckNow)
    driver.answer(ProbeReport.DifferentComputer)
    driver.on(MachineInput.CheckNow)
    driver.answer(ok)

    assertThat(driver.announced().map { it::class }).containsExactly(
      ComputerStatus.Connected::class,
      ComputerStatus.Offline::class,
      ComputerStatus.NotReachable::class,
      ComputerStatus.Connected::class,
    ).inOrder()
  }

  // --- SC-10: wrong claims are bounded --------------------------------------------------------------------------------

  /**
   * A small discrete-event run: the computer answers every probe that starts before [downAt] (or after [upAgainAt]) in
   * 100 ms, and no other (that probe fails after its 3 s timeout). Returns the driver with every status over time.
   */
  private fun simulate(pending: Boolean, downAt: Duration, upAgainAt: Duration? = null, until: Duration): Driver {
    val driver = Driver(t0)
    driver.on(MachineInput.Foreground(true))
    driver.on(MachineInput.Pending(pending))
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi()))
    driver.on(MachineInput.Pairing(target, null))
    fun answers(at: Instant): Boolean {
      val elapsed = Duration.between(t0, at)
      return elapsed < downAt || (upAgainAt != null && elapsed >= upAgainAt)
    }
    val end = t0.plus(until)
    var inFlight: Triple<Long, Instant, Boolean>? = null
    while (driver.now.isBefore(end)) {
      val probe = driver.state.probe
      if (probe != null && inFlight?.first != probe) {
        val up = answers(driver.now)
        inFlight = Triple(probe, driver.now.plus(if (up) Duration.ofMillis(100) else Duration.ofSeconds(3)), up)
      }
      val answer = inFlight?.takeIf { it.first == driver.state.probe }
      val wake = driver.state.wakeAt
      val next = listOfNotNull(answer?.second, wake).minOrNull() ?: break
      if (answer != null && answer.second == next) {
        driver.on(MachineInput.ProbeDone(answer.first, if (answer.third) ok else noAnswer), next)
      } else {
        driver.on(MachineInput.Tick, next)
      }
    }
    return driver
  }

  @Test
  fun connectedIsShownAtMostOneIntervalAndOneTimeoutAfterTheComputerStoppedAnswering() {
    listOf(false to Duration.ofMinutes(2), true to Duration.ofSeconds(30)).forEach { (pending, interval) ->
      val downAt = Duration.ofMinutes(7).plusSeconds(13)
      val driver = simulate(pending, downAt, until = Duration.ofMinutes(15))
      val lastConnected = driver.statuses.last { it.second is ComputerStatus.Connected }.first
      val firstChecking = driver.statuses.first { it.second == ComputerStatus.Checking }.first
      val firstNotReachable = driver.statuses.first { it.second is ComputerStatus.NotReachable }.first

      assertThat(lastConnected).isAtMost(t0.plus(downAt).plus(interval).plusSeconds(3))
      assertThat(firstChecking).isAtMost(t0.plus(downAt).plus(interval).plusSeconds(3))
      // "Checking" lasts the 5 s re-probe and its timeout, and claims nothing; then "isn't reachable".
      assertThat(Duration.between(firstChecking, firstNotReachable)).isAtMost(Duration.ofSeconds(5 + 3))
    }
  }

  @Test
  fun notReachableIsNeverShownWhileTheComputerAnswersOutsideTheReProbe() {
    val driver = simulate(pending = false, downAt = Duration.ofMinutes(3), upAgainAt = Duration.ofMinutes(6), until = Duration.ofMinutes(12))
    val upAgain = t0.plus(Duration.ofMinutes(6))
    val notReachableWhileUp = driver.statuses.filter { (at, status) -> at.isAfter(upAgain) && status is ComputerStatus.NotReachable }

    // After the computer answers again, "isn't reachable" lasts at most until the next scheduled probe.
    notReachableWhileUp.forEach { (at, _) -> assertThat(at).isAtMost(upAgain.plus(Duration.ofMinutes(2)).plusSeconds(1)) }
    assertThat(driver.status).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(driver.statuses.none { (at, status) -> at.isBefore(t0.plus(Duration.ofMinutes(3))) && status is ComputerStatus.NotReachable })
      .isTrue()
  }

  @Test
  fun aTickBeforeItsTimeBecauseTheClockWasSetBackReArmsTheTimer() {
    val driver = connected()
    val effects = driver.on(MachineInput.Tick, t0.plusSeconds(10))

    assertThat(effects).containsExactly(MachineEffect.Schedule(t0.plusSeconds(120)))
    assertThat(driver.state.probe).isNull()
  }

  @Test
  fun aNetworkSnapshotWithTheSameHandleIsNoChange() {
    val driver = connected()
    val before = driver.effects.size
    driver.on(MachineInput.Network(FakeNetworkMonitor.wifi().copy(validated = false)))

    assertThat(driver.effects).hasSize(before)
    assertThat(driver.status).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(NetworkSnapshot(NetworkKind.ETHERNET, 7L, null, null, false, true).link()).isEqualTo(Link.HOME)
  }
}
