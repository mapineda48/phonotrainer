package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import com.mapineda48.phonotrainer.core.testing.computer.JobStage
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Live progress and the foreground session (architecture 5.5, 7.2, 7.6; F-03-AC5; QT-10): the job channel updates the
 * catalogue within an event's arrival and asks for a pass on a new revision, `deleted` events remove clips, a lost
 * channel tells the probe machine and opens again; the session's triggers, the periodic work only while paired, and the
 * Wi-Fi delay starting again on a network change.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSessionTest {
  private suspend fun until(what: String, condition: suspend () -> Boolean) {
    withContext(Dispatchers.Default) { withTimeout(10_000) { while (!condition()) delay(10) } }
    check(condition()) { what }
  }

  private fun live(block: suspend (SyncHarness, CoroutineScope) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      SyncHarness().use { block(it, scope) }
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun jobEventsUpdateTheCatalogueAtOnceAndANewRevisionAsksForAPass() = live { h, scope ->
    val id = h.computer.addJob(JobSeed(status = JobStatus.RUNNING, title = "Analyzing"))
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    scope.launch { h.channelSession.run() }
    until("the channel opens") { h.channelSession.open.value }

    h.computer.progress(id, JobStage.PHONES, 80)
    until("the progress arrives") { h.clip(id).percent == 80 }
    assertThat(h.clip(id).stage).isEqualTo("PHONES")
    assertThat(h.reconciler.jobs.last().percent).isEqualTo(80)
    assertThat(h.trigger.reasons).doesNotContain(SyncReason.JOB_CHANGED)

    h.computer.finishJob(id, title = "Ordering coffee")
    until("the finished job arrives") { h.clip(id).status == "DONE" }
    until("a pass is asked for") { SyncReason.JOB_CHANGED in h.trigger.reasons }
    assertThat(h.liveness.alive).isGreaterThan(0)
  }

  @Test
  fun aDeletedEventRemovesTheClipOrWaitsForItsScreenToClose() = live { h, scope ->
    val open = h.computer.addFinishedClip("Open in the feed")
    val closed = h.computer.addFinishedClip("Not open")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.leases.open += open
    scope.launch { h.channelSession.run() }
    until("the channel opens") { h.channelSession.open.value }

    h.computer.deleteJob(open)
    h.computer.deleteJob(closed)

    until("the closed clip goes") { h.clips.get(closed.value) == null }
    until("the open one waits") { h.clips.get(open.value)?.deletedOnComputer == true }
    h.leases.release(open)
    h.removal.applyDeferred()
    assertThat(h.clips.get(open.value)).isNull()
    assertThat(h.reconciler.deleted).containsExactly(open, closed)
  }

  @Test
  fun aLostChannelTellsTheProbeMachineAndOpensAgain() = live { h, scope ->
    scope.launch { h.channelSession.run() }
    until("the channel opens") { h.channelSession.open.value }

    h.computer.dropChannel()

    until("the loss is told") { h.liveness.lost == 1 }
    until("the channel opens again") { h.channelSession.open.value && h.computer.openChannels == 1 }
  }

  @Test
  fun theSessionSyncsOnStartResumeAndConnectionAndKeepsThePeriodicWorkOnlyWhilePaired() = live { h, scope ->
    val foreground = SettableForeground(visible = false)
    h.status.statusState.value = ComputerStatus.Offline
    h.session(foreground, scope).start()

    foreground.state.value = true
    until("app start") { h.trigger.reasons.contains(SyncReason.APP_START) }
    foreground.state.value = false
    // A state flow keeps only the latest value: the session must see the app leave before it comes back.
    delay(200)
    foreground.state.value = true
    until("resume") { h.trigger.reasons.contains(SyncReason.RESUME) }

    h.status.statusState.value = SyncHarness.CONNECTED
    until("connected") { h.trigger.reasons.contains(SyncReason.CONNECTED) }
    until("the channel opens in the foreground") { h.computer.openChannels == 1 }

    foreground.state.value = false
    until("the channel closes in the background") { h.computer.openChannels == 0 }

    assertThat(h.work.periodic).isTrue()
    h.pairing.state.value = null
    until("no periodic work without a computer") { !h.work.periodic }
  }

  @Test
  fun aNetworkChangeSeenInTheForegroundStartsTheWifiDelayAgain() = live { h, scope ->
    val foreground = SettableForeground(visible = true)
    h.meta.setWifiBackoffMs(3_600_000)
    h.session(foreground, scope).start()

    h.network.state.value = h.network.state.value.copy(kind = NetworkKind.ETHERNET, networkHandle = 77)

    until("the delay starts again") { h.meta.wifiBackoffMs() == 0L }
  }

  @Test
  fun aScreenClosingAppliesTheRemovalThatWaitedForIt() = live { h, scope ->
    val id = h.computer.addFinishedClip("Open")
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.leases.open += id
    h.removal.deletedOnComputer(listOf(id.value))
    h.session(SettableForeground(), scope).start()
    delay(100)

    h.leases.release(id)

    until("the clip goes") { h.clips.get(id.value) == null }
  }

  @StartupOrder(1)
  class Marker : StartupTask {
    @Volatile var ran: Instant? = null

    override suspend fun run(processStart: Instant) {
      ran = processStart
    }
  }

  @Test
  fun theSessionStartsTheStartupCheckOnce() = live { h, scope ->
    val marker = Marker()
    val session = h.session(SettableForeground(), scope, setOf(marker))

    session.start()
    session.start()

    until("the check ran") { marker.ran != null }
  }
}
