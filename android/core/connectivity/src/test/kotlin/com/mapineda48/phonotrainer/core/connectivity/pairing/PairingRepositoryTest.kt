package com.mapineda48.phonotrainer.core.connectivity.pairing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.connectivity.support.Harness
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.ProofMode
import com.mapineda48.phonotrainer.core.testing.computer.RecordedCall
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Pairing, a typed address, a new address and "Forget this computer" against the fake computer (architecture 6.3,
 * spec 7.4 and F-02; work-plan WP-07 Proof: wrong, expired and locked codes, the rate limit with `Retry-After`,
 * "Connect again" with its proof, an unreadable old key, another server id, phone access off, too old through
 * `legacyHealth`, `useNewAddress` only with a valid proof, and "Forget" sending the unpair only when connected).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingRepositoryTest {
  private val computers = mutableListOf<FakeComputer>()

  @After
  fun closeComputers() = computers.forEach { it.close() }

  private fun computer(name: String = "Study desk", seed: Long = 11): FakeComputer =
    FakeComputer(name = name, seed = seed).also { computers += it }

  private val sent = mutableListOf<PairRequest>()

  private fun TestScope.harness(
    vararg extra: FakeComputer,
    client: ((ComputerClient) -> ComputerClient)? = null,
  ): Harness = Harness(this, listOf(computer()) + extra, clientOverride = { real ->
    val recording = object : ComputerClient by real {
      override suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant> {
        sent += request
        return real.pair(at, request)
      }
    }
    client?.invoke(recording) ?: recording
  }).also { it.computer.rateLimits = false }

  private fun Harness.target(computer: FakeComputer = this.computer) =
    PairingTarget(computer.address, computer.serverId, computer.name)

  private suspend fun Harness.pairNow(computer: FakeComputer = this.computer, name: String = "Pixel 9"): PairResult =
    pairing.pair(target(computer), computer.newPairingCode(), name)

  private fun RecordedCall.route(): String = "$method $path"

  // --- pairing -----------------------------------------------------------------------------------------------------

  @Test
  fun theCodeShownOnTheComputerPairsWithOneWriteProvesTheNewKeyAndConnects() = runTest {
    val h = harness()
    val result = h.pairNow()
    runCurrent()

    val paired = (result as PairResult.Paired).computer
    assertThat(paired.serverId).isEqualTo(h.computer.serverId)
    assertThat(paired.name).isEqualTo("Study desk")
    assertThat(paired.deviceName).isEqualTo("Pixel 9")
    assertThat(h.store.saved).hasSize(1)
    assertThat(h.computer.pairedDevices.map { it.name }).containsExactly("Pixel 9")
    assertThat(h.computer.requests.map { it.route() }.take(4)).containsExactly(
      "GET /api/phone/hello", "POST /api/phone/pair", "GET /api/phone/hello", "GET /api/phone/health",
    ).inOrder()
    assertThat(h.computer.requests[0].target).doesNotContain("challenge")
    assertThat(h.computer.requests[2].target).contains("challenge")
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.Connected("Study desk", 0, 0, false))
    assertThat(h.status.paired.value?.deviceId).isEqualTo(paired.deviceId)
    assertThat(h.syncTrigger.reasons).containsExactly(SyncReason.PAIRED)
    assertThat(h.peers.current.value).isEqualTo(h.computer.address)
    assertThat(h.peers.candidates).contains(h.computer.address)
    assertThat(sent.single().previousDeviceId).isNull()
  }

  @Test
  fun aWrongCodeStoresNothingAndAfterFiveTheComputerLocksTheCode() = runTest {
    val h = harness()
    val code = h.computer.newPairingCode("482913")
    repeat(5) {
      assertThat(h.pairing.pair(h.target(), "000000", "Pixel 9")).isEqualTo(PairResult.CodeInvalid)
    }
    assertThat(h.store.saved).isEmpty()

    assertThat(h.pairing.pair(h.target(), code, "Pixel 9")).isEqualTo(PairResult.CodeLocked)
    assertThat(h.store.saved).isEmpty()
    assertThat(h.status.paired.value).isNull()
  }

  @Test
  fun aCodeThatIsNotSixDigitsIsRefusedWithoutAskingTheComputer() = runTest {
    val h = harness()

    assertThat(h.pairing.pair(h.target(), "12345", "Pixel 9")).isEqualTo(PairResult.CodeInvalid)
    assertThat(h.pairing.pair(h.target(), "12a456", "Pixel 9")).isEqualTo(PairResult.CodeInvalid)
    assertThat(h.computer.requests).isEmpty()
  }

  @Test
  fun aCodeOlderThanFiveMinutesHasExpired() = runTest {
    val h = harness()
    val code = h.computer.newPairingCode()
    h.computer.clock.advance(Duration.ofMinutes(6))

    assertThat(h.pairing.pair(h.target(), code, "Pixel 9")).isEqualTo(PairResult.CodeExpired)
  }

  @Test
  fun tooManyAttemptsAreRateLimitedWithTheComputersRetryAfter() = runTest {
    val h = harness()
    h.computer.rateLimits = true
    h.computer.newPairingCode()
    val results = (1..11).map { h.pairing.pair(h.target(), "000000", "Pixel 9") }

    val limited = results.last() as PairResult.RateLimited
    assertThat(limited.retryAfterS).isNotNull()
    assertThat(limited.retryAfterS!!).isGreaterThan(0)
  }

  @Test
  fun connectAgainAfterARevokeReactivatesTheSameDeviceWithTheOldKeysProof() = runTest {
    val h = harness()
    val first = (h.pairNow() as PairResult.Paired).computer
    runCurrent()
    h.computer.revokeDevice()
    assertThat(h.status.checkNow()).isEqualTo(ComputerStatus.ConnectAgain)

    val again = (h.pairNow() as PairResult.Paired).computer
    runCurrent()

    assertThat(again.deviceId).isEqualTo(first.deviceId)
    assertThat(sent.last().previousDeviceId).isEqualTo(first.deviceId)
    assertThat(sent.last().previousTokenProof).matches("^[0-9a-f]{64}$")
    assertThat(h.computer.pairedDevices.map { it.deviceId to it.revoked }).containsExactly(first.deviceId to false)
    assertThat(h.status.status.value).isInstanceOf(ComputerStatus.Connected::class.java)
  }

  @Test
  fun anOldKeyThatCannotBeDecryptedPairsAsANewDeviceAndSaysNothingMore() = runTest {
    val h = harness()
    val first = (h.pairNow() as PairResult.Paired).computer
    h.computer.revokeDevice()
    h.store.unreadable = true

    val again = h.pairNow()
    h.store.unreadable = false
    runCurrent()

    assertThat((again as PairResult.Paired).computer.deviceId).isNotEqualTo(first.deviceId)
    assertThat(sent.last().previousDeviceId).isNull()
    assertThat(sent.last().previousTokenProof).isNull()
    assertThat(h.computer.pairedDevices).hasSize(2)
  }

  @Test
  fun anotherComputerIsRefusedWhileOneIsPaired() = runTest {
    val other = computer(name = "Kitchen", seed = 12)
    val h = harness(other)
    h.pairNow()

    val result = h.pairNow(other)

    assertThat(result).isEqualTo(PairResult.Failed(AppError.Computer.DifferentComputer))
    assertThat(h.store.value?.serverId).isEqualTo(h.computer.serverId)
    assertThat(other.pairedDevices).isEmpty()
  }

  @Test
  fun anAddressAnsweringWithAnotherServerIdThanTheOnePickedIsRefusedBeforeTheCodeIsSent() = runTest {
    val h = harness()
    val code = h.computer.newPairingCode()

    val result = h.pairing.pair(PairingTarget(h.computer.address, ServerId("srv_picked"), "Study desk"), code, "Pixel 9")

    assertThat(result).isEqualTo(PairResult.Failed(AppError.Computer.DifferentComputer))
    assertThat(h.computer.requests.map { it.route() }).containsExactly("GET /api/phone/hello")
    assertThat(h.store.saved).isEmpty()
  }

  @Test
  fun phoneAccessOffOrNotApprovedForThisNetworkIsSaidAndNothingIsStored() = runTest {
    val off = harness()
    off.computer.phoneAccessOff()
    assertThat(off.pairNow()).isEqualTo(PairResult.PhoneAccessOff)

    val suspended = harness()
    suspended.computer.lanForbidden()
    assertThat(suspended.pairNow()).isEqualTo(PairResult.PhoneAccessOff)
    assertThat(off.store.saved + suspended.store.saved).isEmpty()
  }

  @Test
  fun anOlderComputerIsTooOldThroughItsLegacyHealth() = runTest {
    val h = harness()
    h.computer.apiVersion = 5

    assertThat(h.pairNow()).isEqualTo(PairResult.Failed(AppError.Computer.ComputerTooOld))
    assertThat(h.computer.requests.map { it.route() }).containsExactly("GET /api/phone/hello", "GET /api/health").inOrder()
  }

  @Test
  fun aComputerThatNeedsANewerAppRefusesThePairing() = runTest {
    val h = harness()
    h.computer.minAppVersion = 2

    assertThat(h.pairNow()).isEqualTo(PairResult.AppTooOld)
    assertThat(h.store.saved).isEmpty()
  }

  @Test
  fun aPairingFileLostToDamageThenPairingTheSameComputerAgainKeepsEverything() = runTest {
    val h = harness()
    h.pairNow()
    runCurrent()
    h.store.damage()
    runCurrent()
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotConnected)

    val again = h.pairNow()
    runCurrent()

    assertThat(again).isInstanceOf(PairResult.Paired::class.java)
    assertThat(h.status.status.value).isInstanceOf(ComputerStatus.Connected::class.java)
    // Nothing was forgotten: no clip retagged, no request dropped, no work cancelled.
    assertThat(h.cleanup.forgotten).isEmpty()
    assertThat(h.work.cancelled).isEqualTo(0)
  }

  @Test
  fun aComputerThatCannotProveItselfWithTheNewKeyIsNotKept() = runTest {
    val h = harness(client = { real ->
      object : ComputerClient by real {
        override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> {
          val answer = real.hello(at, challengeFor)
          return if (challengeFor != null && answer is ApiResult.Ok) {
            ApiResult.Ok(answer.value.copy(proofValid = false), answer.status)
          } else {
            answer
          }
        }
      }
    })

    val result = h.pairNow()
    runCurrent()

    assertThat(result).isEqualTo(PairResult.Failed(AppError.Computer.DifferentComputer))
    assertThat(h.store.value).isNull()
    assertThat(h.peers.current.value).isNull()
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotConnected)
    assertThat(h.computer.requests.none { it.path == "/api/phone/health" }).isTrue()
  }

  @Test
  fun offTheHomeWifiNothingIsTried() = runTest {
    val h = harness()
    h.monitor.state.value = FakeNetworkMonitor.mobileOnly()

    assertThat(h.pairNow()).isEqualTo(PairResult.Failed(AppError.Computer.NotOnWifi))
    assertThat(h.computer.requests).isEmpty()
  }

  // --- a typed address (F-02 behaviour 4, F-02-AC4, F-02-AC7) -------------------------------------------------------

  @Test
  fun checkAsksForTheIdentityOnlyAndThenTheCodePairs() = runTest {
    val h = harness()

    val check = h.pairing.check(h.computer.address) as CheckResult.Ok
    assertThat(check.computer.serverId).isEqualTo(h.computer.serverId)
    assertThat(check.computer.name).isEqualTo("Study desk")
    assertThat(h.computer.requests.single().target).isEqualTo("/api/phone/hello")
    assertThat(h.computer.requests.single().hadAuthorization).isFalse()
    assertThat(h.store.saved).isEmpty()

    val code = h.computer.newPairingCode()
    val target = PairingTarget(check.computer.address, check.computer.serverId, check.computer.name)
    assertThat(h.pairing.pair(target, code, "Pixel 9")).isInstanceOf(PairResult.Paired::class.java)
  }

  @Test
  fun checkOfAnAddressWithoutPhoneAccessSaysSoAndStoresNothing() = runTest {
    val h = harness()
    h.computer.phoneAccessOff()
    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.PhoneAccessOff)
    h.computer.phoneAccessOn()
    h.computer.lanForbidden()
    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.PhoneAccessOff)

    assertThat(h.store.saved).isEmpty()
    assertThat(h.status.paired.value).isNull()
  }

  @Test
  fun checkRefusesAnAddressOutsideThePrivateRangesWithoutSendingAnything() = runTest {
    val h = harness()
    h.policy.allowed = { false }

    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.NotPrivate)
    assertThat(h.pairing.pair(h.target(), "482913", "Pixel 9")).isEqualTo(PairResult.Failed(AppError.Computer.Guarded))
    assertThat(h.computer.requests).isEmpty()
  }

  @Test
  fun checkTellsNoAnswerTooOldAndNotPhonoTrainerApart() = runTest {
    val h = harness()
    assertThat(h.pairing.check(HostPort(FakeComputer.HOST, 9))).isEqualTo(CheckResult.NoAnswer)

    h.computer.apiVersion = 5
    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.TooOld)

    h.computer.apiVersion = 6
    h.computer.failNext("/api/phone/hello", 404)
    h.computer.failNext("/api/health", 404)
    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.NotPhonoTrainer)

    h.monitor.state.value = FakeNetworkMonitor.offline()
    assertThat(h.pairing.check(h.computer.address)).isEqualTo(CheckResult.NoAnswer)
  }

  // --- a new address (spec 7.3 item 5, F-02-AC6) ---------------------------------------------------------------------

  @Test
  fun aTypedAddressThatProvesItIsThePairedComputerIsAcceptedWithoutPairingAgain() = runTest {
    val h = harness()
    h.seedPairing(address = HostPort(FakeComputer.HOST, 9))
    h.status
    runCurrent()
    assertThat(h.status.status.value).isInstanceOf(ComputerStatus.NotReachable::class.java)

    val result = h.pairing.useNewAddress(h.computer.address)
    runCurrent()

    assertThat(result).isInstanceOf(CheckResult.Ok::class.java)
    assertThat(h.store.value?.address).isEqualTo(h.computer.address)
    assertThat(h.status.status.value).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(h.computer.requests.none { it.path == "/api/phone/pair" }).isTrue()
  }

  @Test
  fun aTypedAddressWithoutAValidProofIsADifferentComputerAndChangesNothing() = runTest {
    val h = harness()
    h.seedPairing()
    val stored = h.store.value!!.address
    ProofMode.entries.forEach { mode ->
      val impostor = h.computer.impersonate(mode)

      assertThat(h.pairing.useNewAddress(impostor.address)).isEqualTo(CheckResult.DifferentComputer)
      assertThat(impostor.requests.map { it.path }).containsExactly("/api/phone/hello")
      assertThat(impostor.requests.none { it.hadAuthorization }).isTrue()
      impostor.close()
    }
    assertThat(h.store.value?.address).isEqualTo(stored)
  }

  @Test
  fun aNewAddressIsOnlyForAPairedPhone() = runTest {
    val h = harness()

    assertThat(h.pairing.useNewAddress(h.computer.address)).isEqualTo(CheckResult.DifferentComputer)
    assertThat(h.computer.requests).isEmpty()
  }

  // --- forget (architecture 6.3, F-22) -------------------------------------------------------------------------------

  @Test
  fun forgetWhileConnectedUnpairsAtOnceThenRemovesThePairingAndCancelsTheWork() = runTest {
    val h = harness()
    val paired = (h.pairNow() as PairResult.Paired).computer
    runCurrent()

    assertThat(h.pairing.forget()).isEqualTo(ForgetResult.Forgotten)
    runCurrent()

    assertThat(h.computer.requests.map { it.route() }).contains("DELETE /api/phone/devices/self")
    assertThat(h.computer.pairedDevices.single { it.deviceId == paired.deviceId }.revoked).isTrue()
    assertThat(h.store.value).isNull()
    assertThat(h.work.cancelled).isEqualTo(1)
    assertThat(h.cleanup.forgotten).containsExactly(paired.serverId)
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotConnected)
    assertThat(h.status.paired.value).isNull()
    assertThat(h.peers.current.value).isNull()
  }

  @Test
  fun forgetWhileNotConnectedNeverSendsTheUnpairAndNeverQueuesIt() = runTest {
    val h = harness()
    h.seedPairing()
    h.monitor.state.value = FakeNetworkMonitor.offline()
    h.status
    runCurrent()

    assertThat(h.pairing.forget()).isEqualTo(ForgetResult.Forgotten)
    h.monitor.state.value = FakeNetworkMonitor.wifi()
    runCurrent()

    assertThat(h.computer.requests.none { it.method == "DELETE" }).isTrue()
    assertThat(h.store.value).isNull()
    assertThat(h.work.cancelled).isEqualTo(1)
    assertThat(h.status.status.value).isEqualTo(ComputerStatus.NotConnected)
  }

  @Test
  fun anUnpairWithoutAnAnswerIsGivenUpAfterFiveSeconds() = runTest {
    val h = harness(client = { real ->
      object : ComputerClient by real {
        override suspend fun unpairSelf(): ApiResult<Unit> = awaitCancellation()
      }
    })
    h.pairNow()
    runCurrent()
    val started = currentTime

    assertThat(h.pairing.forget()).isEqualTo(ForgetResult.Forgotten)
    assertThat(currentTime - started).isEqualTo(5_000L)
    assertThat(h.store.value).isNull()
  }

  @Test
  fun aForgetThatCannotRemoveThePairingSaysSoAndKeepsIt() = runTest {
    val h = harness()
    h.seedPairing()
    h.store.failWrites = true

    assertThat(h.pairing.forget()).isEqualTo(ForgetResult.Failed(AppError.Storage.Io))
    assertThat(h.store.value).isNotNull()
    assertThat(h.cleanup.forgotten).isEmpty()
  }

  @Test
  fun theReactivationProofReproducesTheReferenceVector() {
    // server-contract 6.2 and WP-01's tests/phone_fixtures.py: the same token, code and proof.
    val key = java.security.MessageDigest.getInstance("SHA-256")
      .digest("pt1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8".toByteArray()) // hygiene: test-vector

    assertThat(ReactivationProof.of(key, "482913"))
      .isEqualTo("6c25019dd33f72685983eea58450f519b69da49f1b31857490345bdd61e497b7") // hygiene: test-vector
  }
}
