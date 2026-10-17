package com.mapineda48.phonotrainer.core.connectivity.support

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [AllowedPeers] as `:core:network`'s `ComputerPeers` keeps them (architecture 5.2): the current address, and a
 * candidate only while its [withCandidate] block runs. Candidates are checked one at a time; a nested check in the same
 * coroutine replaces the candidate for its block. [candidates] records every address checked.
 */
internal class GuardedPeers : AllowedPeers {
  private val state = MutableStateFlow<HostPort?>(null)
  private val lock = Mutex()
  private val checked = CopyOnWriteArrayList<HostPort>()
  private val listeners = CopyOnWriteArrayList<(HostPort?) -> Unit>()

  @Volatile private var candidate: HostPort? = null

  val candidates: List<HostPort> get() = checked.toList()

  override val current: StateFlow<HostPort?> = state

  override fun setCurrent(address: HostPort?) {
    val previous = state.value
    state.value = address
    if (previous != address) listeners.forEach { it(address) }
  }

  fun addCurrentListener(listener: (HostPort?) -> Unit) {
    listeners += listener
  }

  override suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T {
    checked += address
    if (coroutineContext[Check] != null) {
      val outer = candidate
      candidate = address
      return try {
        block()
      } finally {
        candidate = outer
      }
    }
    return lock.withLock {
      candidate = address
      try {
        withContext(Check()) { block() }
      } finally {
        candidate = null
      }
    }
  }

  /** What the cleartext guard lets a request reach now. */
  fun allows(address: HostPort): Boolean = address == state.value || address == candidate

  private class Check : AbstractCoroutineContextElement(Check) {
    companion object Key : CoroutineContext.Key<Check>
  }
}

/**
 * [VerifiedPeers] as `:core:network`'s `ComputerVerifiedPeers` keeps them (architecture 5.3): one verified address at a
 * time, forgotten when the current address moves to another address (or none), on [reset], and by [forget].
 */
internal class ModelVerifiedPeers(peers: GuardedPeers) : VerifiedPeers {
  @Volatile private var verified: Pair<HostPort, ServerId>? = null

  init {
    peers.addCurrentListener { address -> verified = verified?.takeIf { it.first == address } }
  }

  fun serverIdOf(address: HostPort): ServerId? = verified?.takeIf { it.first == address }?.second

  override fun markVerified(address: HostPort, serverId: ServerId) {
    verified = address to serverId
  }

  override fun isVerified(address: HostPort): Boolean = verified?.first == address

  override fun reset() {
    verified = null
  }

  fun forget(address: HostPort) {
    if (verified?.first == address) verified = null
  }
}

/**
 * The client's guard and proof bookkeeping as `:core:network`'s `HttpComputerClient` and `CleartextGuard` apply them,
 * around the in-memory client: a call that names an address ([hello], [legacyHealth], [pair]) reaches it only if it is
 * the current address or the candidate being checked; anything else fails with `Failed(Guarded)` before it reaches a
 * computer and is recorded in [refused]. A challenged `hello` with a valid proof from a device that is not revoked
 * marks its address verified; any other challenged answer forgets it (so signed calls go there only after a proof).
 * Calls to the current address (the signed ones) pass through: the in-memory client signs them only for a verified
 * address.
 */
internal class GuardedClient(
  private val inner: ComputerClient,
  private val peers: GuardedPeers,
  private val verified: ModelVerifiedPeers,
) : ComputerClient by inner {
  private val refusals = CopyOnWriteArrayList<String>()

  /** Every call the guard refused, as "`route` at `host:port`". */
  val refused: List<String> get() = refusals.toList()

  override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> {
    if (!peers.allows(at)) return refuse("hello", at)
    val result = inner.hello(at, challengeFor)
    if (challengeFor != null) {
      val identity = (result as? ApiResult.Ok)?.value
      val serverId = identity?.serverId
      if (identity != null && serverId != null && identity.proofValid == true && identity.revoked != true) {
        verified.markVerified(at, serverId)
      } else {
        verified.forget(at)
      }
    }
    return result
  }

  override suspend fun legacyHealth(at: HostPort): ApiResult<Int> =
    if (peers.allows(at)) inner.legacyHealth(at) else refuse("legacyHealth", at)

  override suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant> =
    if (peers.allows(at)) inner.pair(at, request) else refuse("pair", at)

  private fun refuse(route: String, at: HostPort): ApiResult.Failed {
    refusals += "$route at ${at.host}:${at.port}"
    return ApiResult.Failed(AppError.Computer.Guarded)
  }
}
