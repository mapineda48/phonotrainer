package com.mapineda48.phonotrainer.core.connectivity.pairing

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.ComputerNames
import com.mapineda48.phonotrainer.core.connectivity.ConnectivityRules
import com.mapineda48.phonotrainer.core.connectivity.ConnectivitySession
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveredAddress
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryClient
import com.mapineda48.phonotrainer.core.connectivity.discovery.DiscoveryProtocol
import com.mapineda48.phonotrainer.core.connectivity.discovery.FoundList
import com.mapineda48.phonotrainer.core.connectivity.onHomeNetwork
import com.mapineda48.phonotrainer.core.connectivity.platform.AppVersion
import com.mapineda48.phonotrainer.core.connectivity.platform.ComputerWork
import com.mapineda48.phonotrainer.core.connectivity.status.DefaultComputerStatusRepository
import com.mapineda48.phonotrainer.core.connectivity.status.ProbeReport
import com.mapineda48.phonotrainer.core.connectivity.status.ProbeTarget
import com.mapineda48.phonotrainer.core.connectivity.status.ProbeVerdicts
import com.mapineda48.phonotrainer.core.connectivity.status.toPaired
import com.mapineda48.phonotrainer.core.datastore.pairing.DeviceKeyResult
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PairingRepository] (architecture 6.1, 6.3; spec 7.3, 7.4, F-02): discovery with a `hello` confirmation per answer,
 * the identity check of a typed address, pairing with the operator's code ("Connect again" proves the old key), a new
 * address accepted only with a valid proof for the paired computer, and "Forget this computer".
 *
 * Every address is reached as the single candidate of the cleartext guard ([AllowedPeers.withCandidate]) until it
 * proved it is the paired computer; only then does it become the current address that signed calls may reach. While a
 * pairing exists, nothing is sent to an address (no code, no re-activation proof) before it proved it is the paired
 * computer with this phone's key (spec 7.4, D-37); only a phone that cannot read its own key any more pairs without
 * that proof, as a new device (architecture 6.3, 20.3). Every computer name is cleaned before it leaves the module
 * ([ComputerNames]).
 */
