package com.mapineda48.phonotrainer.core.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.sync.engine.DownloadRunner
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundState
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.engine.SyncPasses
import com.mapineda48.phonotrainer.core.sync.engine.SyncWork
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.transfer.RunOutcome
import com.mapineda48.phonotrainer.core.sync.work.DownloadWorker
import com.mapineda48.phonotrainer.core.sync.work.SyncWorker
import java.time.Duration
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [SyncPasses] whose answers a test sets; it records every reason it ran for. */
internal class ScriptedPasses(
  var outcome: PassOutcome = PassOutcome.NotConnected(
    ComputerStatus.NotReachable("Desk", lastSeen = null, addressMayHaveChanged = false, lastProbe = ProbeOutcome.NoAnswer(3)),
  ),
) : SyncPasses {
  val reasons: MutableList<SyncReason> = Collections.synchronizedList(mutableListOf())
  var pending = true
  var retryIn: Duration? = null

  override suspend fun pass(reason: SyncReason, userVisible: Boolean): PassOutcome {
    reasons += reason
    return outcome
  }

  override suspend fun hasPendingWork(): Boolean = pending

  override suspend fun nextRetryIn(): Duration? = retryIn
}

internal class SettableForeground(visible: Boolean = false) : ForegroundState {
  val state = MutableStateFlow(visible)
  override val foreground: StateFlow<Boolean> = state
}

/** The "Transfers" notification as a plain notification, for the workers' foreground side. */
internal fun transferNotifier(context: Context) = TransferNotifier { _, progress, _ ->
  NotificationCompat.Builder(context, "transfers").setContentTitle(progress.title).setSmallIcon(android.R.drawable.stat_sys_download).build()
}

/**
 * Builds `:core:sync`'s workers by hand from [passes], [runner], [work] and [meta], as Hilt's worker factory does in the
 * app (work-plan 2.4: core tests wire their own fakes).
 */
internal class SyncWorkerFactory(
  private val passes: () -> SyncPasses,
  private val runner: () -> DownloadRunner,
  private val work: () -> SyncWork,
  private val meta: () -> SyncMeta,
  private val foreground: ForegroundState = SettableForeground(),
  private val log: PhonoLog = PhonoLog.NONE,
) : WorkerFactory() {
  override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
    when (workerClassName) {
      SyncWorker::class.java.name ->
        SyncWorker(appContext, workerParameters, passes(), work(), meta(), transferNotifier(appContext), log)
      DownloadWorker::class.java.name ->
        DownloadWorker(appContext, workerParameters, runner(), passes(), work(), meta(), transferNotifier(appContext), foreground, log)
      else -> null
    }
}

/** The test WorkManager (synchronous executor, a test driver) with [factory]. */
internal fun initWorkManager(context: Context, factory: WorkerFactory) {
  WorkManagerTestInitHelper.initializeTestWorkManager(
    context,
    Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build(),
  )
}

/** A download run that ends at once. */
internal val NO_DOWNLOADS = DownloadRunner { RunOutcome.DONE }
