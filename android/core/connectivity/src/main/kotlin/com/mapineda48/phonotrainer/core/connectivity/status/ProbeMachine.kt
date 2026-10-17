package com.mapineda48.phonotrainer.core.connectivity.status

import com.mapineda48.phonotrainer.core.connectivity.ConnectivityRules
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import java.time.Duration
import java.time.Instant

/** The phone's network as the machine needs it (spec 7.5 rows Offline and Not on Wi-Fi). */
internal enum class Link { NONE, MOBILE_ONLY, HOME }

/** The statuses TalkBack announces (spec 7.5, A-04): never `Checking`, never a percent. */
internal enum class Announced { CONNECTED, OFFLINE, NOT_REACHABLE }

internal fun ComputerStatus.announced(): Announced? = when (this) {
  is ComputerStatus.Connected -> Announced.CONNECTED
  ComputerStatus.Offline -> Announced.OFFLINE
  is ComputerStatus.NotReachable -> Announced.NOT_REACHABLE
  else -> null
}

internal fun NetworkSnapshot.link(): Link = when (kind) {
  NetworkKind.WIFI, NetworkKind.ETHERNET -> Link.HOME
  NetworkKind.MOBILE_ONLY -> Link.MOBILE_ONLY
  NetworkKind.NONE -> Link.NONE
}

/** How a re-discovery for the paired computer ended (architecture 6.1, 6.2 rule 2). */
internal sealed interface RediscoveryResult {
  /** An address whose `hello` proof verified for the paired server id. */
  data class Found(val address: HostPort) : RediscoveryResult

  /** The paired computer answered with a valid proof, and says this phone is revoked. */
  data object Revoked : RediscoveryResult

  /** Something answered with the paired server id, and none of those answers proved it. */
  data object Impostor : RediscoveryResult

  data object NothingFound : RediscoveryResult
}

/** Everything that drives the status (architecture 6.2): the network, the pairing, probes, the channel, the clock. */
internal sealed interface MachineInput {
  data class Network(val snapshot: NetworkSnapshot) : MachineInput

  /** The pairing store's record (null: not paired), with the last time the computer was seen. */
  data class Pairing(val target: ProbeTarget?, val lastSeen: Instant?) : MachineInput

  /** Pairing or a new address just verified the computer and its `health` (architecture 6.3): `Connected` now. */
  data class Adopted(val target: ProbeTarget, val health: ComputerHealth) : MachineInput

  data class Foreground(val foreground: Boolean) : MachineInput

  /** Waiting requests, requests on the computer or wanted downloads exist (the 30 s interval, spec 7.5). */
  data class Pending(val pending: Boolean) : MachineInput

  /** "Check now", or a sync pass before it sends (spec 7.5 schedule). */
  data object CheckNow : MachineInput

  data class ProbeDone(val id: Long, val report: ProbeReport) : MachineInput

  data class RediscoveryDone(val id: Long, val result: RediscoveryResult) : MachineInput

  /** The job channel missed its pongs (architecture 5.5). */
  data object ChannelLost : MachineInput

  data object ChannelAlive : MachineInput

  /** The time of the next scheduled step has come. */
  data object Tick : MachineInput
}

/** What the effect runner does after a step, in order. */
internal sealed interface MachineEffect {
  data class Probe(val id: Long, val target: ProbeTarget, val slow: Boolean) : MachineEffect

  data object CancelProbe : MachineEffect

  data class Rediscover(val id: Long, val target: ProbeTarget) : MachineEffect

  data object CancelRediscovery : MachineEffect

  /** Wake the machine with [MachineInput.Tick] at [at]; null: nothing is scheduled. */
  data class Schedule(val at: Instant?) : MachineEffect

  /** A polite announcement of a transition to `Connected`, `Offline` or `NotReachable` (spec 7.5, A-04). */
  data class Announce(val status: ComputerStatus) : MachineEffect

  /** Bind the computer client to this Wi-Fi or Ethernet network (null: none) and forget verified addresses (5.1). */
  data class Bind(val networkHandle: Long?) : MachineEffect

  /** The guard's current address (`AllowedPeers.current`, architecture 5.2). */
  data class SetPeer(val address: HostPort?) : MachineEffect

  /** A re-discovered address proved it is the paired computer: store it and make it current (spec 7.3 item 5). */
  data class AddressChanged(val address: HostPort) : MachineEffect

  data class RecordProbe(val outcome: ProbeOutcome) : MachineEffect

  data class RecordSeen(val health: ComputerHealth) : MachineEffect
}

