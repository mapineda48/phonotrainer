package com.mapineda48.phonotrainer.core.connectivity.discovery

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.connectivity.support.Harness
import com.mapineda48.phonotrainer.core.connectivity.support.LoopbackOnly
import com.mapineda48.phonotrainer.core.connectivity.support.LoopbackTransport
import com.mapineda48.phonotrainer.core.connectivity.support.RecordingResponder
import com.mapineda48.phonotrainer.core.connectivity.pairing.DefaultPairingRepository
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.ProofMode
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import java.time.Duration
import javax.inject.Provider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test

/**
 * Discovery end to end on the loopback interface (architecture 6.1; spec F-02-AC1, F-02-AC10): real UDP responders
 * (the fake computers' and an impersonator's), the real transport, the `hello` confirmation of each answer, conflicts
 * and preselection. The broadcast targets are swapped for the responders' loopback ports and the schedule is shortened
 * ([LoopbackTransport]); what the search asked for is checked too.
 */
class DiscoverySearchTest {
  private val closeables = mutableListOf<AutoCloseable>()

  @After
  fun closeAll() = closeables.reversed().forEach { it.close() }

  private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

  private class Search(val harness: Harness, val repository: DefaultPairingRepository, val transport: LoopbackTransport)

  /** A pairing repository over [computers], whose discovery reaches the UDP responders on [ports]. */
  private fun TestScope.search(computers: List<FakeComputer>, ports: () -> List<Int>): Search {
    val transport = LoopbackTransport(ports)
    val harness = Harness(this, computers)
    harness.policy.allowed = LoopbackOnly::allowsCandidate
    val repository = DefaultPairingRepository(
      harness.client, harness.peers, harness.verified, harness.policy, harness.store, harness.monitor,
      DiscoveryClient(transport, LoopbackOnly), harness.status, Provider { harness.syncTrigger }, harness.work,
      harness.cleanup, harness.appVersion, harness.session, harness.clock, PhonoLog.NONE,
    )
    return Search(harness, repository, transport)
  }

  private suspend fun Search.run(): List<SearchState> = withContext(Dispatchers.Default) { repository.search().toList() }

  @Test
  fun aComputerWithPhoneAccessOnIsListedAndPreselectedWithinTheSearch() = runTest {
    val computer = FakeComputer(name = "Study desk").start().closing()
    val port = computer.startDiscoveryResponder()
    val search = search(listOf(computer)) { listOf(port) }

    val states = search.run()

    assertThat(states.first()).isEqualTo(SearchState.Searching)
    val found = states.last() as SearchState.Found
    assertThat(found.computers.map { it.serverId to it.address }).containsExactly(computer.serverId to computer.address)
    assertThat(found.preselected?.name).isEqualTo("Study desk")
    assertThat(found.conflict).isFalse()
    // The real search: the limited and the directed broadcast, 0/1/3 s, 10 s.
    val call = search.transport.calls.single()
    assertThat(call.broadcastTargets).containsExactly("255.255.255.255", "192.0.2.255")
    assertThat(call.schedule).isEqualTo(DiscoveryProtocol.SCHEDULE)
    assertThat(call.listenFor).isEqualTo(DiscoveryProtocol.LISTEN)
  }

  @Test
  fun threeProbesAnsweredThreeTimesAreOneEntryPerServerIdAndSource() = runTest {
    val computer = FakeComputer().start().closing()
    val port = computer.startDiscoveryResponder()
    // The same computer reached through two targets: six answers in all, one entry.
    val search = search(listOf(computer)) { listOf(port, port) }

    val found = search.run().last() as SearchState.Found

    assertThat(found.computers).hasSize(1)
    // One `hello` per entry, never one per datagram.
    assertThat(computer.requests.count { it.target.startsWith("/api/phone/hello") }).isEqualTo(1)
  }

  @Test
  fun twoComputersWithOneNameAreTwoRowsWithTheNoteAndNothingPreselected() = runTest {
    val first = FakeComputer(name = "PhonoTrainer computer", seed = 1).start().closing()
    val second = FakeComputer(name = "PhonoTrainer computer", seed = 2).start().closing()
    val ports = listOf(first.startDiscoveryResponder(), second.startDiscoveryResponder())
    val search = search(listOf(first, second)) { ports }

    val found = search.run().last() as SearchState.Found

    assertThat(found.computers.map { it.serverId }).containsExactly(first.serverId, second.serverId)
    assertThat(found.computers.all { it.conflict }).isTrue()
    assertThat(found.conflict).isTrue()
    assertThat(found.preselected).isNull()
  }

  @Test
  fun oneServerIdAnsweringFromTwoAddressesIsAConflictAndNeverMerged() = runTest {
    val computer = FakeComputer(name = "Study desk").start().closing()
    val impostor = computer.impersonate(ProofMode.WRONG).closing()
    val ports = listOf(computer.startDiscoveryResponder(), impostor.startDiscoveryResponder())
    val search = search(listOf(computer)) { ports }

    val found = search.run().last() as SearchState.Found

    assertThat(found.computers.map { it.address }).containsExactly(computer.address, impostor.address)
    assertThat(found.computers.map { it.serverId }.distinct()).containsExactly(computer.serverId)
    assertThat(found.conflict).isTrue()
    assertThat(found.preselected).isNull()
    // Identity only: neither answer was asked for a proof or sent a credential.
    assertThat(impostor.requests.map { it.target }).containsExactly("/api/phone/hello")
    assertThat(impostor.requests.none { "Authorization" in it.headers }).isTrue()
  }

  @Test
  fun anAnswerToAnotherProbeIsIgnored() = runTest {
    val rogue = RecordingResponder { _ ->
      """{"t":"phonotrainer-here","v":1,"nonce":"00000000000000000000000000000000","server_id":"srv_rogue","name":"Rogue","http_port":8000,"api_version":6}"""
        .toByteArray()
    }.closing()
    val search = search(listOf(FakeComputer())) { listOf(rogue.port) }

    val states = search.run()

    assertThat(rogue.arrivals).isNotEmpty()
    assertThat(states).containsExactly(SearchState.Searching, SearchState.NothingFound).inOrder()
  }

  @Test
  fun aComputerWithPhoneAccessOffOrNotApprovedStaysSilent() = runTest {
    val off = FakeComputer(seed = 3).start().closing().also { it.phoneAccessOff() }
    val suspended = FakeComputer(seed = 4).start().closing().also { it.lanForbidden() }
    val ports = listOf(off.startDiscoveryResponder(), suspended.startDiscoveryResponder())
    val search = search(listOf(off, suspended)) { ports }

    assertThat(search.run()).containsExactly(SearchState.Searching, SearchState.NothingFound).inOrder()
  }

  @Test
  fun offTheHomeNetworkTheSearchSaysNotOnWifiWithoutSendingAnything() = runTest {
    val search = search(listOf(FakeComputer())) { emptyList() }
    search.harness.monitor.state.value = FakeNetworkMonitor.mobileOnly()

    assertThat(search.run()).containsExactly(SearchState.Searching, SearchState.NotOnWifi).inOrder()
    assertThat(search.transport.calls).isEmpty()
  }

  @Test
  fun theSearchEndsWithinItsTenSeconds() = runTest {
    val search = search(listOf(FakeComputer())) { emptyList() }
    val started = System.nanoTime()

    search.run()

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
  }
}
