package com.mapineda48.phonotrainer.core.connectivity.status

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import org.junit.Test

/** The verdicts of a probe's answers (architecture 6.2 rules 3 and 5; server-contract 6.2 and 8.1). */
class ProbeVerdictsTest {
  private val target = ProbeTarget(ServerId("srv_example1"), "Study desk", DeviceId("dev_example1"), HostPort("192.0.2.10", 8000))

  private fun identity(
    phoneAccess: Boolean = true,
    serverId: String? = "srv_example1",
    apiVersion: Int = 6,
    proofValid: Boolean? = true,
    revoked: Boolean? = false,
  ) = ApiResult.Ok(ComputerIdentity(phoneAccess, serverId?.let(::ServerId), "Study desk", apiVersion, 1L, proofValid, revoked), 200)

  private fun health(serverId: String = "srv_example1", apiVersion: Int = 6, minAppVersion: Int = 1) = ApiResult.Ok(
    ComputerHealth(
      ServerId(serverId), "Study desk", LibraryId("lib_example1"), apiVersion, minAppVersion, "0.1.0", 1L,
      DeviceId("dev_example1"), 0, 0, false, Capabilities(true, null, true, false, false, false, false, 1L, 1L, 1L, 1L),
    ),
    200,
  )

  private fun refused(status: Int, code: String, retryAfterS: Int? = null) =
    ApiResult.Refused(status, code, "detail", retryAfterS = retryAfterS)

  @Test
  fun helloProvesThePairedComputerOnlyWithAValidUnrevokedProofForItsServerId() {
    assertThat(ProbeVerdicts.hello(identity(), target)).isEqualTo(ProbeVerdicts.Hello.Proven)
    assertThat(ProbeVerdicts.hello(identity(proofValid = false), target)).isEqualTo(ProbeVerdicts.Hello.ProofFailed)
    assertThat(ProbeVerdicts.hello(identity(proofValid = null), target)).isEqualTo(ProbeVerdicts.Hello.ProofFailed)
    assertThat(ProbeVerdicts.hello(identity(serverId = "srv_example2"), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.DifferentComputer))
    assertThat(ProbeVerdicts.hello(identity(revoked = true), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.ConnectAgain))
  }

  @Test
  fun helloSaysPhoneAccessIsOffOrNotApprovedAndOldVersionsAreTooOld() {
    assertThat(ProbeVerdicts.hello(identity(phoneAccess = false, serverId = null, proofValid = null), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff)))
    assertThat(ProbeVerdicts.hello(refused(403, "lan_forbidden"), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff)))
    assertThat(ProbeVerdicts.hello(refused(403, "phone_access_off"), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff)))
    assertThat(ProbeVerdicts.hello(identity(apiVersion = 5), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.ComputerTooOld))
    assertThat(ProbeVerdicts.hello(refused(404, ""), target)).isEqualTo(ProbeVerdicts.Hello.NeedLegacy)
  }

  @Test
  fun aRateLimitedHelloKeepsItsRetryAfterAndOtherRefusalsAreNotPhonoTrainer() {
    assertThat(ProbeVerdicts.hello(refused(429, "rate_limited", retryAfterS = 12), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.Refused, 12)))
    assertThat(ProbeVerdicts.hello(refused(503, ""), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.Refused)))
    assertThat(ProbeVerdicts.hello(refused(400, "invalid_request"), target))
      .isEqualTo(ProbeVerdicts.Hello.Report(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer)))
  }

  @Test
  fun aLegacyHealthWithAnOlderApiVersionIsComputerTooOldAndAnythingElseIsNotPhonoTrainer() {
    assertThat(ProbeVerdicts.legacy(ApiResult.Ok(5, 200))).isEqualTo(ProbeReport.ComputerTooOld)
    assertThat(ProbeVerdicts.legacy(ApiResult.Ok(6, 200))).isEqualTo(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer))
    assertThat(ProbeVerdicts.legacy(refused(404, ""))).isEqualTo(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer))
    assertThat(ProbeVerdicts.legacy(ApiResult.Failed(AppError.Computer.Malformed)))
      .isEqualTo(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer))
  }

  @Test
  fun healthConnectsOnlyTheSameComputerWithVersionsThisAppSupports() {
    assertThat(ProbeVerdicts.health(health(), target, appVersionCode = 1)).isInstanceOf(ProbeReport.Success::class.java)
    assertThat(ProbeVerdicts.health(health(serverId = "srv_example2"), target, 1)).isEqualTo(ProbeReport.DifferentComputer)
    assertThat(ProbeVerdicts.health(health(apiVersion = 5), target, 1)).isEqualTo(ProbeReport.ComputerTooOld)
    assertThat(ProbeVerdicts.health(health(minAppVersion = 2), target, 1)).isEqualTo(ProbeReport.AppTooOld)
  }

  @Test
  fun healthRefusalsMapToTheirStates() {
    assertThat(ProbeVerdicts.health(refused(401, "credential_rejected"), target, 1)).isEqualTo(ProbeReport.ConnectAgain)
    assertThat(ProbeVerdicts.health(refused(426, "app_too_old"), target, 1)).isEqualTo(ProbeReport.AppTooOld)
    assertThat(ProbeVerdicts.health(refused(403, "lan_forbidden"), target, 1))
      .isEqualTo(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))
    assertThat(ProbeVerdicts.health(refused(401, "clock_skew"), target, 1)).isEqualTo(ProbeReport.Failure(ProbeOutcome.Reset))
    assertThat(ProbeVerdicts.health(refused(429, "rate_limited", 30), target, 1))
      .isEqualTo(ProbeReport.Failure(ProbeOutcome.Refused, 30))
  }

  @Test
  fun callsWithoutAnUsableAnswerKeepTheirReason() {
    assertThat(ProbeVerdicts.failure(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(10))))
      .isEqualTo(ProbeReport.Failure(ProbeOutcome.NoAnswer(10)))
    assertThat(ProbeVerdicts.failure(AppError.Computer.Unreachable(ProbeOutcome.VpnBlocks)))
      .isEqualTo(ProbeReport.Failure(ProbeOutcome.VpnBlocks))
    assertThat(ProbeVerdicts.failure(AppError.Computer.CredentialRejected)).isEqualTo(ProbeReport.ConnectAgain)
    assertThat(ProbeVerdicts.failure(AppError.Computer.Malformed)).isEqualTo(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer))
    assertThat(ProbeVerdicts.failure(AppError.Computer.Guarded)).isEqualTo(ProbeReport.Failure(ProbeOutcome.Reset))
    assertThat(ProbeVerdicts.failure(AppError.Computer.NotOnWifi)).isEqualTo(ProbeReport.Failure(ProbeOutcome.NoRoute))
  }
}