/** The machine's whole state; [status] is what `ComputerStatusRepository.status` shows. */
internal data class MachineState(
  val status: ComputerStatus = ComputerStatus.Looking,
  val target: ProbeTarget? = null,
  val pairingKnown: Boolean = false,
  val link: Link? = null,
  val networkHandle: Long? = null,
  val foreground: Boolean = false,
  val pending: Boolean = false,
  /** The id of the probe in flight, if any; answers of other ids are stale and ignored. */
  val probe: Long? = null,
  val rediscovery: Long? = null,
  val nextId: Long = 1,
  val probeAt: Instant? = null,
  /** While `Looking`, when it gives way to `NotReachable` (≤ 10 s, spec 7.5). */
  val lookingUntil: Instant? = null,
  val lastSeen: Instant? = null,
  /** The last `health` reported an analysis: probes wait 10 s (spec 7.5). */
  val analyzing: Boolean = false,
  /** This episode started with a network change (spec 7.5: "Enter the new address" after a network change). */
  val afterNetworkChange: Boolean = false,
  /** A re-discovery already ran in this episode (it runs once per episode, in the foreground only, NFR-04). */
  val rediscovered: Boolean = false,
  val rediscoveryFailed: Boolean = false,
  val lastProbe: ProbeOutcome? = null,
  val lastProbeAt: Instant? = null,
  /** The not-before time a `429` asked for (server-contract 8.1). */
  val notBefore: Instant? = null,
  /**
   * The computer said this phone is revoked: `ConnectAgain` until this phone pairs again, whatever the network; only
   * "Check now" probes then (spec 7.4: background calls stop until paired again).
   */
  val revoked: Boolean = false,
  /** The last status announced: a transition back to it (through `Checking` or `Looking`) is not announced again. */
  val announced: Announced? = null,
) {
  /** When the machine next needs a [MachineInput.Tick]. */
  val wakeAt: Instant? get() = listOfNotNull(probeAt, lookingUntil).minOrNull()

  /** The probe timeout now: 10 s while the computer was last known to be analyzing, 3 s otherwise (spec 7.5). */
  val probeTimeout: Duration get() = if (analyzing) ConnectivityRules.SLOW_PROBE_TIMEOUT else ConnectivityRules.PROBE_TIMEOUT
}

internal data class Transition(val state: MachineState, val effects: List<MachineEffect>)

/**
 * The probe state machine of architecture 6.2 as a pure reducer: `reduce(state, input, now) -> (state, effects)`.
 * Rules (spec 7.5, SC-10):
 * 1. Not paired → `NotConnected`; no Wi-Fi or Ethernet → `Offline` or `NotOnWifi`; no probes then.
 * 2. A network change to Wi-Fi or Ethernet, and app start → `Looking` (at most 10 s): the stored address first, then
 *    a re-discovery that accepts only an address whose proof verifies.
 * 3. A probe that verifies the computer and is accepted → `Connected`.
 * 4. While `Connected`, a failed probe or a lost channel → `Checking` at once and a re-probe 5 s later; a second failure
 *    → `NotReachable`, a success → `Connected`. `Checking` claims nothing.
 * 5. Revoked → `ConnectAgain` (no scheduled probes until paired again); no valid proof → `DifferentComputer`; old
 *    versions → `ComputerTooOld` / `AppTooOld`; phone access off or not approved → `NotReachable` with
 *    `PhoneAccessOff`; a VPN that blocks the binding → `NotReachable` with `VpnBlocks`.
 * 6. Probes on start, resume and network change; every 30 s in the foreground while anything is pending, every 2 min
 *    otherwise; on demand ("Check now", before sends); in the background only on demand, never on mobile data.
 * 7. Announcements only for transitions to `Connected`, `Offline` and `NotReachable`, and never twice in a row for the
 *    same one (`Connected` → `Checking` → `Connected` says nothing: "no flicker").
 */
internal object ProbeMachine {
  fun reduce(state: MachineState, input: MachineInput, now: Instant): Transition {
    val step = Step(state, now)
    when (input) {
      is MachineInput.Network -> step.network(input.snapshot)
      is MachineInput.Pairing -> step.pairing(input.target, input.lastSeen)
      is MachineInput.Adopted -> step.adopted(input.target, input.health)
      is MachineInput.Foreground -> step.foreground(input.foreground)
      is MachineInput.Pending -> step.pending(input.pending)
      MachineInput.CheckNow -> step.checkNow()
      is MachineInput.ProbeDone -> step.probeDone(input.id, input.report)
      is MachineInput.RediscoveryDone -> step.rediscoveryDone(input.id, input.result)
      MachineInput.ChannelLost -> step.channelLost()
      MachineInput.ChannelAlive -> step.channelAlive()
      MachineInput.Tick -> step.tick()
    }
    // A tick always re-arms the timer: a deadline the wall clock has not reached yet (the clock was set back) waits on.
    return step.finish(state, rearm = input == MachineInput.Tick)
  }

