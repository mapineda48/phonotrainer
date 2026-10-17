package com.mapineda48.phonotrainer.core.connectivity.status

import com.mapineda48.phonotrainer.core.connectivity.ConnectivitySession
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveredAddress
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryClient
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryProtocol
import com.mapineda48.phonotrainer.core.connectivity.platform.AppVersion
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One probe of the paired computer (architecture 6.2 rule 3, spec 7.5): `hello` with a fresh challenge at the stored
 * address, then the signed `health`, at most two probe timeouts in all. Nothing signed is sent before the proof
 * verified (the client signs only for a verified address, architecture 5.3), and nothing else is sent at all.
 */
internal class Prober @Inject constructor(
  private val client: ComputerClient,
  private val peers: AllowedPeers,
  private val store: PairingStore,
  private val appVersion: AppVersion,
  private val session: ConnectivitySession,
) {
  suspend fun probe(target: ProbeTarget, timeout: Duration): ProbeReport = session.lock.withLock {
    withTimeoutOrNull(timeout.multipliedBy(2).plus(MARGIN).toMillis()) { exchange(target) }
      ?: ProbeReport.Failure(ProbeOutcome.NoAnswer(timeout.seconds.toInt()))
  }

  private suspend fun exchange(target: ProbeTarget): ProbeReport {
    // Signed calls go to the current address only (architecture 5.2): the probe's address is the current one.
    if (peers.current.value != target.address) peers.setCurrent(target.address)
    return when (val hello = ProbeVerdicts.hello(client.hello(target.address, challengeFor = target.deviceId), target)) {
      ProbeVerdicts.Hello.Proven -> ProbeVerdicts.health(client.health(), target, appVersion.code)
      ProbeVerdicts.Hello.NeedLegacy -> ProbeVerdicts.legacy(client.legacyHealth(target.address))
      ProbeVerdicts.Hello.ProofFailed -> if (keyUnreadable()) ProbeReport.ConnectAgain else ProbeReport.DifferentComputer
      is ProbeVerdicts.Hello.Report -> hello.report
    }
  }

  /**
   * A proof can also fail because this phone cannot read its own key any more (restored to another phone, key store
   * cleared): that reads as revoked, "Connect again" (architecture 20.3), never as another computer.
   */
  private suspend fun keyUnreadable(): Boolean = try {
    store.withDeviceKey { } is DeviceKeyResult.Unreadable
  } catch (_: IOException) {
    false
  }

  private companion object {
    val MARGIN: Duration = Duration.ofSeconds(1)
  }
}

/**
 * Re-discovery of the paired computer (spec 7.3 item 5, architecture 6.1 and 6.2 rule 2): one discovery search, and
 * for every answer carrying the paired server id, `hello` with a fresh challenge at that address as the single
 * candidate. Answers with other server ids are other computers and are ignored. Foreground only (NFR-04): the machine
 * never asks for it in the background.
 *
 * Only an address whose proof verifies is accepted, and only if it is the **only** one. A proof says that some host
 * could get it, not that it comes from the address that answered: a host on the Wi-Fi can answer discovery with the
 * paired server id and pass the phone's challenge on to the real computer. Such a relay can only prove while the real
 * computer answers too, so the search does not stop at the first proof: it goes on until [SETTLE_FLOOR] has passed
 * since it started (one second after the last probe) and [SETTLE_AFTER] since the first proof, or until the search
 * window ends. Two addresses that both prove make the result [RediscoveryResult.Impostor]: nothing is accepted, the
 * status says a different computer answered, and "Enter the new address" stays the way back. Answers with the paired
 * id and no valid proof make it [RediscoveryResult.Impostor] too when nothing proved.
 */
internal class Rediscovery @Inject constructor(
  private val discovery: DiscoveryClient,
  private val client: ComputerClient,
  private val peers: AllowedPeers,
  private val session: ConnectivitySession,
) {
  suspend fun find(target: ProbeTarget, network: NetworkSnapshot): RediscoveryResult = coroutineScope {
    val answers = Channel<DiscoveredAddress>(Channel.UNLIMITED)
    val search = launch {
      try {
        discovery.search(network).filter { it.reply.serverId == target.serverId }.collect { answers.send(it) }
      } finally {
        answers.close()
      }
    }
    val floor = launch { delay(SETTLE_FLOOR.toMillis()) }
    var settled: Job? = null
    // Every address whose proof verified, and whether the computer said this phone is revoked there.
    val proving = LinkedHashMap<HostPort, Boolean>()
    var impostor = false
    try {
      while (true) {
        val candidate = next(answers, settled) ?: break
        val answer = session.lock.withLock {
          peers.withCandidate(candidate.address) { client.hello(candidate.address, challengeFor = target.deviceId) }
        }
        when (val verdict = ProbeVerdicts.hello(answer, target)) {
          ProbeVerdicts.Hello.Proven -> proving[candidate.address] = false
          ProbeVerdicts.Hello.ProofFailed -> impostor = true
          is ProbeVerdicts.Hello.Report -> when (verdict.report) {
            ProbeReport.ConnectAgain -> proving[candidate.address] = true
            ProbeReport.DifferentComputer -> impostor = true
            else -> Unit
          }
          ProbeVerdicts.Hello.NeedLegacy -> Unit
        }
        if (proving.isNotEmpty() && settled == null) {
          settled = launch {
            delay(SETTLE_AFTER.toMillis())
            floor.join()
          }
        }
      }
    } finally {
      search.cancel()
      floor.cancel()
      settled?.cancel()
    }
    val only = proving.entries.singleOrNull()
    when {
      proving.size > 1 -> RediscoveryResult.Impostor
      only != null -> if (only.value) RediscoveryResult.Revoked else RediscoveryResult.Found(only.key)
      impostor -> RediscoveryResult.Impostor
      else -> RediscoveryResult.NothingFound
    }
  }

  /** The next answer, or null once the search ended or, after a proof, once the search [settled]. */
  private suspend fun next(answers: ReceiveChannel<DiscoveredAddress>, settled: Job?): DiscoveredAddress? {
    if (settled == null) return answers.receiveCatching().getOrNull()
    return select {
      answers.onReceiveCatching { it.getOrNull() }
      settled.onJoin { null }
    }
  }

  companion object {
    /** No address is accepted before one second after the last probe (0, 1 and 3 s), when every answer is in. */
    val SETTLE_FLOOR: Duration = DiscoveryProtocol.SCHEDULE.last().plusSeconds(1)

    /** After the first proof, how long the search keeps listening for a second address that proves too. */
    val SETTLE_AFTER: Duration = Duration.ofSeconds(1)
  }
}
