package com.mapineda48.phonotrainer.core.sync.engine

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sync engine's face to the rest of the app: [SyncController] (the sync state, "Sync now") and the real
 * [SyncTrigger] (architecture 7.2). A request created while a screen is visible (the share sheet's link, a request added
 * in the app) is sent by the expedited `send-now` work, never by the bare application scope, which Android 14+ freezes
 * once the sheet closes (Android review H2b). Every other reason runs one pass in the application scope; requests that
 * arrive while one waits to start are folded into it. A pass that ends without reaching the computer while something
 * waits leaves a `sync-on-wifi` work behind, so the phone tries again when it joins a Wi-Fi network (7.2).
 */
@Singleton
class SyncEngine @Inject internal constructor(
  private val passes: Provider<SyncPasses>,
  private val work: SyncWork,
  private val foreground: ForegroundState,
  private val pairing: PairingStore,
  private val meta: SyncMeta,
  stateSource: SyncStateSource,
  session: Provider<ForegroundSession>,
  @param:ApplicationScope private val scope: CoroutineScope,
  private val log: PhonoLog,
) : SyncController, SyncTrigger {
  private val waiting = AtomicBoolean(false)
  private val visibleWaiting = AtomicBoolean(false)
  private val running = Mutex()

  override val state: StateFlow<SyncState> = stateSource.state

  /** When the last complete listing was reconciled: the source of "Last synced {time}" (F-21-AC8). */
  val lastSynced: Flow<Instant?> = meta.observeLastListingAt()

  init {
    // Started after construction: the session reaches the device queue, which asks this engine for passes.
    scope.launch { session.get().start() }
  }

  override fun syncNow(reason: SyncReason) = request(reason)

  override fun request(reason: SyncReason) {
    when (reason) {
      SyncReason.REQUEST_CREATED, SyncReason.SEND_NOW -> scope.launch {
        if (paired()) guarded("sync.send_now_not_scheduled") { work.sendNow() }
      }
      else -> foregroundPass(reason)
    }
  }

  private fun foregroundPass(reason: SyncReason) {
    if (foreground.foreground.value && reason in USER_VISIBLE) visibleWaiting.set(true)
    // One pass waits to start at most: later reasons fold into it.
    if (!waiting.compareAndSet(false, true)) return
    scope.launch {
      running.withLock {
        waiting.set(false)
        try {
          val outcome = passes.get().pass(reason, userVisible = visibleWaiting.getAndSet(false))
          if (outcome is PassOutcome.NotConnected && passes.get().hasPendingWork()) work.ensureOnWifi()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failed: RuntimeException) {
          log.e("sync.pass_failed", failed)
        } catch (failed: IOException) {
          log.e("sync.pass_failed", failed)
        }
      }
    }
  }

  private suspend fun paired(): Boolean = try {
    pairing.current() != null
  } catch (failed: IOException) {
    log.w("sync.pairing_unreadable", failed)
    false
  }

  private inline fun guarded(event: String, block: () -> Unit) {
    try {
      block()
    } catch (failed: IllegalStateException) {
      // WorkManager is not initialized (a process without the application): nothing can be scheduled.
      log.w(event, failed)
    }
  }

  private companion object {
    /** The learner asked for these in the foreground: the downloads they start run as a visible transfer (7.2). */
    val USER_VISIBLE = setOf(SyncReason.PAIRED, SyncReason.SYNC_NOW, SyncReason.PULL_TO_REFRESH)
  }
}
