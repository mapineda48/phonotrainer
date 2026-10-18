package com.mapineda48.phonotrainer.core.sync

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SendReport
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The pass of architecture 7.1: its order, where it stops, and what it records. */
@RunWith(RobolectricTestRunner::class)
class PassTest {
  private fun sync(block: suspend TestScope.(SyncHarness) -> Unit) = runTest { SyncHarness().use { block(it) } }

  @Test
  fun aPassRunsOperationsLinksListingReferenceThenSchedulesTransfers() = sync { h ->
    h.computer.addFinishedClip("Ordering coffee")

    val outcome = h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(outcome).isEqualTo(PassOutcome.Done(listed = true))
    assertThat(h.events)
      .containsExactly("operations", "links", "library", "reconcile", "reference", "downloads", "uploads").inOrder()
    assertThat(h.status.checks).isEqualTo(1)
  }

  @Test
  fun theIndexStepRunsAfterTheReferenceAndBeforeTheTransfersAndTheBudgetComesLast() = sync { h ->
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()
    h.events.clear()
    // The recording indexer never writes the clip's index state, so the next pass finds it again.

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.events)
      .containsExactly("operations", "links", "library", "reconcile", "index:${id.value}", "uploads").inOrder()
    assertThat(h.indexer.reindexes).isEqualTo(1)
  }

  @Test
  fun aPassThatDoesNotReachTheComputerSendsAndAsksNothing() = sync { h ->
    h.status.statusState.value = ComputerStatus.NotReachable("Desk", null, false, ProbeOutcome.NoAnswer(3))

    val outcome = h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(outcome).isInstanceOf(PassOutcome.NotConnected::class.java)
    assertThat(h.events).isEmpty()
    assertThat(h.computer.requests).isEmpty()
  }

  @Test
  fun aDifferentComputerAtTheAddressGetsNothingAndNothingIsDeleted() = sync { h ->
    // F-21-AC5: the proof failed, so the probe says "A different computer answered".
    val id = h.computer.addFinishedClip("Ordering coffee")
    h.syncAndDownload()
    h.events.clear()
    h.computer.deleteJob(id)
    h.status.statusState.value = ComputerStatus.DifferentComputer("Desk")

    repeat(3) { h.orchestrator.pass(SyncReason.SYNC_NOW) }

    assertThat(h.events).isEmpty()
    assertThat(h.clips.get(id.value)).isNotNull()
  }

  @Test
  fun aBlockedOutboxStopsThePassBeforeAnyLinkOrListing() = sync { h ->
    h.sender.operations = SendReport(sent = 0, refused = 0, transient = 0, blocked = true)

    assertThat(h.orchestrator.pass(SyncReason.SYNC_NOW)).isEqualTo(PassOutcome.Blocked)
    assertThat(h.events).containsExactly("operations")
  }

  @Test
  fun anOpenChannelProvesTheComputerAliveSoThePassSkipsItsProbe() = sync { h ->
    h.channel.state.value = true

    h.orchestrator.pass(SyncReason.JOB_CHANGED)

    assertThat(h.status.checks).isEqualTo(0)
    assertThat(h.events).contains("library")
  }

  @Test
  fun aPassWithoutAPairedComputerDoesNothing() = sync { h ->
    h.pairing.state.value = null

    assertThat(h.orchestrator.pass(SyncReason.SYNC_NOW)).isEqualTo(PassOutcome.NotPaired)
    assertThat(h.events).isEmpty()
  }

  @Test
  fun theReferenceIsCachedOnlyOnceTheRepositoryAcceptedItsBody() = sync { h ->
    // The phone holds an older reference than the computer's; the computer's body is refused at first.
    h.reference.state.value = h.reference.state.value.copy(apiVersion = h.computer.apiVersion - 1)
    h.reference.onReplace = { _, _ -> null }

    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.events.count { it == "reference" }).isEqualTo(2)
    assertThat(h.meta.referenceApiVersion()).isNull()

    h.reference.onReplace = { _, apiVersion -> h.reference.state.value.copy(apiVersion = apiVersion) }
    h.orchestrator.pass(SyncReason.SYNC_NOW)
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.events.count { it == "reference" }).isEqualTo(3)
    assertThat(h.meta.referenceApiVersion()).isEqualTo(h.computer.apiVersion)
  }

  @Test
  fun aCompleteListingRecordsWhenItWasSyncedAndFromWhichLibrary() = sync { h ->
    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.meta.lastListingAt()).isEqualTo(h.clock.now())
    assertThat(h.meta.lastListing()).isEqualTo(h.computer.serverId.value to h.computer.libraryId.value)
    assertThat(h.db.syncMetaDao().get(SyncMeta.LAST_LISTING_AT)).isNotNull()
  }

  @Test
  fun reachingTheComputerStartsTheWifiRetryDelayAgain() = sync { h ->
    h.meta.setWifiBackoffMs(7_200_000)

    h.orchestrator.pass(SyncReason.SYNC_NOW)

    assertThat(h.meta.wifiBackoffMs()).isEqualTo(0)
  }

  @Test
  fun aRefusedListingPausesTheQueueOnlyForTheBlockingCodes() = sync { h ->
    h.computer.revokeDevice()

    assertThat(h.orchestrator.pass(SyncReason.SYNC_NOW)).isEqualTo(PassOutcome.Blocked)

    h.events.clear()
    h.computer.failNext("/api/phone/library", 503)
    assertThat(h.orchestrator.pass(SyncReason.SYNC_NOW)).isEqualTo(PassOutcome.Done(listed = false))
    assertThat(h.events).doesNotContain("reconcile")
  }
}
