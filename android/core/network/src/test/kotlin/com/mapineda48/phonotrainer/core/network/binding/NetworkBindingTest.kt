package com.mapineda48.phonotrainer.core.network.binding

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.support.RecordingPlatform
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import java.net.SocketException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The client follows the home network (architecture 5.1, NFR-05, Android review M11), with a recording platform in
 * place of `Network.bindSocket`: every socket is bound to the network current when it is opened, a change of network
 * evicts the connection pool so the next request opens a socket on the new network, there is no traffic at all
 * without a Wi-Fi or Ethernet network, and a VPN that refuses the binding is its own outcome.
 */
class NetworkBindingTest {
  private val computer = FakeComputer().start()
  private val platform = RecordingPlatform()
  private val stack = TestStack(computer, platform = platform)

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  @Test
  fun aSocketOpenedAfterANetworkChangeIsBoundToTheNewNetworkAndThePoolIsEvicted() = runBlocking<Unit> {
    stack.binder.bind(NETWORK_A)
    val device = com.mapineda48.phonotrainer.core.domain.model.DeviceId(stack.pair().deviceId)
    stack.client.health()
    assertThat(platform.bound.map { it.first }.toSet()).containsExactly(NETWORK_A)
    assertThat(stack.pool.connectionCount()).isGreaterThan(0)

    stack.binder.bind(NETWORK_B)
    assertThat(stack.pool.connectionCount()).isEqualTo(0)
    val beforeB = platform.bound.size
    stack.client.hello(computer.address, device)
    stack.client.health()

    val onB = platform.bound.drop(beforeB)
    assertThat(onB).isNotEmpty()
    assertThat(onB.map { it.first }.toSet()).containsExactly(NETWORK_B)
  }

  @Test
  fun bindingTheSameNetworkAgainKeepsThePool() = runBlocking<Unit> {
    stack.binder.bind(NETWORK_A)
    stack.pair()
    stack.client.health()
    val pooled = stack.pool.connectionCount()

    stack.binder.bind(NETWORK_A)

    assertThat(stack.pool.connectionCount()).isEqualTo(pooled)
    assertThat(stack.verified.isVerified(computer.address)).isTrue()
  }

  @Test
  fun withoutAWifiNetworkNothingIsSent() = runBlocking<Unit> {
    val beforeAnyBind = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address) }
    stack.binder.bind(null)
    val noWifi = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address) }

    assertThat(beforeAnyBind).isEqualTo(ApiResult.Failed(AppError.Computer.NotOnWifi))
    assertThat(noWifi).isEqualTo(ApiResult.Failed(AppError.Computer.NotOnWifi))
    assertThat(computer.requests).isEmpty()
    assertThat(platform.bound).isEmpty()
  }

  @Test
  fun aVpnThatRefusesTheBindingIsItsOwnOutcome() = runBlocking<Unit> {
    stack.binder.bind(NETWORK_A)
    platform.failWith = SocketException("Binding socket to network 101 failed: EPERM (Operation not permitted)")

    val hello = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address) }

    assertThat(hello).isEqualTo(ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.VpnBlocks)))
    assertThat(computer.requests).isEmpty()
  }

  private companion object {
    const val NETWORK_A = 101L
    const val NETWORK_B = 102L
  }
}
