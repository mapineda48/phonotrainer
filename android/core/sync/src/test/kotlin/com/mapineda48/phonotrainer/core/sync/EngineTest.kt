package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.engine.SyncEngine
import com.mapineda48.phonotrainer.core.sync.engine.SyncPasses
import com.mapineda48.phonotrainer.core.sync.engine.SyncStateSource
import java.time.Duration
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sync engine's triggers (architecture 7.2; Android review H2b): a request created while a screen is visible goes
 * through the expedited `send-now` work, every other reason runs one pass in the application scope, reasons arriving
 * while a pass waits to start are folded into it, and a pass that missed the computer leaves a Wi-Fi work behind.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTest {
  /** Passes that wait until a test lets them end, recording each reason and whether it was visible. */
  private class GatedPasses(var outcome: PassOutcome = PassOutcome.Done(listed = true)) : SyncPasses {
    val calls: MutableList<Pair<SyncReason, Boolean>> = Collections.synchronizedList(mutableListOf())
    @Volatile var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
    var pending = false

    override suspend fun pass(reason: SyncReason, userVisible: Boolean): PassOutcome {
      calls += reason to userVisible
      gate.await()
      return outcome
    }

    override suspend fun hasPendingWork(): Boolean = pending

    override suspend fun nextRetryIn(): Duration? = null
  }

  private suspend fun until(what: String, condition: () -> Boolean) {
    withContext(Dispatchers.Default) { withTimeout(10_000) { while (!condition()) delay(10) } }
    check(condition()) { what }
  }

  private fun engine(block: suspend (SyncHarness, SyncEngine, GatedPasses, SettableForeground) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      SyncHarness().use { h ->
        val passes = GatedPasses()
        val foreground = SettableForeground(visible = true)
        val state = SyncStateSource(h.artifacts, h.status, h.board, h.flag, { h.requests }, scope, h.log)
        val engine = SyncEngine({ passes }, h.work, foreground, h.pairing, h.meta, state, { h.session(SettableForeground(), scope) }, scope, h.log)
        block(h, engine, passes, foreground)
      }
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun aRequestCreatedIsSentByTheExpeditedWorkNeverByTheApplicationScope() = engine { h, engine, passes, _ ->
    engine.request(SyncReason.REQUEST_CREATED)
    engine.request(SyncReason.SEND_NOW)

    until("send-now") { h.work.sendNows == 2 }
    delay(100)
    assertThat(passes.calls).isEmpty()
  }

  @Test
  fun withoutAPairedComputerARequestCreatedSchedulesNothing() = engine { h, engine, _, _ ->
    h.pairing.state.value = null

    engine.request(SyncReason.REQUEST_CREATED)
    delay(200)

    assertThat(h.work.sendNows).isEqualTo(0)
  }

  @Test
  fun syncNowRunsOnePassAndItsDownloadsAreAVisibleTransferOnlyWhenTheAppIsVisible() = engine { _, engine, passes, foreground ->
    engine.syncNow(SyncReason.SYNC_NOW)
    until("the pass") { passes.calls.size == 1 }
    foreground.state.value = false
    engine.syncNow(SyncReason.PULL_TO_REFRESH)
    until("the second pass") { passes.calls.size == 2 }
    engine.request(SyncReason.JOB_CHANGED)
    until("the third pass") { passes.calls.size == 3 }

    assertThat(passes.calls).containsExactly(
      SyncReason.SYNC_NOW to true, SyncReason.PULL_TO_REFRESH to false, SyncReason.JOB_CHANGED to false,
    ).inOrder()
  }

  @Test
  fun reasonsArrivingWhileAPassWaitsToStartAreFoldedIntoIt() = engine { _, engine, passes, _ ->
    passes.gate = CompletableDeferred()
    engine.request(SyncReason.CONNECTED)
    until("the first pass runs") { passes.calls.size == 1 }

    repeat(5) { engine.request(SyncReason.JOB_CHANGED) }
    delay(100)
    passes.gate.complete(Unit)
    until("the folded pass runs") { passes.calls.size == 2 }
    delay(200)

    assertThat(passes.calls).hasSize(2)
  }

  @Test
  fun aPassThatMissedTheComputerWhileSomethingWaitsLeavesAWifiWork() = engine { h, engine, passes, _ ->
    passes.outcome = PassOutcome.NotConnected(ComputerStatus.NotOnWifi)
    passes.pending = true

    engine.request(SyncReason.RESUME)

    until("the wifi work") { h.work.onWifi == 1 }
  }

  @Test
  fun theStateFollowsTheFilesOnTheirWay() = engine { h, engine, _, _ ->
    assertThat(engine.state.value).isEqualTo(SyncState.Idle)
    h.computer.addFinishedClip("Ordering coffee", video = false)

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    until("syncing") { engine.state.value is SyncState.Active }
    val active = engine.state.value as SyncState.Active
    assertThat(active.bringing).isEqualTo(1)
    assertThat(active.totalBytes).isGreaterThan(0L)

    h.downloader.run {}
    until("idle") { engine.state.value == SyncState.Idle }
    assertThat(engine.lastSynced.first()).isEqualTo(h.clock.now())
  }
}
