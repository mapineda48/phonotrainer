package com.mapineda48.phonotrainer.core.connectivity.status

import com.mapineda48.phonotrainer.core.connectivity.ConnectivitySession
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryClient
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
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
 * candidate. Only an address whose proof verifies is accepted; answers with the paired id and no valid proof make the
 * result [RediscoveryResult.Impostor], so the status says a different computer answered. Answers with other server
 * ids are other computers and are ignored. Foreground only (NFR-04): the machine never asks for it in the background.
 */
internal class Rediscovery @Inject constructor(
  private val discovery: DiscoveryClient,
  private val client: ComputerClient,
  private val peers: AllowedPeers,
  private val session: ConnectivitySession,
) {
  suspend fun find(target: ProbeTarget, network: NetworkSnapshot): RediscoveryResult {
    var impostor = false
    var revoked = false
    val found: HostPort? = discovery.search(network)
      .filter { it.reply.serverId == target.serverId }
      .firstOrNull { candidate ->
        val answer = session.lock.withLock {
          peers.withCandidate(candidate.address) { client.hello(candidate.address, challengeFor = target.deviceId) }
        }
        when (val verdict = ProbeVerdicts.hello(answer, target)) {
          ProbeVerdicts.Hello.Proven -> true
          ProbeVerdicts.Hello.ProofFailed -> {
            impostor = true
            false
          }
          is ProbeVerdicts.Hello.Report -> {
            when (verdict.report) {
              ProbeReport.ConnectAgain -> revoked = true
              ProbeReport.DifferentComputer -> impostor = true
              else -> Unit
            }
            false
          }
          ProbeVerdicts.Hello.NeedLegacy -> false
        }
      }?.address
    return when {
      found != null -> RediscoveryResult.Found(found)
      revoked -> RediscoveryResult.Revoked
      impostor -> RediscoveryResult.Impostor
      else -> RediscoveryResult.NothingFound
    }
  }
}
