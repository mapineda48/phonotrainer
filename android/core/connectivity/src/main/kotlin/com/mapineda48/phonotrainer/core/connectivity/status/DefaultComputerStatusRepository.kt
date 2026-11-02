package com.mapineda48.phonotrainer.core.connectivity.status

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.ComputerNames
import com.mapineda48.phonotrainer.core.connectivity.ConnectivityRules
import com.mapineda48.phonotrainer.core.connectivity.onHomeNetwork
import com.mapineda48.phonotrainer.core.connectivity.platform.AppForeground
import com.mapineda48.phonotrainer.core.connectivity.platform.BackgroundRestrictionMonitor
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ChannelLiveness
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [ComputerStatusRepository] (architecture 6.2): the probe state machine fed by the network monitor, the pairing store,
 * the app's foreground state, what is pending and the job channel, plus the pairing as the learner sees it (with its
 * "Last synced" time from the sync engine, [SyncController.lastSynced]) and the facts of Connection details. It starts
 * with the process (its first injection) and runs in the application scope.
 *
 * Other cores' repositories are read through providers, after construction: `:core:requests` and `:core:sync` read
 * this repository too, so a direct dependency would be a cycle.
 */
@Singleton
internal class DefaultComputerStatusRepository @Inject constructor(
  private val engine: StatusEngine,
  private val store: PairingStore,
  private val monitor: NetworkMonitor,
  private val appForeground: AppForeground,
  private val background: BackgroundRestrictionMonitor,
  private val requests: Provider<RequestRepository>,
  private val sync: Provider<SyncController>,
  private val decoderCapabilities: Provider<DecoderCapabilities>,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) : ComputerStatusRepository {
  private val record = MutableStateFlow<PairingRecord?>(null)
  private val pairedState = MutableStateFlow<PairedComputer?>(null)

  /** What [pairedState] is made of, changed together under [pairedLock] so neither change can undo the other. */
  private val pairedLock = Any()
  private var pairing: PairedComputer? = null
  private var lastListing: Instant? = null

  private val pending: StateFlow<Pending> = combine(
    deferred(PENDING_UNKNOWN) { requests.get().counts() }.onStart { emit(RequestCounts(0, 0, 0, 0)) },
    deferred(PENDING_UNKNOWN) { sync.get().state }.onStart { emit(SyncState.Idle) },
  ) { counts, syncState ->
    Pending(counts.waiting, counts.onComputer, (syncState as? SyncState.Active)?.bringing ?: 0)
  }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Pending())
  private val decoders: List<DecoderInfo> by lazy {
    try {
      decoderCapabilities.get().summary()
    } catch (e: RuntimeException) {
      log.w("connectivity.decoders_unknown", e)
      emptyList()
    }
  }

  override val status: StateFlow<ComputerStatus> = engine.status

  override val paired: StateFlow<PairedComputer?> = pairedState.asStateFlow()

  override val backgroundRestricted: StateFlow<Boolean> = background.restricted

  override val diagnostics: StateFlow<ConnectionDiagnostics> = combine(
    engine.state,
    record,
    monitor.network,
    background.restricted,
    pending,
  ) { machine, stored, network, restricted, pending ->
    diagnosticsOf(machine, stored, network, restricted, pending)
  }.stateIn(scope, SharingStarted.Eagerly, diagnosticsOf(MachineState(), null, monitor.network.value, false, Pending()))

  init {
    engine.start()
    scope.launch { monitor.network.collect { engine.submit(MachineInput.Network(it)) } }
    scope.launch {
      store.pairing
        .catch { error ->
          if (error is CancellationException) throw error
          log.w("connectivity.pairing_unreadable", error)
          emit(null)
        }
        .collect { stored ->
          record.value = stored
          publishPairing(stored?.toPaired())
          engine.submit(MachineInput.Pairing(stored?.toTarget(), stored?.lastSeen))
        }
    }
    scope.launch {
      deferred(LAST_SYNC_UNKNOWN) { sync.get().lastSynced }.distinctUntilChanged().collect { publishListing(it) }
    }
    scope.launch {
      appForeground.foreground.collect { foreground ->
        if (foreground) background.refresh()
        engine.submit(MachineInput.Foreground(foreground))
      }
    }
    scope.launch {
      pending.map { it.any }.distinctUntilChanged().collect { engine.submit(MachineInput.Pending(it)) }
    }
    background.refresh()
  }

  override suspend fun checkNow(): ComputerStatus = engine.checkNow()

  /** Pairing (or a new address) verified the computer and its `health`: `Connected` now (architecture 6.3). */
  fun adopt(target: ProbeTarget, health: ComputerHealth, computer: PairedComputer) {
    publishPairing(computer)
    engine.submit(MachineInput.Adopted(target, health))
  }

  /** "Forget this computer" removed the pairing: `NotConnected` at once, before the store's change arrives. */
  fun forgotten() {
    publishPairing(null)
    record.value = null
    engine.submit(MachineInput.Pairing(null, null))
  }

  /** Probe again soon, without waiting for the answer (after pairing or a new address that did not settle it). */
  fun checkLater() = engine.submit(MachineInput.CheckNow)

  fun channelAlive() = engine.submit(MachineInput.ChannelAlive)

  fun channelLost() = engine.submit(MachineInput.ChannelLost)

  /** What is pending (spec 7.5: the 30 s interval) and its counts for Connection details. */
  private data class Pending(val waiting: Int = 0, val onComputer: Int = 0, val wanted: Int = 0) {
    val any: Boolean get() = waiting > 0 || onComputer > 0 || wanted > 0
  }

  /** The pairing as stored or adopted (or none), shown with the last listing known. */
  private fun publishPairing(computer: PairedComputer?) = synchronized(pairedLock) {
    pairing = computer
    pairedState.value = computer?.syncedAt(lastListing)
  }

  /** When the sync engine last reconciled a complete listing ([at], null before the first): "Last synced" (F-21, F-22). */
  private fun publishListing(at: Instant?) = synchronized(pairedLock) {
    lastListing = at
    pairedState.value = pairing?.syncedAt(at)
  }

  /**
   * Read only once collected, so the other cores are created after this repository, never during its creation. A
   * source that fails is logged as [event] and ends; what it gave before stays.
   */
  private fun <T> deferred(event: String, source: () -> Flow<T>): Flow<T> = flow { emitAll(source()) }.catch { error ->
    if (error is CancellationException) throw error
    log.w(event, error)
  }

  private fun diagnosticsOf(
    machine: MachineState,
    stored: PairingRecord?,
    network: NetworkSnapshot,
    restricted: Boolean,
    pending: Pending,
  ): ConnectionDiagnostics = ConnectionDiagnostics(
    address = stored?.address,
    shortServerId = stored?.serverId?.value?.take(ConnectivityRules.SHORT_ID_CHARS),
    apiVersion = stored?.apiVersion,
    lastProbe = machine.lastProbe ?: stored?.lastProbe,
    lastProbeAt = machine.lastProbeAt ?: stored?.lastProbeAt,
    addressChangedAt = stored?.addressChangedAt,
    waitingRequests = pending.waiting,
    requestsOnComputer = pending.onComputer,
    wantedDownloads = pending.wanted,
    backgroundRestricted = restricted,
    decoders = if (stored == null && machine.target == null) emptyList() else decoders,
    phoneAddress = network.ipv4.takeIf { network.onHomeNetwork },
  )

  private companion object {
    const val PENDING_UNKNOWN = "connectivity.pending_unknown"
    const val LAST_SYNC_UNKNOWN = "connectivity.last_sync_unknown"
  }
}

