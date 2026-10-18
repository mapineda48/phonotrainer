package com.mapineda48.phonotrainer.core.sync.engine

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.sync.catalogue.CatalogueReconciler
import com.mapineda48.phonotrainer.core.sync.catalogue.ClipRemoval
import com.mapineda48.phonotrainer.core.sync.catalogue.ListingOutcome
import com.mapineda48.phonotrainer.core.sync.catalogue.PairedSource
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.transfer.Downloader
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The orchestrator of architecture 7.1: passes serialized by one process-wide mutex (WorkManager runs in the app
 * process), each in this order:
 * 1. **probe** (skipped while the job channel is open and the computer connected); stop unless `Connected`;
 * 2. **outbox** (`RequestSender.sendOperations`: cancels, removals, re-runs, oldest first, before any new request);
 * 3. **links** (`RequestSender.sendLinks`, in creation order);
 * 4. **listing** (`GET /api/phone/library`): the catalogue reconcile, then `RequestReconciler.reconcile`;
 * 5. **reference** when the listing's API version differs from the one cached (recorded only once accepted);
 * 6. **index** the clips whose verified clip data changed (and, once per process, the stale rule versions);
 * 7. **transfers**: the download worker when a file is wanted, the upload work when a file request waits;
 * 8. **budget**: the storage policy, and removals that waited for a screen to close.
 */
