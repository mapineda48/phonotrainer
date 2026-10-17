package com.mapineda48.phonotrainer.core.connectivity.status

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.connectivity.support.Harness
import com.mapineda48.phonotrainer.core.connectivity.support.RelayingClient
import com.mapineda48.phonotrainer.core.connectivity.support.TimedTransport
import com.mapineda48.phonotrainer.core.connectivity.support.relayDatagram
import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.ProofMode
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Re-discovery accepts an address only when it is the one address that proves it is the paired computer (spec 7.3
 * item 5, 7.4, D-37; architecture 6.1, 6.2 rule 2). A host on the Wi-Fi that answers discovery with the paired server
 * id and passes the phone's challenge on to the real computer gets a valid proof; it can only do so while the real
 * computer answers too, so two proving addresses are an impostor and neither is accepted. Virtual time measures when
 * the search settles: one second after the last probe at the earliest, one second after the first proof, or at the
 * end of the 10 s window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RediscoveryTest {
  private val computers = mutableListOf<FakeComputer>()
  private val relay = HostPort(FakeComputer.HOST, 10)
  private val transport = TimedTransport()

  @After
  fun closeComputers() = computers.forEach { it.close() }

  private class Setup(val harness: Harness, val computer: FakeComputer, val relaying: RelayingClient)

  private fun TestScope.setup(): Setup {
    val computer = FakeComputer(name = "Study desk", seed = 7).also { it.rateLimits = false; computers += it }
    var relaying: RelayingClient? = null
    val harness = Harness(this, listOf(computer), clientOverride = { inner ->
      RelayingClient(inner, relay, computer).also { relaying = it }
    }, transport = transport)
    harness.seedPairing(address = HostPort(FakeComputer.HOST, 9))
    return Setup(harness, computer, relaying!!)
  }

  private fun Setup.real(): (ByteArray) -> Datagram? =
    { probe -> computer.answerProbe(probe)?.let { Datagram(it, FakeComputer.HOST, Harness.DISCOVERY_PORT) } }

  private fun Setup.relayed(): (ByteArray) -> Datagram? = { probe -> relayDatagram(probe, computer, relay) }

  private suspend fun Setup.find(): RediscoveryResult =
    harness.rediscovery.find(harness.store.value!!.toTarget(), FakeNetworkMonitor.wifi())

  @Test
  fun theOnlyAddressThatProvesIsAcceptedOnceEveryProbeCouldHaveBeenAnswered() = runTest {
    val setup = setup()
    transport.at(0, setup.real())

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Found(setup.computer.address))
    assertThat(currentTime).isEqualTo(4_000L)
  }

  @Test
  fun aRelayThatAnswersFirstIsNotAcceptedWhileTheRealComputerAnswersToo() = runTest {
    val setup = setup()
    transport.at(0, setup.relayed())
    transport.at(500, setup.real())

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Impostor)
    // The relay's proof verified (it came from the real computer): only the second address showed what it was.
    assertThat(setup.relaying.asked).containsExactly(relay, setup.computer.address).inOrder()
    assertThat(setup.harness.guarded.refused).isEmpty()
  }

  @Test
  fun aSecondAddressThatProvesBeforeTheSearchSettlesMakesItAnImpostor() = runTest {
    val setup = setup()
    transport.at(0, setup.real())
    transport.at(3_500, setup.relayed())

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Impostor)
  }

  @Test
  fun aLateFirstProofIsAcceptedOneSecondLaterWhenNoOtherAddressProves() = runTest {
    val setup = setup()
    transport.at(6_000, setup.real())

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Found(setup.computer.address))
    assertThat(currentTime).isEqualTo(7_000L)
  }

  @Test
  fun anAddressWithoutAValidProofIsAnImpostorAtTheEndOfTheWindowAndNothingIsTheEndToo() = runTest {
    val setup = setup()
    val impostor = setup.computer.impersonate(ProofMode.WRONG)
    transport.at(0) { probe -> impostor.answerProbe(probe)?.let { Datagram(it, FakeComputer.HOST, Harness.DISCOVERY_PORT) } }

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Impostor)
    assertThat(currentTime).isEqualTo(10_000L)
    impostor.close()
  }

  @Test
  fun nothingAnsweringWithThePairedIdIsNothingFoundWhenTheWindowEnds() = runTest {
    val setup = setup()

    assertThat(setup.find()).isEqualTo(RediscoveryResult.NothingFound)
    assertThat(currentTime).isEqualTo(10_000L)
  }

  @Test
  fun aRevokedPhonesOnlyProvingAddressIsRevoked() = runTest {
    val setup = setup()
    setup.computer.revokeDevice()
    transport.at(0, setup.real())

    assertThat(setup.find()).isEqualTo(RediscoveryResult.Revoked)
  }
}
