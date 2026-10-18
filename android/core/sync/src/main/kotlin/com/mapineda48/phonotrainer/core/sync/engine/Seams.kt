package com.mapineda48.phonotrainer.core.sync.engine

import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import com.mapineda48.phonotrainer.core.sync.transfer.RunProgress
import java.time.Duration
import kotlinx.coroutines.flow.StateFlow

// The seams inside :core:sync that its workers and its foreground session use, so each is tested with a fake: one sync
// pass, one download run, the WorkManager requests, whether the app is in the foreground, whether the job channel is
// open.

/** How one sync pass ended (architecture 7.1). */
internal sealed interface PassOutcome {
  /** No computer is paired: nothing to do. */
  data object NotPaired : PassOutcome

  /** The probe did not reach `Connected` ([status]): requests and downloads wait. */
  data class NotConnected(val status: ComputerStatus) : PassOutcome

  /** The computer refuses this phone or is not the paired one: the queue is paused until the learner acts. */
  data object Blocked : PassOutcome

  /** The pass ran to its end ([listed]: the listing was reconciled). */
  data class Done(val listed: Boolean) : PassOutcome
}

/** One sync pass, serialized with every other (architecture 7.1). */
internal interface SyncPasses {
  suspend fun pass(reason: SyncReason, userVisible: Boolean = false): PassOutcome

  /** Whether something waits for the computer: requests or operations to send, or files to download (7.2). */
  suspend fun hasPendingWork(): Boolean

  /** When the earliest waiting request may be tried again, if one waits for its retry schedule (7.2). */
  suspend fun nextRetryIn(): Duration?
}

/** One download run (architecture 7.4). */
internal fun interface DownloadRunner {
  suspend fun run(progress: (RunProgress) -> Unit): RunOutcome
}

/** The unique works of architecture 7.2 that `:core:sync` owns, by name. */
internal interface SyncWork {
  /** `downloads`: a run of the download worker; [userVisible] when the learner started it in the foreground. */
  fun scheduleDownloads(userVisible: Boolean)

  /** `send-now`: one pass as expedited work, enqueued while a screen is visible (the share sheet, H2b). */
  fun sendNow()

  /** `sync-on-wifi` with the unmetered constraint, kept when one is already waiting (something became pending). */
  fun ensureOnWifi()

  /** The next `sync-on-wifi` after [delay], appended from inside a run (never with a zero delay, Android review M1). */
  fun nextOnWifi(delay: Duration)

  /** `sync-periodic`: every 15 minutes on an unmetered network with battery not low, only while paired (NFR-04). */
  fun ensurePeriodic()

  /** No computer is paired any longer: the periodic and the Wi-Fi works go. */
  fun cancelUnpaired()
}

/** Whether the app has a visible activity (`ProcessLifecycleOwner` at least STARTED). */
internal interface ForegroundState {
  val foreground: StateFlow<Boolean>
}

/** Whether the job channel is open now: an open channel proves liveness, so a pass may skip its probe (7.1 step 1). */
internal interface ChannelState {
  val open: StateFlow<Boolean>
}