/** The stored name was cleaned when it was stored; it is cleaned again on the way out ([ComputerNames]). */
internal fun PairingRecord.toTarget(): ProbeTarget =
  ProbeTarget(serverId, ComputerNames.clean(serverName), deviceId, address)

/**
 * This pairing with its "Last synced" time: [listing], when the sync engine last reconciled a complete listing
 * ([SyncController.lastSynced], CR-WP08-1). A listing older than the pairing belongs to an earlier one, perhaps with
 * another computer (forgetting a computer keeps the engine's record), so it is not this computer's and gives none.
 */
internal fun PairedComputer.syncedAt(listing: Instant?): PairedComputer =
  copy(lastSync = listing?.takeUnless { it.isBefore(pairedAt) })

/** The pairing as stored, with no sync time: the repository adds it ([syncedAt]). */
internal fun PairingRecord.toPaired(): PairedComputer = PairedComputer(
  serverId = serverId,
  name = ComputerNames.clean(serverName),
  deviceId = deviceId,
  deviceName = deviceName,
  address = address,
  libraryId = libraryId,
  pairedAt = pairedAt,
  lastSeen = lastSeen,
  lastSync = null,
  apiVersion = apiVersion,
  capabilities = capabilities,
)

/** [ChannelLiveness] (architecture 5.5): the job channel's pongs feed the state machine. */
internal class MachineChannelLiveness @Inject constructor(
  private val status: DefaultComputerStatusRepository,
) : ChannelLiveness {
  override fun channelAlive() = status.channelAlive()

  override fun channelLost() = status.channelLost()
}