  /** A failure the computer's address may explain, so a re-discovery is worth it (not a VPN, not phone access). */
  fun rediscoverable(outcome: ProbeOutcome?): Boolean = when (outcome) {
    is ProbeOutcome.NoAnswer, ProbeOutcome.Refused, ProbeOutcome.NoRoute, ProbeOutcome.Reset,
    ProbeOutcome.NotPhonoTrainer -> true
    ProbeOutcome.Ok, ProbeOutcome.VpnBlocks, ProbeOutcome.PhoneAccessOff, ProbeOutcome.ProofFailed, null -> false
  }

  private class Step(var s: MachineState, val now: Instant) {
    val effects = mutableListOf<MachineEffect>()

    // --- inputs ------------------------------------------------------------------------------------------------------

    fun network(snapshot: NetworkSnapshot) {
      val link = snapshot.link()
      val handle = snapshot.networkHandle.takeIf { link == Link.HOME }
      val changed = s.link != link || s.networkHandle != handle
      if (!changed) return
      // The first network the monitor reports is the app's start, not a change (spec 7.5 "after a network change").
      val afterChange = link == Link.HOME && s.link != null
      // The client is bound on the first report and whenever the network it may use changes (never mobile data).
      if (s.link == null || s.networkHandle != handle) effects += MachineEffect.Bind(handle)
      s = s.copy(link = link, networkHandle = handle)
      stopWork()
      s = s.copy(rediscovered = false, rediscoveryFailed = false, afterNetworkChange = afterChange, notBefore = null)
      settle()
    }

    fun pairing(target: ProbeTarget?, lastSeen: Instant?) {
      val known = s.pairingKnown
      s = s.copy(pairingKnown = true)
      val current = s.target
      if (target == null) {
        if (current == null && known) return
        stopWork()
        s = s.copy(
          target = null,
          lastSeen = null,
          analyzing = false,
          lastProbe = null,
          lastProbeAt = null,
          revoked = false,
          announced = null,
        )
        effects += MachineEffect.SetPeer(null)
        settle()
        return
      }
      if (current != null && current.serverId == target.serverId && current.deviceId == target.deviceId) {
        // The same pairing, rewritten (an address, the last seen time): nothing to probe.
        if (current.address != target.address) effects += MachineEffect.SetPeer(target.address)
        s = s.copy(target = target, lastSeen = latest(s.lastSeen, lastSeen))
        return
      }
      stopWork()
      s = s.copy(
        target = target,
        lastSeen = lastSeen,
        analyzing = false,
        rediscovered = false,
        rediscoveryFailed = false,
        revoked = false,
      )
      effects += MachineEffect.SetPeer(target.address)
      settle()
    }

    fun adopted(target: ProbeTarget, health: ComputerHealth) {
      stopWork()
      if (s.target?.address != target.address) effects += MachineEffect.SetPeer(target.address)
      s = s.copy(target = target, pairingKnown = true, revoked = false)
      success(health)
    }

    fun foreground(foreground: Boolean) {
      if (s.foreground == foreground) return
      s = s.copy(foreground = foreground)
      if (foreground) {
        // Resume: a probe now, and a new re-discovery may run in this visit.
        s = s.copy(rediscovered = false)
        if (!s.revoked) startProbe()
      } else {
        // No discovery and no periodic probes in the background (NFR-04); a pending re-probe still runs.
        if (s.rediscovery != null) {
          effects += MachineEffect.CancelRediscovery
          s = s.copy(rediscovery = null)
        }
        if (s.status !is ComputerStatus.Checking) s = s.copy(probeAt = null)
      }
    }

    fun pending(pending: Boolean) {
      if (s.pending == pending) return
      s = s.copy(pending = pending)
      val probeAt = s.probeAt ?: return
      val sooner = (s.lastProbeAt ?: now).plus(interval() ?: return)
      if (sooner.isBefore(probeAt)) s = s.copy(probeAt = maxOf(sooner, now))
    }

    fun checkNow() {
      if (s.target == null || s.link != Link.HOME) return
      startProbe()
    }

    fun probeDone(id: Long, report: ProbeReport) {
      if (id != s.probe) return
      s = s.copy(probe = null)
      when (report) {
        is ProbeReport.Success -> success(report.health)
        is ProbeReport.Failure -> failure(report.outcome, report.retryAfterS)
        ProbeReport.ConnectAgain -> connectAgain()
        ProbeReport.DifferentComputer -> different()
        ProbeReport.ComputerTooOld -> settledAnswer(ComputerStatus.ComputerTooOld)
        ProbeReport.AppTooOld -> settledAnswer(ComputerStatus.AppTooOld)
      }
    }