@Singleton
internal class DefaultPairingRepository @Inject constructor(
  private val client: ComputerClient,
  private val peers: AllowedPeers,
  private val verified: VerifiedPeers,
  private val policy: AddressPolicy,
  private val store: PairingStore,
  private val monitor: NetworkMonitor,
  private val discovery: DiscoveryClient,
  private val status: DefaultComputerStatusRepository,
  private val syncTrigger: Provider<SyncTrigger>,
  private val work: ComputerWork,
  private val cleanup: ForgetCleanup,
  private val appVersion: AppVersion,
  private val session: ConnectivitySession,
  private val clock: Clock,
  private val log: PhonoLog,
) : PairingRepository {
  // --- discovery (architecture 6.1) ------------------------------------------------------------------------------------

  /**
   * "Looking for your computer…" for at most 10 s: every answer is confirmed with `hello` at its address (identity
   * only, no credential) before it is listed; answers that do not confirm, or whose computer has phone access off,
   * are left out. While paired ("Connect again"), an answer carrying the paired server id is listed only if its
   * `hello` proves it is the paired computer with this phone's key (a revoked phone's proof counts), so an address
   * that merely repeats the public server id is never offered (spec 7.4, D-37). Conflicts and preselection follow
   * [FoundList].
   */
  override fun search(): Flow<SearchState> = channelFlow {
    send(SearchState.Searching)
    val network = monitor.network.value
    if (!network.onHomeNetwork) {
      send(SearchState.NotOnWifi)
      return@channelFlow
    }
    val paired = current()?.takeIf { canProve() }
    val rows = mutableListOf<FoundComputer>()
    withTimeoutOrNull(DiscoveryProtocol.LISTEN.toMillis()) {
      discovery.search(network).collect { answer ->
        val row = confirm(answer, paired) ?: return@collect
        rows += row
        send(FoundList.state(rows.toList()))
      }
    }
    if (rows.isEmpty()) send(SearchState.NothingFound)
  }

  private suspend fun confirm(answer: DiscoveredAddress, paired: PairingRecord?): FoundComputer? {
    val proveAs = paired?.takeIf { it.serverId == answer.reply.serverId }
    val identity = if (proveAs == null) {
      peers.withCandidate(answer.address) { client.hello(answer.address) }
    } else {
      session.lock.withLock {
        peers.withCandidate(answer.address) { client.hello(answer.address, challengeFor = proveAs.deviceId) }
      }
    }
    val value = (identity as? ApiResult.Ok)?.value ?: return null
    if (!value.phoneAccess || value.serverId != answer.reply.serverId) return null
    if (proveAs != null && !proves(value, proveAs, answer.address)) {
      log.w("connectivity.search_unproven")
      return null
    }
    val name = ComputerNames.clean(value.name, fallback = answer.reply.name)
    return FoundComputer(
      serverId = answer.reply.serverId,
      name = name,
      address = answer.address,
      apiVersion = value.apiVersion,
      phoneAccess = true,
      tooOld = value.apiVersion < ConnectivityRules.MIN_SERVER_API,
      // A `hello` that names another computer than its own discovery reply is not trusted to be the one it says.
      conflict = ComputerNames.conflictKey(name) != ComputerNames.conflictKey(answer.reply.name),
    )
  }

  // --- a typed address (F-02 behaviour 4, States) ------------------------------------------------------------------

  override suspend fun check(address: HostPort): CheckResult {
    if (!policy.allowsCandidate(address.host)) return CheckResult.NotPrivate
    if (!monitor.network.value.onHomeNetwork) return CheckResult.NoAnswer
    return peers.withCandidate(address) {
      when (val result = client.hello(address)) {
        is ApiResult.Ok -> identityCheck(result.value, address)
        is ApiResult.Refused -> refusedCheck(result, address)
        is ApiResult.Failed -> failedCheck(result.error)
      }
    }
  }

  private fun identityCheck(identity: ComputerIdentity, address: HostPort): CheckResult {
    if (!identity.phoneAccess) return CheckResult.PhoneAccessOff
    val serverId = identity.serverId ?: return CheckResult.NotPhonoTrainer
    if (identity.apiVersion < ConnectivityRules.MIN_SERVER_API) return CheckResult.TooOld
    val name = ComputerNames.clean(identity.name)
    return CheckResult.Ok(
      FoundComputer(serverId, name, address, identity.apiVersion, phoneAccess = true, tooOld = false, conflict = false),
    )
  }

  private suspend fun refusedCheck(refused: ApiResult.Refused, address: HostPort): CheckResult = when {
    refused.status == NOT_FOUND -> if (olderPhonoTrainer(address)) CheckResult.TooOld else CheckResult.NotPhonoTrainer
    refused.code in PHONE_ACCESS_CODES -> CheckResult.PhoneAccessOff
    else -> CheckResult.NotPhonoTrainer
  }

  private fun failedCheck(error: AppError.Computer): CheckResult = when (error) {
    AppError.Computer.Malformed -> CheckResult.NotPhonoTrainer
    AppError.Computer.Guarded -> CheckResult.NotPrivate
    AppError.Computer.PhoneAccessOff -> CheckResult.PhoneAccessOff
    AppError.Computer.ComputerTooOld -> CheckResult.TooOld
    else -> CheckResult.NoAnswer
  }

  /**
   * After a `404` on `hello`: `GET /api/health` with an `api_version` is an older PhonoTrainer (D-15). It goes to the
   * same candidate, so the caller runs it inside that address's [AllowedPeers.withCandidate] block.
   */
  private suspend fun olderPhonoTrainer(address: HostPort): Boolean =
    ProbeVerdicts.legacy(client.legacyHealth(address)) == ProbeReport.ComputerTooOld

  // --- pairing (architecture 6.3) ------------------------------------------------------------------------------------

  override suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult {
    if (!CODE.matches(code)) return PairResult.CodeInvalid
    if (!monitor.network.value.onHomeNetwork) return PairResult.Failed(AppError.Computer.NotOnWifi)
    if (!policy.allowsCandidate(target.address.host)) return PairResult.Failed(AppError.Computer.Guarded)
    return session.lock.withLock { pairLocked(target, code, deviceName.trim()) }
  }

  private suspend fun pairLocked(target: PairingTarget, code: String, deviceName: String): PairResult {
    val address = target.address
    val existing = current()
    // While paired, the address must first prove it is the paired computer with this phone's key; a phone that can no
    // longer read its key cannot prove anything and pairs as a new device (architecture 6.3, 20.3).
    val proveAs = existing?.takeIf { canProve() }
    val identity = when (val hello = helloAt(address, challengeFor = proveAs?.deviceId)) {
      is Hello.Answered -> hello.identity
      is Hello.Ended -> return hello.result
    }
    if (!identity.phoneAccess) return PairResult.PhoneAccessOff
    if (identity.apiVersion < ConnectivityRules.MIN_SERVER_API) return PairResult.Failed(AppError.Computer.ComputerTooOld)
    val serverId = identity.serverId ?: return PairResult.Failed(AppError.Computer.Malformed)
    // The answer at this address must be the computer the learner picked, and one computer at a time (D-29).
    if (target.serverId != null && target.serverId != serverId) return PairResult.Failed(AppError.Computer.DifferentComputer)
    if (existing != null && existing.serverId != serverId) return PairResult.Failed(AppError.Computer.DifferentComputer)
    if (proveAs != null && !proves(identity, proveAs, address)) {
      // The public server id without this phone's proof: no code, no re-activation proof, the pairing untouched.
      log.w("connectivity.pairing_address_unproven")
      return PairResult.Failed(AppError.Computer.DifferentComputer)
    }

    val previous = proveAs?.let { reactivation(it, code) }
    val request = PairRequest(
      code = code,
      deviceName = deviceName,
      appVersion = appVersion.code,
      appVersionName = appVersion.name,
      previousDeviceId = previous?.first,
      previousTokenProof = previous?.second,
    )
    val grant = when (val answer = peers.withCandidate(address) { client.pair(address, request) }) {
      is ApiResult.Ok -> answer.value
      is ApiResult.Refused -> return refusedPair(answer)
      is ApiResult.Failed -> return PairResult.Failed(answer.error)
    }
    if (grant.serverId != serverId) {
      grant.token.fill(Char(0))
      return PairResult.Failed(AppError.Computer.DifferentComputer)
    }
    return adoptGrant(grant.copy(name = ComputerNames.clean(grant.name, fallback = identity.name)), address, deviceName)
  }

  /** What `hello` at a candidate gave pairing: an identity to go on with, or the result pairing ends with. */
  private sealed interface Hello {
    data class Answered(val identity: ComputerIdentity) : Hello

    data class Ended(val result: PairResult) : Hello
  }

  /**
   * `hello` at [address] as the single candidate (with a challenge for [challengeFor]); after a `404`, the legacy
   * health at the same candidate, inside the same block, so the guard lets it through (server-contract 6.2).
   */
  private suspend fun helloAt(address: HostPort, challengeFor: DeviceId?): Hello = peers.withCandidate(address) {
    when (val hello = client.hello(address, challengeFor)) {
      is ApiResult.Ok -> Hello.Answered(hello.value)
      is ApiResult.Refused -> Hello.Ended(refusedHello(hello, address))
      is ApiResult.Failed -> Hello.Ended(PairResult.Failed(hello.error))
    }
  }

  /**
   * Whether [identity], the answer to a challenge for [paired]'s device at [address], proves it is the paired computer:
   * a valid proof for the paired server id, from a device the computer knows, revoked or not (a revoked phone must be
   * able to connect again, spec 7.4). Anything else is a different computer (D-37).
   */
  private fun proves(identity: ComputerIdentity, paired: PairingRecord, address: HostPort): Boolean {
    val target = ProbeTarget(paired.serverId, paired.serverName, paired.deviceId, address)
    return when (val verdict = ProbeVerdicts.hello(ApiResult.Ok(identity, OK), target)) {
      ProbeVerdicts.Hello.Proven -> true
      is ProbeVerdicts.Hello.Report -> verdict.report == ProbeReport.ConnectAgain
      else -> false
    }
  }

  /**
   * Whether this phone can still compute proofs with its stored key: false only when the store says the key cannot be
   * read (restored to another phone, key store cleared; architecture 20.3) or is gone. A store that fails to read
   * (DataStore's `IOException`) does not count as unreadable: the proof is still asked for, and fails safe.
   */
  private suspend fun canProve(): Boolean = try {
    store.withDeviceKey { } is DeviceKeyResult.Ok
  } catch (e: IOException) {
    log.w("connectivity.key_check_failed", e)
    true
  }

  /**
   * Stores the grant in one write, makes its address current, then asks the computer to prove itself with the new key
   * and probes it (architecture 6.3). A computer that answers without the proof is not kept: the pairing is removed
   * and nothing is sent there. A probe that only fails to arrive keeps the pairing; the status machine probes again.
   */
  private suspend fun adoptGrant(grant: PairGrant, address: HostPort, deviceName: String): PairResult {
    val now = clock.now()
    try {
      store.save(grant, address, deviceName, now, grant.serverTime - now.epochSecond)
    } catch (e: IOException) {
      log.w("connectivity.pairing_not_stored", e)
      return PairResult.Failed(AppError.Computer.Refused(NOT_STORED, "The pairing could not be stored on this phone."))
    }
    val record = current()
      ?: return PairResult.Failed(AppError.Computer.Refused(NOT_STORED, "The pairing could not be stored on this phone."))
    peers.setCurrent(address)
    val target = ProbeTarget(record.serverId, record.serverName, record.deviceId, address)
    val proven = ProbeVerdicts.hello(client.hello(address, challengeFor = record.deviceId), target)
    if (proven == ProbeVerdicts.Hello.ProofFailed || proven == ProbeVerdicts.Hello.Report(ProbeReport.DifferentComputer)) {
      log.w("connectivity.pairing_unproven")
      forgetLocally(clearStore = true)
      return PairResult.Failed(AppError.Computer.DifferentComputer)
    }
    val computer = record.toPaired()
    if (proven == ProbeVerdicts.Hello.Proven) {
      when (val health = ProbeVerdicts.health(client.health(), target, appVersion.code)) {
        is ProbeReport.Success -> status.adopt(target, health.health, computer.copy(lastSeen = now))
        ProbeReport.AppTooOld -> {
          // Kept: once this app is updated, the same pairing works (the status says "Update this app").
          status.checkLater()
          return PairResult.AppTooOld
        }
        else -> status.checkLater()
      }
    } else {
      status.checkLater()
    }
    syncTrigger.get().request(SyncReason.PAIRED)
    return PairResult.Paired(computer)
  }

  /** A refused `hello` (runs inside the candidate block: the legacy health after a `404` goes to the same address). */
  private suspend fun refusedHello(refused: ApiResult.Refused, address: HostPort): PairResult = when {
    refused.status == NOT_FOUND ->
      PairResult.Failed(if (olderPhonoTrainer(address)) AppError.Computer.ComputerTooOld else AppError.Computer.Malformed)
    refused.code in PHONE_ACCESS_CODES -> PairResult.PhoneAccessOff
    else -> PairResult.Failed(AppError.Computer.Refused(refused.code, refused.detail))
  }

  private fun refusedPair(refused: ApiResult.Refused): PairResult = when {
    refused.code == "code_invalid" -> PairResult.CodeInvalid
    refused.code == "code_expired" -> PairResult.CodeExpired
    refused.code == "code_locked" -> PairResult.CodeLocked
    refused.code == "rate_limited" || refused.status == TOO_MANY -> PairResult.RateLimited(refused.retryAfterS)
    refused.code == "app_too_old" || refused.status == UPGRADE_REQUIRED -> PairResult.AppTooOld
    refused.code in PHONE_ACCESS_CODES -> PairResult.PhoneAccessOff
    else -> PairResult.Failed(AppError.Computer.Refused(refused.code, refused.detail))
  }

  /**
   * "Connect again" with the same computer, once its address proved it is that computer: the stored device id and the
   * proof made with its key, so the computer re-activates the same device (security review L-1). A key that cannot be
   * read pairs as a new device, silently.
   */
  private suspend fun reactivation(existing: PairingRecord, code: String): Pair<DeviceId, String>? = try {
    when (val proof = store.withDeviceKey { key -> ReactivationProof.of(key, code) }) {
      is DeviceKeyResult.Ok -> existing.deviceId to proof.value
      DeviceKeyResult.NotPaired, DeviceKeyResult.Unreadable -> null
    }
  } catch (e: IOException) {
    log.w("connectivity.old_key_unreadable", e)
    null
  }

  // --- a new address (spec 7.3 item 5, F-02-AC6) ---------------------------------------------------------------------

  override suspend fun useNewAddress(address: HostPort): CheckResult {
    val record = current() ?: return CheckResult.DifferentComputer
    if (!policy.allowsCandidate(address.host)) return CheckResult.NotPrivate
    if (!monitor.network.value.onHomeNetwork) return CheckResult.NoAnswer
    return session.lock.withLock {
      val target = ProbeTarget(record.serverId, record.serverName, record.deviceId, address)
      val verdict = peers.withCandidate(address) {
        when (val hello = ProbeVerdicts.hello(client.hello(address, challengeFor = record.deviceId), target)) {
          ProbeVerdicts.Hello.NeedLegacy ->
            ProbeVerdicts.Hello.Report(ProbeVerdicts.legacy(client.legacyHealth(address)))
          else -> hello
        }
      }
      when (verdict) {
        ProbeVerdicts.Hello.Proven -> accept(record, target, revoked = false)
        ProbeVerdicts.Hello.ProofFailed -> CheckResult.DifferentComputer
        ProbeVerdicts.Hello.NeedLegacy -> CheckResult.NotPhonoTrainer
        is ProbeVerdicts.Hello.Report -> when (val report = verdict.report) {
          ProbeReport.ConnectAgain -> accept(record, target, revoked = true)
          ProbeReport.DifferentComputer -> CheckResult.DifferentComputer
          ProbeReport.ComputerTooOld -> CheckResult.TooOld
          is ProbeReport.Failure -> when (report.outcome) {
            ProbeOutcome.PhoneAccessOff -> CheckResult.PhoneAccessOff
            ProbeOutcome.NotPhonoTrainer -> CheckResult.NotPhonoTrainer
            else -> CheckResult.NoAnswer
          }
          else -> CheckResult.NoAnswer
        }
      }
    }
  }

  /** The address proved it is the paired computer: it is stored, made current and probed (a revoked phone included). */
  private suspend fun accept(record: PairingRecord, target: ProbeTarget, revoked: Boolean): CheckResult {
    val address = target.address
    try {
      store.updateAddress(address, clock.now())
    } catch (e: IOException) {
      log.w("connectivity.address_not_stored", e)
    }
    peers.setCurrent(address)
    val found = FoundComputer(record.serverId, record.serverName, address, record.apiVersion, true, false, false)
    if (revoked) {
      status.checkLater()
      return CheckResult.Ok(found)
    }
    when (val health = ProbeVerdicts.health(client.health(), target, appVersion.code)) {
      is ProbeReport.Success -> status.adopt(target, health.health, record.toPaired().copy(address = address))
      else -> status.checkLater()
    }
    return CheckResult.Ok(found)
  }

  // --- forget (architecture 6.3, spec 7.9, F-22) ---------------------------------------------------------------------

  /**
   * "Forget this computer": the unpair is sent at once, only while connected and for at most 5 s, never queued (the
   * key is deleted right after); then the pairing and its key are removed, the unique works cancelled, and the
   * library database updated ([ForgetCleanup]). Clips and waiting requests stay.
   */
  override suspend fun forget(): ForgetResult = session.lock.withLock {
    val record = current()
    if (record != null && status.status.value is ComputerStatus.Connected) {
      val unpaired = withTimeoutOrNull(ConnectivityRules.UNPAIR_LIMIT.toMillis()) { client.unpairSelf() }
      if (unpaired !is ApiResult.Ok) log.i { "unpair not confirmed; the computer keeps this phone until revoked there" }
    }
    try {
      store.clear()
    } catch (e: IOException) {
      log.w("connectivity.forget_failed", e)
      return@withLock ForgetResult.Failed(AppError.Storage.Io)
    }
    forgetLocally(clearStore = false)
    work.cancelAll()
    if (record != null) {
      try {
        cleanup.forget(record.serverId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: RuntimeException) {
        log.e("connectivity.forget_cleanup_failed", e)
      }
    }
    ForgetResult.Forgotten
  }

  /** No current address, nothing verified, and the status machine told at once. */
  private suspend fun forgetLocally(clearStore: Boolean) {
    if (clearStore) {
      try {
        store.clear()
      } catch (e: IOException) {
        log.w("connectivity.forget_failed", e)
      }
    }
    peers.setCurrent(null)
    verified.reset()
    status.forgotten()
  }

  private suspend fun current(): PairingRecord? = try {
    store.current()
  } catch (e: IOException) {
    log.w("connectivity.pairing_unreadable", e)
    null
  }

  private companion object {
    val CODE = Regex("^[0-9]{6}$")
    val PHONE_ACCESS_CODES = setOf("phone_access_off", "lan_forbidden")
    const val OK = 200
    const val NOT_FOUND = 404
    const val UPGRADE_REQUIRED = 426
    const val TOO_MANY = 429
    const val NOT_STORED = "not_stored"
  }
}
