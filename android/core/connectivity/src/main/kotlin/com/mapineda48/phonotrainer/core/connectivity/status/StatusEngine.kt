package com.mapineda48.phonotrainer.core.connectivity.status

import android.content.Context
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.ConnectivityRules
import com.mapineda48.phonotrainer.core.connectivity.R
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The words of the announced transitions, from this module's strings (spec 7.5). */
internal fun interface StatusWords {
  /** The announcement of [status], or null for a status that is never announced. */
  fun of(status: ComputerStatus): String?
}

internal class ResourceStatusWords @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : StatusWords {
  override fun of(status: ComputerStatus): String? = when (status) {
    is ComputerStatus.Connected -> context.getString(R.string.connectivity_announce_connected, status.name)
    ComputerStatus.Offline -> context.getString(R.string.connectivity_announce_offline)
    is ComputerStatus.NotReachable -> context.getString(R.string.connectivity_announce_not_reachable)
    else -> null
  }
}

/**
 * Where the machine's effects land outside the machine: the computer client's binding, the guard's current address,
 * the pairing store and the announcer. Store writes run one at a time, in order, off the machine's coroutine; a write
 * that fails (DataStore's `IOException`) is logged and the next probe writes again.
 */
@Singleton
internal class StatusSinks @Inject constructor(
  private val store: PairingStore,
  private val peers: AllowedPeers,
  private val verified: VerifiedPeers,
  private val binder: ComputerNetworkBinder,
  private val announcer: Announcer,
  private val words: StatusWords,
  private val clock: Clock,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) {
  private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
  private val writing = AtomicBoolean(false)

  fun bind(networkHandle: Long?) {
    binder.bind(networkHandle)
    verified.reset()
  }

  fun setPeer(address: HostPort?) = peers.setCurrent(address)

  fun addressChanged(address: HostPort) {
    peers.setCurrent(address)
    val at = clock.now()
    write { store.updateAddress(address, at) }
  }

  fun recordProbe(outcome: ProbeOutcome) {
    val at = clock.now()
    write { store.recordProbe(outcome, at) }
  }

  fun recordSeen(health: ComputerHealth) {
    val at = clock.now()
    write {
      store.recordSeen(at, health.apiVersion, health.capabilities)
      store.updateLibraryId(health.libraryId)
    }
  }

  fun announce(status: ComputerStatus) {
    words.of(status)?.let(announcer::polite)
  }

  private fun write(block: suspend () -> Unit) {
    writes.trySend(block)
    if (writing.compareAndSet(false, true)) {
      scope.launch {
        for (next in writes) {
          try {
            next()
          } catch (e: CancellationException) {
            throw e
          } catch (e: IOException) {
            log.w("connectivity.store_write", e)
          }
        }
      }
    }
  }
}

/**
 * The effect runner of [ProbeMachine] (architecture 6.2): one coroutine applies every input in order, so the machine's
 * state never races; probes, re-discoveries and the timer run as their own jobs and report back as inputs. The status
 * is published synchronously with each step. [checkNow] waits for the probe it starts (or the one already running).
 */
@Singleton
internal class StatusEngine @Inject constructor(
  @param:ApplicationScope private val scope: CoroutineScope,
  private val clock: Clock,
  private val prober: Prober,
  private val rediscovery: Rediscovery,
  private val sinks: StatusSinks,
  private val log: PhonoLog,
) {
  private sealed interface Command {
    data class Apply(val input: MachineInput) : Command

    data class Check(val done: CompletableDeferred<ComputerStatus>) : Command
  }

  private val commands = Channel<Command>(Channel.UNLIMITED)
  private val started = AtomicBoolean(false)
  private val machine = MutableStateFlow(MachineState())
  private val statusState = MutableStateFlow(machine.value.status)

  // Touched only by the runner's coroutine.
  private var probeJob: Job? = null
  private var rediscoveryJob: Job? = null
  private var timerJob: Job? = null
  private var network: NetworkSnapshot? = null
  private val waiters = mutableListOf<CompletableDeferred<ComputerStatus>>()

  val state: StateFlow<MachineState> = machine.asStateFlow()
  val status: StateFlow<ComputerStatus> = statusState.asStateFlow()

  /** Starts the runner once; inputs submitted before it are kept and applied in order. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch {
      for (command in commands) {
        when (command) {
          is Command.Apply -> apply(command.input)
          is Command.Check -> {
            apply(MachineInput.CheckNow)
            if (machine.value.probe != null) waiters += command.done else command.done.complete(machine.value.status)
          }
        }
      }
    }
  }

  fun submit(input: MachineInput) {
    commands.trySend(Command.Apply(input))
  }

  suspend fun checkNow(): ComputerStatus {
    val done = CompletableDeferred<ComputerStatus>()
    commands.send(Command.Check(done))
    return done.await()
  }

  private fun apply(input: MachineInput) {
    if (input is MachineInput.Network) network = input.snapshot
    val transition = ProbeMachine.reduce(machine.value, input, clock.now())
    machine.value = transition.state
    statusState.value = transition.state.status
    transition.effects.forEach(::run)
    if (transition.state.probe == null && waiters.isNotEmpty()) {
      waiters.forEach { it.complete(transition.state.status) }
      waiters.clear()
    }
  }

  private fun run(effect: MachineEffect) {
    when (effect) {
      is MachineEffect.Probe -> {
        probeJob?.cancel()
        probeJob = scope.launch {
          val timeout = if (effect.slow) SLOW else FAST
          val report = guarded("connectivity.probe_failed", ProbeReport.Failure(ProbeOutcome.Reset)) {
            prober.probe(effect.target, timeout)
          }
          submit(MachineInput.ProbeDone(effect.id, report))
        }
      }
      MachineEffect.CancelProbe -> {
        probeJob?.cancel()
        probeJob = null
      }
      is MachineEffect.Rediscover -> {
        rediscoveryJob?.cancel()
        val snapshot = network
        rediscoveryJob = scope.launch {
          val result = if (snapshot == null) {
            RediscoveryResult.NothingFound
          } else {
            guarded("connectivity.rediscovery_failed", RediscoveryResult.NothingFound) {
              rediscovery.find(effect.target, snapshot)
            }
          }
          submit(MachineInput.RediscoveryDone(effect.id, result))
        }
      }
      MachineEffect.CancelRediscovery -> {
        rediscoveryJob?.cancel()
        rediscoveryJob = null
      }
      is MachineEffect.Schedule -> {
        timerJob?.cancel()
        timerJob = effect.at?.let { at ->
          scope.launch {
            delay(Duration.between(clock.now(), at).toMillis().coerceAtLeast(0))
            submit(MachineInput.Tick)
          }
        }
      }
      is MachineEffect.Announce -> sinks.announce(effect.status)
      is MachineEffect.Bind -> sinks.bind(effect.networkHandle)
      is MachineEffect.SetPeer -> sinks.setPeer(effect.address)
      is MachineEffect.AddressChanged -> sinks.addressChanged(effect.address)
      is MachineEffect.RecordProbe -> sinks.recordProbe(effect.outcome)
      is MachineEffect.RecordSeen -> sinks.recordSeen(effect.health)
    }
  }

  /** Runs [block]; an unexpected exception (a defect) is logged and answered with [fallback], never thrown. */
  private suspend fun <T> guarded(event: String, fallback: T, block: suspend () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.e(event, e)
    fallback
  }

  private companion object {
    val FAST: Duration = ConnectivityRules.PROBE_TIMEOUT
    val SLOW: Duration = ConnectivityRules.SLOW_PROBE_TIMEOUT
  }
}
