package com.mapineda48.phonotrainer.core.domain.spi

import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.model.SendReport
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Seams between core modules (work-plan 4.2), so that :core:sync never depends on the :core:requests, :core:corpus or
// :core:study implementations.

/** :core:requests (WP-09) → :core:sync (WP-08), architecture 7.1. */
interface RequestSender {
  /** CANCEL, REMOVE_JOB, RERUN, oldest first (unpair is never queued). */
  suspend fun sendOperations(): SendReport

  /** Waiting links in seq order. */
  suspend fun sendLinks(): SendReport

  /** Enqueues the upload work when a file request waits. */
  fun scheduleUploads()
}

/** :core:requests (WP-09) → :core:sync (WP-08). */
interface RequestReconciler {
  /** Request states, automatic re-run, attention notifications. */
  suspend fun reconcile(library: RemoteLibrary)

  /** Live channel event. */
  suspend fun onJob(job: RemoteJob)

  suspend fun onJobDeleted(id: JobId)

  /** Study pack verified → ON_PHONE and "Ready to watch" (once). */
  suspend fun onClipOnPhone(id: JobId)
}

/** :core:corpus (WP-10) → :core:sync (WP-08), architecture 9.5. */
interface ClipIndexer {
  /** Parse → shorts → occurrences, one transaction. */
  suspend fun index(id: JobId): IndexOutcome

  /** After an app update raised a rule version. */
  suspend fun reindexStale()

  suspend fun drop(ids: Set<JobId>)
}

/** :core:corpus (WP-10) → :core:sync (WP-08), architecture 7.7. */
interface ClipLeases {
  fun isOpen(id: JobId): Boolean

  val released: Flow<JobId>
}

/** Bound `@IntoSet`; WP-14 and WP-10 provide one. */
fun interface ClipRemovalHook {
  suspend fun onClipsRemoved(ids: Set<JobId>)
}

/** :core:study (WP-14) → :core:sync eviction (WP-08), architecture 7.5. */
interface StudyProtection {
  /** Today's set and clips opened in the last 24 h. */
  suspend fun protectedClips(): Set<JobId>

  suspend fun lastWatched(): Map<JobId, Instant>
}

/**
 * Bound `@IntoSet`, its class annotated with [StartupOrder]; run once per process by `StartupCheck` (WP-08), and acts
 * only on rows and files older than [processStart] (architecture 4.6).
 */
fun interface StartupTask {
  suspend fun run(processStart: Instant)
}

/** The order in which `StartupCheck` runs a [StartupTask] (lower first); read from the task's class. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StartupOrder(val value: Int)

/** Intake, transfers and the first sync pass wait for `StartupCheck` (architecture 4.6). */
interface StartupGate {
  suspend fun awaitDone()
}

/** :core:connectivity (WP-07); replaced in end-to-end tests. */
interface NetworkMonitor {
  val network: StateFlow<NetworkSnapshot>
}

/** :core:connectivity (WP-07); `FakeDiscovery` in end-to-end tests. */
interface DiscoveryTransport {
  fun probe(datagram: ByteArray, broadcastTargets: List<String>, schedule: List<Duration>, listenFor: Duration): Flow<Datagram>
}