    fun rediscoveryDone(id: Long, result: RediscoveryResult) {
      if (id != s.rediscovery) return
      s = s.copy(rediscovery = null, rediscovered = true)
      val target = s.target ?: return
      when (result) {
        is RediscoveryResult.Found -> {
          s = s.copy(target = target.copy(address = result.address))
          effects += MachineEffect.AddressChanged(result.address)
          startProbe()
        }
        RediscoveryResult.Revoked -> connectAgain()
        RediscoveryResult.Impostor -> {
          record(ProbeOutcome.ProofFailed)
          s = s.copy(status = ComputerStatus.DifferentComputer(target.name), lookingUntil = null)
          scheduleNext()
        }
        RediscoveryResult.NothingFound -> {
          s = s.copy(rediscoveryFailed = true)
          when (val status = s.status) {
            ComputerStatus.Looking -> if (s.probe == null) afterFailedLook()
            is ComputerStatus.NotReachable ->
              s = s.copy(status = status.copy(addressMayHaveChanged = mayHaveChanged(status.lastProbe)))
            else -> Unit
          }
        }
      }
    }

    fun channelLost() {
      if (s.status is ComputerStatus.Connected) {
        s = s.copy(status = ComputerStatus.Checking, probeAt = now.plus(ConnectivityRules.RE_PROBE))
      }
    }

    fun channelAlive() {
      if (s.status is ComputerStatus.Connected) s = s.copy(lastSeen = now)
    }

    fun tick() {
      val until = s.lookingUntil
      if (until != null && !now.isBefore(until)) {
        s = s.copy(lookingUntil = null)
        if (s.status == ComputerStatus.Looking) afterFailedLook()
      }
      val at = s.probeAt
      if (at != null && !now.isBefore(at)) {
        s = s.copy(probeAt = null)
        if (s.notBefore?.isAfter(now) == true) {
          s = s.copy(probeAt = s.notBefore)
        } else {
          startProbe()
        }
      }
    }

    // --- outcomes ----------------------------------------------------------------------------------------------------

    private fun success(health: ComputerHealth) {
      record(ProbeOutcome.Ok)
      effects += MachineEffect.RecordSeen(health)
      s = s.copy(
        status = ComputerStatus.Connected(health.name, health.analyzing, health.inLine, health.lowOnSpace),
        lastSeen = now,
        analyzing = health.analyzing > 0,
        afterNetworkChange = false,
        rediscovered = false,
        rediscoveryFailed = false,
        lookingUntil = null,
        notBefore = null,
        revoked = false,
      )
      scheduleNext()
    }

    private fun failure(outcome: ProbeOutcome, retryAfterS: Int?) {
      record(outcome)
      if (retryAfterS != null && retryAfterS > 0) s = s.copy(notBefore = now.plusSeconds(retryAfterS.toLong()))
      when (s.status) {
        is ComputerStatus.Connected -> {
          s = s.copy(status = ComputerStatus.Checking, probeAt = later(now.plus(ConnectivityRules.RE_PROBE)))
        }
        ComputerStatus.Checking -> notReachable(outcome)
        ComputerStatus.Looking ->
          if (!startRediscovery(outcome)) notReachable(outcome)
        ComputerStatus.ConnectAgain -> Unit
        else -> notReachable(outcome)
      }
    }

    /** `Looking` ended without the computer: its last answer decides between a different computer and not reachable. */
    private fun afterFailedLook() {
      val target = s.target ?: return
      if (s.lastProbe == ProbeOutcome.ProofFailed) {
        s = s.copy(status = ComputerStatus.DifferentComputer(target.name), lookingUntil = null)
        scheduleNext()
      } else {
        notReachable(s.lastProbe?.takeUnless { it == ProbeOutcome.Ok } ?: ProbeOutcome.NoAnswer(s.probeTimeout.seconds.toInt()))
      }
    }

    private fun notReachable(outcome: ProbeOutcome) {
      val target = s.target ?: return
      s = s.copy(
        status = ComputerStatus.NotReachable(target.name, s.lastSeen, mayHaveChanged(outcome), outcome),
        lookingUntil = null,
      )
      scheduleNext()
      startRediscovery(outcome)
    }

