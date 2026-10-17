package com.mapineda48.phonotrainer.core.network.peers

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The addresses the cleartext guard lets requests reach (architecture 5.2): the paired computer's current address and,
 * only inside [withCandidate], the single address being checked. Candidates are checked one at a time: a second
 * [withCandidate] from another coroutine waits for the first to finish; a nested one in the same coroutine replaces
 * the candidate for its block and restores it afterwards.
 */
@Singleton
internal class ComputerPeers @Inject constructor() : AllowedPeers {
  private val currentAddress = MutableStateFlow<HostPort?>(null)
  private val candidateLock = Mutex()
  private val currentListeners = CopyOnWriteArrayList<(HostPort?) -> Unit>()

  @Volatile private var candidate: HostPort? = null

  override val current: StateFlow<HostPort?> = currentAddress.asStateFlow()

  override fun setCurrent(address: HostPort?) {
    val previous = currentAddress.getAndUpdate { address }
    if (previous != address) currentListeners.forEach { it(address) }
  }

  /** Runs [listener] with the new address every time the current address changes (not when it is set again). */
  fun addCurrentListener(listener: (HostPort?) -> Unit) {
    currentListeners += listener
  }

  override suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T {
    if (coroutineContext[CandidateCheck] != null) {
      val outer = candidate
      candidate = address
      return try {
        block()
      } finally {
        candidate = outer
      }
    }
    return candidateLock.withLock {
      candidate = address
      try {
        withContext(CandidateCheck()) { block() }
      } finally {
        candidate = null
      }
    }
  }

  /** The current address and the candidate being checked, if any: what the guard may let through. */
  fun reachable(): List<HostPort> = listOfNotNull(currentAddress.value, candidate)

  /** Marks the coroutine that holds the candidate, so a nested check does not wait for itself. */
  private class CandidateCheck : AbstractCoroutineContextElement(CandidateCheck) {
    companion object Key : CoroutineContext.Key<CandidateCheck>
  }
}

/**
 * The address whose `hello` proof verified for the paired server id (architecture 5.3). One address at a time: a new
 * proof replaces the previous one. Verification is reset on every network change (the binding's listener) and every
 * address change (the current address moving to any other address, or to none, forgets it), so after either change
 * nothing is signed before a new proof, not even when the address later moves back.
 */
@Singleton
internal class ComputerVerifiedPeers @Inject constructor(
  binder: ComputerNetworkBinder,
  peers: ComputerPeers,
) : VerifiedPeers {
  private val verified = AtomicReference<Pair<HostPort, ServerId>?>(null)

  init {
    (binder as? NetworkBinding)?.addListener { reset() }
    peers.addCurrentListener { address -> verified.updateAndGet { if (it?.first == address) it else null } }
  }

  override fun markVerified(address: HostPort, serverId: ServerId) {
    verified.set(address to serverId)
  }

  override fun isVerified(address: HostPort): Boolean = verified.get()?.first == address

  override fun reset() {
    verified.set(null)
  }

  /** Forgets [address] only, when an answer there no longer proves it (a wrong or missing proof). */
  fun forget(address: HostPort) {
    verified.updateAndGet { if (it?.first == address) null else it }
  }
}

/**
 * The computer's clock minus the phone's (architecture 5.3), learned from a verified `hello`, a pairing answer and a
 * `401 clock_skew`. It starts from the offset the pairing store kept and writes every new one back (the store ignores
 * it while nothing is paired).
 */
@Singleton
internal class LearnedClockOffset @Inject constructor(
  private val clock: Clock,
  private val store: PairingStore,
  @ApplicationScope private val scope: CoroutineScope,
) : ClockOffset {
  private val offset = MutableStateFlow(0L)

  @Volatile private var learned = false

  init {
    scope.launch {
      val stored = try {
        store.current()?.clockOffsetS
      } catch (_: IOException) {
        null
      }
      if (stored != null && !learned) offset.value = stored
    }
  }

  override val offsetS: StateFlow<Long> = offset.asStateFlow()

  override fun update(serverTime: Long) {
    val learnedOffset = serverTime - clock.now().epochSecond
    learned = true
    offset.value = learnedOffset
    scope.launch {
      try {
        store.updateClockOffset(learnedOffset)
      } catch (_: IOException) {
        // The offset is learned again from the next hello.
      }
    }
  }
}
