package com.mapineda48.phonotrainer.core.network.signing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.support.RecordingPlatform
import com.mapineda48.phonotrainer.core.network.support.TestStack
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.ProofMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * Identity proof first, then signed requests (architecture 5.3, security review H-1, M-1): only an address whose
 * `hello` proof verifies for the paired computer is ever sent a signed request; `hello`, `pair` and a request to any
 * unverified address carry no `Authorization`; a `401 clock_skew` re-syncs the offset and the request is sent once more.
 */
class ProofAndSigningTest {
  private val computer = FakeComputer().start()
  private val stack = TestStack(computer)

  @After
  fun close() {
    stack.close()
    computer.close()
  }

  @Test
  fun helloAndPairNeverCarryACredentialAndSignedRoutesAlwaysDo() = runBlocking<Unit> {
    val device = stack.pair()
    stack.client.health()
    stack.client.library()

    val requests = computer.requests
    requests.filter { it.path == "/api/phone/hello" || it.path == "/api/phone/pair" }.forEach {
      assertThat(it.hadAuthorization).isFalse()
    }
    requests.filter { it.path == "/api/phone/health" || it.path == "/api/phone/library" }.forEach {
      assertThat(it.signedBy).isEqualTo(DeviceId(device.deviceId))
    }
    assertThat(requests.map { it.path })
      .containsExactly("/api/phone/hello", "/api/phone/pair", "/api/phone/hello", "/api/phone/health", "/api/phone/library")
      .inOrder()
  }

  @Test
  fun aSignedRouteIsNeverSentToAnAddressThatHasNotProvedItself() = runBlocking<Unit> {
    stack.pair()
    stack.verified.reset()

    val outcome = stack.client.library()

    assertThat(outcome).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(computer.requests.map { it.path }).doesNotContain("/api/phone/library")
  }

