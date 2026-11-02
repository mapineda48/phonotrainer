package com.mapineda48.phonotrainer.core.connectivity.status

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.connectivity.support.Harness
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * "Last synced" of the paired computer (spec F-21, the data side of F-21-AC8, F-22's "last sync"; CR-WP08-1):
 * [PairedComputer.lastSync] is when the sync engine last reconciled a complete listing
 * (`SyncController.lastSynced`), as long as that listing belongs to the current pairing; the status machine is the
 * same with it or without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastSyncTest {
  private val computers = mutableListOf<FakeComputer>()

  @After
  fun closeComputers() = computers.forEach { it.close() }

  private fun computer(name: String = "Study desk", seed: Long = 7): FakeComputer =
    FakeComputer(name = name, seed = seed).also { it.rateLimits = false; computers += it }

  private fun TestScope.harness(vararg computers: FakeComputer = arrayOf(computer())) =
    Harness(this, computers.toList())

  private suspend fun Harness.pairNow(computer: FakeComputer): PairResult =
    pairing.pair(PairingTarget(computer.address, computer.serverId, computer.name), computer.newPairingCode(), "Pixel 9")

  private val connected = ComputerStatus.Connected("Study desk", 0, 0, false)

  @Test
  fun thePairedComputerWasLastSyncedWhenTheSyncEngineLastReconciledAListing() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    assertThat(h.status.paired.value?.serverId).isEqualTo(h.computer.serverId)
    assertThat(h.status.paired.value?.lastSync).isNull()

    val first = h.clock.now()
    h.lastListing.value = first
    runCurrent()
    assertThat(h.status.paired.value?.lastSync).isEqualTo(first)

    advanceTimeBy(600_000)
    val second = h.clock.now()
    h.lastListing.value = second
    runCurrent()
    assertThat(h.status.paired.value?.lastSync).isEqualTo(second)
    assertThat(h.status.status.value).isEqualTo(connected)
  }

  @Test
  fun aTimeTheSyncEngineKeptFromAnEarlierRunIsThereFromTheStart() = runTest {
    val h = harness()
    val kept = Instant.parse("2026-10-09T21:30:00Z")
    h.lastListing.value = kept
    h.seedPairing()
    h.status
    runCurrent()

    assertThat(h.status.paired.value?.lastSync).isEqualTo(kept)
  }

  @Test
  fun everyProbeThatRecordsTheComputerAsSeenKeepsTheSyncTime() = runTest {
    val h = harness()
    h.seedPairing()
    h.status
    runCurrent()
    val synced = h.clock.now()
    h.lastListing.value = synced
    runCurrent()
    val seen = h.status.paired.value?.lastSeen

    advanceTimeBy(121_000)
    runCurrent()

    val paired = h.status.paired.value
    assertThat(paired?.lastSeen).isGreaterThan(seen)
    assertThat(paired?.lastSync).isEqualTo(synced)
  }

  @Test
  fun aListingFromBeforeThePairingBelongsToAnEarlierOneAndGivesNoTime() = runTest {
    val h = harness()
    // Seeded pairings are from 2026-10-01T09:00:00Z; this listing was of whatever this phone was paired with before.
    h.lastListing.value = Instant.parse("2026-09-30T18:00:00Z")
    h.seedPairing()
    h.status
    runCurrent()
    assertThat(h.status.paired.value?.serverId).isEqualTo(h.computer.serverId)
    assertThat(h.status.paired.value?.lastSync).isNull()

    val synced = h.clock.now()
    h.lastListing.value = synced
    runCurrent()
    assertThat(h.status.paired.value?.lastSync).isEqualTo(synced)
  }

  @Test
  fun afterForgettingAComputerTheNextOneWaitsForItsOwnFirstListing() = runTest {
    val desk = computer()
    val kitchen = computer(name = "Kitchen", seed = 12)
    val h = harness(desk, kitchen)
    assertThat(h.pairNow(desk)).isInstanceOf(PairResult.Paired::class.java)
    runCurrent()
    advanceTimeBy(60_000)
    val deskSynced = h.clock.now()
    h.lastListing.value = deskSynced
    runCurrent()
    assertThat(h.status.paired.value?.lastSync).isEqualTo(deskSynced)

    assertThat(h.pairing.forget()).isEqualTo(ForgetResult.Forgotten)
    runCurrent()
    assertThat(h.status.paired.value).isNull()

    // The sync engine still remembers the desk's listing: it is not the kitchen computer's "Last synced".
    advanceTimeBy(60_000)
    assertThat(h.pairNow(kitchen)).isInstanceOf(PairResult.Paired::class.java)
    runCurrent()
    assertThat(h.status.paired.value?.serverId).isEqualTo(kitchen.serverId)
    assertThat(h.status.paired.value?.lastSync).isNull()

    advanceTimeBy(5_000)
    val kitchenSynced = h.clock.now()
    h.lastListing.value = kitchenSynced
    runCurrent()
    assertThat(h.status.paired.value?.lastSync).isEqualTo(kitchenSynced)
  }

  @Test
  fun aSyncEngineThatCannotSayWhenItSyncedLeavesThePairingAndTheStatusAlone() = runTest {
    val h = harness()
    h.lastSyncedSource = flow { throw IllegalStateException("sync_meta unreadable") }
    h.seedPairing()
    h.status
    runCurrent()

    assertThat(h.status.paired.value?.serverId).isEqualTo(h.computer.serverId)
    assertThat(h.status.paired.value?.lastSync).isNull()
    assertThat(h.status.status.value).isEqualTo(connected)
  }

  @Test
  fun aListingCountsFromTheMomentOfThePairingOn() {
    val pairedAt = Instant.parse("2026-10-10T09:00:00Z")
    val computer = PairedComputer(
      serverId = ServerId("srv_0123456789abcdef"), name = "Study desk", deviceId = DeviceId("dev_01"),
      deviceName = "Pixel 9", address = HostPort("192.0.2.10", 8000), libraryId = LibraryId("lib_01"),
      pairedAt = pairedAt, lastSeen = null, lastSync = Instant.parse("2026-10-11T09:00:00Z"), apiVersion = 6,
      capabilities = null,
    )

    assertThat(computer.syncedAt(null).lastSync).isNull()
    assertThat(computer.syncedAt(pairedAt.minusMillis(1)).lastSync).isNull()
    assertThat(computer.syncedAt(pairedAt).lastSync).isEqualTo(pairedAt)
    assertThat(computer.syncedAt(pairedAt.plusSeconds(90)).lastSync).isEqualTo(pairedAt.plusSeconds(90))
    assertThat(computer.syncedAt(pairedAt).copy(lastSync = null)).isEqualTo(computer.copy(lastSync = null))
  }
}
