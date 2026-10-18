package com.mapineda48.phonotrainer.core.sync.engine

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingRecord
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.spi.ClipLeases
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipRemoval
import com.mapineda48.phonotrainer.core.sync.live.JobChannelSession
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.startup.StartupCheck
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The foreground session of architecture 7.2, started once per process: the start-up check; a pass when the app comes to
 * the foreground (app start, then resume) and when the computer becomes `Connected`; the job channel while the app is
 * in the foreground and connected; the periodic work only while paired (NFR-04); the Wi-Fi retry delay starting again
 * on a network change seen in the foreground; and the removals that waited for a screen to close (QT-10).
 */
@Singleton
internal class ForegroundSession @Inject constructor(
  private val foreground: ForegroundState,
  private val status: ComputerStatusRepository,
  private val pairing: PairingStore,
  private val network: NetworkMonitor,
  private val channel: JobChannelSession,
  private val leases: ClipLeases,
  private val removal: ClipRemoval,
  private val work: SyncWork,
  private val meta: SyncMeta,
  private val startup: StartupCheck,
  private val trigger: Provider<SyncTrigger>,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) {
  private val started = AtomicBoolean(false)

  fun start() {
    if (!started.compareAndSet(false, true)) return
    startup.start()
    scope.launch {
      var first = true
      foreground.foreground.collect { visible ->
        if (!visible) return@collect
        trigger.get().request(if (first) SyncReason.APP_START else SyncReason.RESUME)
        first = false
      }
    }
    // Both start from the values read here, so a change between start() and the collector's subscription still counts.
    val connectedAtStart = status.status.value is ComputerStatus.Connected
    scope.launch {
      status.status.map { it is ComputerStatus.Connected }.changesFrom(connectedAtStart).collect { connected ->
        if (connected) trigger.get().request(SyncReason.CONNECTED)
      }
    }
    scope.launch {
      combine(foreground.foreground, status.status.map { it is ComputerStatus.Connected }, paired()) { visible, connected, paired ->
        visible && connected && paired != null
      }.distinctUntilChanged().collectLatest { live -> if (live) channel.run() }
    }
    scope.launch {
      paired().map { it != null }.distinctUntilChanged().collect { paired ->
        guarded("sync.periodic_not_scheduled") { if (paired) work.ensurePeriodic() else work.cancelUnpaired() }
      }
    }
    val networkAtStart = network.network.value
    scope.launch {
      network.network.changesFrom(networkAtStart).collect {
        if (foreground.foreground.value) guarded("sync.backoff_not_reset") { meta.setWifiBackoffMs(0) }
      }
    }
    scope.launch {
      leases.released.collect { guarded("sync.deferred_removal_failed") { removal.applyDeferred() } }
    }
  }

  /** The values that differ from the one before them, the first compared with [initial]. */
  private fun <T> Flow<T>.changesFrom(initial: T): Flow<T> = flow {
    var last = initial
    collect { value ->
      if (value != last) {
        last = value
        emit(value)
      }
    }
  }

  private fun paired(): Flow<PairingRecord?> = pairing.pairing.catch { failed ->
    if (failed is CancellationException) throw failed
    log.w("sync.pairing_unreadable", failed)
    emit(null)
  }

  private suspend fun guarded(event: String, block: suspend () -> Unit) {
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failed: RuntimeException) {
      log.w(event, failed)
    }
  }
}