@Singleton
internal class SyncOrchestrator @Inject constructor(
  private val pairing: PairingStore,
  private val status: ComputerStatusRepository,
  private val client: ComputerClient,
  private val sender: Provider<RequestSender>,
  private val reconciler: Provider<RequestReconciler>,
  private val requests: Provider<RequestRepository>,
  private val catalogue: CatalogueReconciler,
  private val removal: ClipRemoval,
  private val reference: ReferenceRepository,
  private val meta: SyncMeta,
  private val indexer: ClipIndexer,
  private val clips: ClipDao,
  private val downloader: Downloader,
  private val work: SyncWork,
  private val channel: ChannelState,
  private val flag: PassFlag,
  private val startup: StartupGate,
  private val clock: Clock,
  private val log: PhonoLog,
) : SyncPasses {
  private val mutex = Mutex()
  private val reindexed = AtomicBoolean(false)

  override suspend fun pass(reason: SyncReason, userVisible: Boolean): PassOutcome = mutex.withLock {
    flag.running.value = true
    try {
      runPass(reason, userVisible)
    } finally {
      flag.running.value = false
    }
  }

  override suspend fun hasPendingWork(): Boolean {
    val waiting = guarded("sync.requests_unknown", 0) { requests.get().counts().first().waiting }
    if (waiting > 0) return true
    return guarded("sync.plan_failed", false) { downloader.refreshQueue().wanted.isNotEmpty() }
  }

  override suspend fun nextRetryIn(): Duration? {
    val now = clock.now()
    val next = guarded("sync.requests_unknown", emptyList()) { requests.get().requests().first() }
      .filter { it.state == RequestState.WAITING && it.waiting?.held != true }
      .mapNotNull { it.waiting?.nextAttemptAt }
      .minOrNull() ?: return null
    return Duration.between(now, next).takeIf { !it.isNegative } ?: Duration.ZERO
  }

  private suspend fun runPass(reason: SyncReason, userVisible: Boolean): PassOutcome {
    startup.awaitDone()
    val paired = guarded("sync.pairing_unreadable", null) { pairing.current() } ?: return PassOutcome.NotPaired
    log.d { "sync pass ($reason)" }
    // 1. Probe.
    val current = status.status.value.takeIf { it is ComputerStatus.Connected && channel.open.value } ?: status.checkNow()
    if (current !is ComputerStatus.Connected) {
      removal.applyDeferred()
      return PassOutcome.NotConnected(current)
    }
    meta.setWifiBackoffMs(0)
    // 2. Outbox, 3. links: before any listing, so a cancel reaches the computer before new requests (F-20-AC5).
    if (guarded("sync.send_failed", false) { sender.get().sendOperations().blocked }) return PassOutcome.Blocked
    if (guarded("sync.send_failed", false) { sender.get().sendLinks().blocked }) return PassOutcome.Blocked
    // 4. Listing.
    val library = when (val answer = client.library()) {
      is ApiResult.Ok -> answer.value
      is ApiResult.Refused -> return refused(answer)
      is ApiResult.Failed -> return failed(answer.error)
    }
    val outcome = catalogue.reconcile(library, PairedSource(paired.serverId.value, paired.serverName))
    if (outcome is ListingOutcome.OtherComputer) {
      // The proof said otherwise a moment ago: let the probe decide, and send or delete nothing more (QT-11).
      status.checkNow()
      return PassOutcome.Blocked
    }
    if (library.libraryId != paired.libraryId) guarded("sync.library_not_stored", Unit) { pairing.updateLibraryId(library.libraryId) }
    downloader.refreshQueue()
    guarded("sync.reconcile_failed", Unit) { reconciler.get().reconcile(library) }
    // 5. Reference.
    refreshReference(library)
    // 6. Index.
    index()
    // 7. Transfers.
    val plan = downloader.refreshQueue()
    if (plan.wanted.isNotEmpty()) work.scheduleDownloads(userVisible)
    guarded("sync.uploads_not_scheduled", Unit) { sender.get().scheduleUploads() }
    // 8. Budget.
    downloader.enforceBudget()
    removal.applyDeferred()
    return PassOutcome.Done(listed = true)
  }

  private suspend fun refreshReference(library: RemoteLibrary) {
    if (meta.referenceApiVersion() == library.apiVersion) return
    when (val answer = client.reference()) {
      is ApiResult.Ok -> {
        guarded("sync.reference_refused", Unit) { reference.replace(answer.value, library.apiVersion) }
        // A body the repository refused must be fetched again at the next pass (WP-04's note, review G-2).
        if (reference.reference.value.apiVersion == library.apiVersion) meta.recordReference(library.apiVersion)
      }
      else -> log.w("sync.reference_unavailable")
    }
  }

  private suspend fun index() {
    if (reindexed.compareAndSet(false, true)) guarded("sync.reindex_failed", Unit) { indexer.reindexStale() }
    for (id in clips.needingIndex()) {
      val outcome = guarded("sync.index_failed", IndexOutcome.Missing) { indexer.index(JobId(id)) }
      if (outcome == IndexOutcome.Corrupt) log.w("sync.clip_data_corrupt")
      downloader.markOnPhoneIfComplete(id)
    }
  }

  private suspend fun refused(answer: ApiResult.Refused): PassOutcome {
    log.w("sync.listing_refused.${answer.code.ifEmpty { "status_${answer.status}" }}")
    return when (answer.code) {
      CREDENTIAL_REJECTED, PHONE_ACCESS_OFF, LAN_FORBIDDEN, APP_TOO_OLD -> {
        status.checkNow()
        PassOutcome.Blocked
      }
      else -> PassOutcome.Done(listed = false)
    }
  }

  private suspend fun failed(error: AppError.Computer): PassOutcome {
    log.w("sync.listing_failed")
    val after = status.checkNow()
    return when (error) {
      AppError.Computer.DifferentComputer, AppError.Computer.Guarded, AppError.Computer.CredentialRejected,
      AppError.Computer.PhoneAccessOff, AppError.Computer.AppTooOld, AppError.Computer.ComputerTooOld -> PassOutcome.Blocked
      else -> if (after is ComputerStatus.Connected) PassOutcome.Done(listed = false) else PassOutcome.NotConnected(after)
    }
  }

  private suspend fun <T> guarded(event: String, fallback: T, block: suspend () -> T): T = try {
    block()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failed: RuntimeException) {
    log.w(event, failed)
    fallback
  } catch (failed: IOException) {
    log.w(event, failed)
    fallback
  }

  private companion object {
    const val CREDENTIAL_REJECTED = "credential_rejected"
    const val PHONE_ACCESS_OFF = "phone_access_off"
    const val LAN_FORBIDDEN = "lan_forbidden"
    const val APP_TOO_OLD = "app_too_old"
  }
}