  @Test
  fun anImpersonatorNeverReceivesASignedRequest() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    for (mode in ProofMode.entries) {
      computer.impersonate(mode).use { impostor ->
        stack.peers.setCurrent(impostor.address)

        val hello = stack.client.hello(impostor.address, device) as ApiResult.Ok
        val library = stack.client.library()
        val upload = stack.client.uploadStatus(com.mapineda48.phonotrainer.core.domain.model.RequestId(UUID_ONE))

        assertThat(hello.value.serverId).isEqualTo(computer.serverId)
        assertThat(hello.value.proofValid).isFalse()
        assertThat(stack.verified.isVerified(impostor.address)).isFalse()
        assertThat(library).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(upload).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
        assertThat(impostor.requests.map { it.path }).containsExactly("/api/phone/hello")
        assertThat(impostor.requests.none { it.hadAuthorization }).isTrue()
      }
    }
  }

  @Test
  fun aWrongProofAtAnAddressThatWasVerifiedLeavesItUnverified() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    assertThat(stack.verified.isVerified(computer.address)).isTrue()

    computer.changeServerId(forgetDevices = false)
    val hello = stack.client.hello(computer.address, device) as ApiResult.Ok

    assertThat(hello.value.proofValid).isFalse()
    assertThat(stack.verified.isVerified(computer.address)).isFalse()
    assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
  }

  @Test
  fun aValidProofFromARevokedDeviceDoesNotVerifyTheAddress() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    computer.revokeDevice()

    val hello = stack.client.hello(computer.address, device) as ApiResult.Ok

    assertThat(hello.value.proofValid).isTrue()
    assertThat(hello.value.revoked).isTrue()
    assertThat(stack.verified.isVerified(computer.address)).isFalse()
  }

  @Test
  fun anUnreadableCredentialFailsTheProofAndEverySignature() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    stack.store.unreadable = true

    val library = stack.client.library()
    val hello = stack.client.hello(computer.address, device) as ApiResult.Ok

    assertThat(library).isEqualTo(ApiResult.Failed(AppError.Computer.CredentialRejected))
    assertThat(hello.value.proofValid).isFalse()
  }

  @Test
  fun aClockSkewTeachesTheOffsetAndTheRequestIsSentOnceMore() = runBlocking<Unit> {
    stack.pair()
    val before = stack.clockOffset.offsetS.value
    computer.clockSkew(SKEW_S)

    val library = stack.client.library()

    assertThat(library).isInstanceOf(ApiResult.Ok::class.java)
    val sent = computer.requests.filter { it.path == "/api/phone/library" }
    assertThat(sent.map { it.status }).containsExactly(401, 200).inOrder()
    assertThat(stack.clockOffset.offsetS.value - before).isIn(com.google.common.collect.Range.closed(SKEW_S - 2, SKEW_S + 2))
    // The learned offset is written back to the pairing store.
    withTimeout(2_000) { while (stack.store.current()!!.clockOffsetS != stack.clockOffset.offsetS.value) delay(10) }
  }

  @Test
  fun aSecondClockSkewIsRefusedRatherThanRetriedForever() = runBlocking<Unit> {
    stack.pair()
    computer.failNext("/api/phone/library", 401, "clock_skew", times = 3)

    val library = stack.client.library() as ApiResult.Refused

    assertThat(library.status).isEqualTo(401)
    assertThat(library.code).isEqualTo("clock_skew")
    assertThat(computer.requests.count { it.path == "/api/phone/library" }).isAtMost(2)
  }

  @Test
  fun aNetworkChangeForgetsTheVerifiedAddress() {
    val platform = RecordingPlatform()
    TestStack(computer, platform = platform).use { bound ->
      runBlocking {
        bound.binder.bind(NETWORK_A)
        val device = DeviceId(bound.pair().deviceId)
        assertThat(bound.verified.isVerified(computer.address)).isTrue()

        bound.binder.bind(NETWORK_B)
        assertThat(bound.verified.isVerified(computer.address)).isFalse()
        assertThat(bound.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))

        bound.binder.bind(NETWORK_B)
        assertThat((bound.client.hello(computer.address, device) as ApiResult.Ok).value.proofValid).isTrue()
        assertThat(bound.client.library()).isInstanceOf(ApiResult.Ok::class.java)
      }
    }
  }

  @Test
  fun anAddressChangeForgetsTheVerifiedAddressEvenWhenItMovesBack() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    val elsewhere = HostPort(FakeComputer.HOST, computer.address.port + 1)
    assertThat(stack.verified.isVerified(computer.address)).isTrue()

    stack.peers.setCurrent(elsewhere)
    stack.peers.setCurrent(computer.address)
    val library = computer.requests.count { it.path == "/api/phone/library" }

    assertThat(stack.verified.isVerified(computer.address)).isFalse()
    assertThat(stack.client.library()).isEqualTo(ApiResult.Failed(AppError.Computer.Guarded))
    assertThat(computer.requests.count { it.path == "/api/phone/library" }).isEqualTo(library)

    assertThat((stack.client.hello(computer.address, device) as ApiResult.Ok).value.proofValid).isTrue()
    assertThat(stack.client.library()).isInstanceOf(ApiResult.Ok::class.java)
  }

  @Test
  fun aCandidateThatProvedItselfStaysVerifiedWhenItBecomesTheCurrentAddress() = runBlocking<Unit> {
    val device = DeviceId(stack.pair().deviceId)
    stack.peers.setCurrent(null)
    assertThat(stack.verified.isVerified(computer.address)).isFalse()

    // Re-discovery (architecture 6.2 rule 2): the proof at the candidate, then the candidate becomes the address.
    val proof = stack.peers.withCandidate(computer.address) { stack.client.hello(computer.address, device) }
    assertThat((proof as ApiResult.Ok).value.proofValid).isTrue()
    stack.peers.setCurrent(computer.address)

    assertThat(stack.verified.isVerified(computer.address)).isTrue()
    assertThat(stack.client.library()).isInstanceOf(ApiResult.Ok::class.java)
  }

  private companion object {
    const val SKEW_S = 1_000L
    const val NETWORK_A = 101L
    const val NETWORK_B = 102L
    const val UUID_ONE = "00000000-0000-4000-8000-000000000001"
  }
}
