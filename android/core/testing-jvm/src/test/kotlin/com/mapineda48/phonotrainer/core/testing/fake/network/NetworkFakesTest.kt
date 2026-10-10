package com.mapineda48.phonotrainer.core.testing.fake.network

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NetworkFakesTest {
  private val address = HostPort("192.0.2.10", 8000)

  @Test
  fun allowedPeersRecordsCandidatesAndKeepsTheCurrentAddress() = runTest {
    val peers = FakeAllowedPeers()

    val answer = peers.withCandidate(address) { "checked" }
    peers.setCurrent(address)

    assertThat(answer).isEqualTo("checked")
    assertThat(peers.candidates).containsExactly(address)
    assertThat(peers.current.value).isEqualTo(address)
  }

  @Test
  fun verifiedPeersForgetEverythingOnReset() {
    val peers = FakeVerifiedPeers()

    peers.markVerified(address, ServerId("server-a"))
    assertThat(peers.isVerified(address)).isTrue()
    assertThat(peers.serverIdOf(address)).isEqualTo(ServerId("server-a"))

    peers.reset()
    assertThat(peers.isVerified(address)).isFalse()
  }

  @Test
  fun clockOffsetIsServerTimeMinusPhoneTime() {
    val offset = FakeClockOffset(phoneNowS = 1_000)

    offset.update(serverTime = 1_090)

    assertThat(offset.offsetS.value).isEqualTo(90)
  }

  @Test
  fun addressPolicyFollowsItsRule() {
    val policy = FakeAddressPolicy()
    assertThat(policy.allowsCandidate("192.0.2.10")).isTrue()

    policy.allowed = { false }
    assertThat(policy.allowsCandidate("192.0.2.10")).isFalse()
  }
}