    /**
     * The stored address answered without proving it is the paired computer. While `Looking`, a re-discovery may
     * still find the real one (the status stays `Looking` until it ends or the 10 s pass); otherwise the status is
     * `DifferentComputer` at once, and a re-discovery may still move it to the right address.
     */
    private fun different() {
      record(ProbeOutcome.ProofFailed)
      val target = s.target ?: return
      val rediscovering = startRediscovery(ProbeOutcome.ProofFailed, force = true)
      if (!rediscovering || s.status != ComputerStatus.Looking) {
        s = s.copy(status = ComputerStatus.DifferentComputer(target.name), lookingUntil = null)
      }
      scheduleNext()
    }

    private fun connectAgain() {
      record(ProbeOutcome.Ok)
      if (s.rediscovery != null) effects += MachineEffect.CancelRediscovery
      s = s.copy(
        status = ComputerStatus.ConnectAgain,
        probeAt = null,
        lookingUntil = null,
        rediscovery = null,
        revoked = true,
      )
    }

    /** The computer answered, proved itself, and cannot serve this app (versions). */
    private fun settledAnswer(status: ComputerStatus) {
      record(ProbeOutcome.Ok)
      s = s.copy(status = status, lookingUntil = null)
      scheduleNext()
    }

    private fun record(outcome: ProbeOutcome) {
      s = s.copy(lastProbe = outcome, lastProbeAt = now)
      effects += MachineEffect.RecordProbe(outcome)
    }

    private fun mayHaveChanged(outcome: ProbeOutcome): Boolean =
      rediscoverable(outcome) && (s.afterNetworkChange || s.rediscoveryFailed)

    // --- scheduling ----------------------------------------------------------------------------------------------------

    /**
     * The status the pairing and the network alone decide, and the probe a new episode starts with: not paired first,
     * then a revoked phone (whatever the network), then no network, mobile data only, and the home network.
     */
    private fun settle() {
      val target = s.target
      when {
        !s.pairingKnown && target == null -> s = s.copy(status = ComputerStatus.Looking)
        target == null -> s = s.copy(status = ComputerStatus.NotConnected)
        s.revoked -> s = s.copy(status = ComputerStatus.ConnectAgain)
        s.link == null -> s = s.copy(status = ComputerStatus.Looking)
        s.link == Link.NONE -> s = s.copy(status = ComputerStatus.Offline)
        s.link == Link.MOBILE_ONLY -> s = s.copy(status = ComputerStatus.NotOnWifi)
        else -> {
          s = s.copy(status = ComputerStatus.Looking, lookingUntil = now.plus(ConnectivityRules.LOOKING))
          startProbe()
        }
      }
    }

    private fun startProbe() {
      val target = s.target ?: return
      if (s.link != Link.HOME || s.probe != null) return
      s = s.copy(probe = s.nextId, nextId = s.nextId + 1, probeAt = null)
      effects += MachineEffect.Probe(s.probe!!, target, slow = s.analyzing)
    }

    /** Starts a re-discovery if this episode has none yet, the app is in the foreground and it can help. */
    private fun startRediscovery(outcome: ProbeOutcome, force: Boolean = false): Boolean {
      val target = s.target ?: return false
      if (!s.foreground || s.rediscovered || s.rediscovery != null || s.link != Link.HOME) return false
      if (!force && !rediscoverable(outcome)) return false
      s = s.copy(rediscovery = s.nextId, nextId = s.nextId + 1)
      effects += MachineEffect.Rediscover(s.rediscovery!!, target)
      return true
    }

    private fun stopWork() {
      if (s.probe != null) effects += MachineEffect.CancelProbe
      if (s.rediscovery != null) effects += MachineEffect.CancelRediscovery
      s = s.copy(probe = null, rediscovery = null, probeAt = null, lookingUntil = null)
    }

    private fun scheduleNext() {
      val interval = interval()
      s = s.copy(probeAt = interval?.let { later(now.plus(it)) })
    }

    private fun later(at: Instant): Instant = s.notBefore?.takeIf { it.isAfter(at) } ?: at

    private fun latest(a: Instant?, b: Instant?): Instant? = when {
      a == null -> b
      b == null -> a
      else -> maxOf(a, b)
    }

    private fun interval(): Duration? = when {
      !s.foreground -> null
      s.pending -> ConnectivityRules.PENDING_INTERVAL
      else -> ConnectivityRules.IDLE_INTERVAL
    }

    fun finish(before: MachineState, rearm: Boolean): Transition {
      if (rearm || before.wakeAt != s.wakeAt) effects += MachineEffect.Schedule(s.wakeAt)
      val kind = s.status.announced()
      if (kind != null && kind != s.announced) {
        effects += MachineEffect.Announce(s.status)
        s = s.copy(announced = kind)
      }
      return Transition(s, effects.toList())
    }
  }
}
