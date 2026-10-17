package com.mapineda48.phonotrainer.core.connectivity.status

import com.mapineda48.phonotrainer.core.connectivity.ConnectivityRules
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.network.api.ApiResult

/** The paired computer as a probe needs it: whom to expect at which address, and this phone's device id. */
internal data class ProbeTarget(val serverId: ServerId, val name: String, val deviceId: DeviceId, val address: HostPort)

/** How one probe ended (architecture 6.2 rules 3 to 5). */
internal sealed interface ProbeReport {
  /** A valid proof, an accepted signature and compatible versions: `Connected`. */
  data class Success(val health: ComputerHealth) : ProbeReport

  /** No answer, or an answer that is not the paired computer serving this phone; [outcome] is the last probe. */
  data class Failure(val outcome: ProbeOutcome, val retryAfterS: Int? = null) : ProbeReport

  /** Another server id, or a missing or wrong proof (spec 7.4, D-37). */
  data object DifferentComputer : ProbeReport

  /** `401 credential_rejected`, or a valid proof with `revoked: true` (spec 7.4). */
  data object ConnectAgain : ProbeReport

  /** `api_version` below 6, or a pre-6 server behind a `404` on `hello` (server-contract 6.2). */
  data object ComputerTooOld : ProbeReport

  /** `min_app_version` above this app, or `426 app_too_old`. */
  data object AppTooOld : ProbeReport
}

/**
 * The pure verdicts of a probe's answers (architecture 6.2 rules 3 and 5, server-contract 6.2 and 8.1). A probe is
 * `hello` with a fresh challenge, then the signed `health`; `legacyHealth` only after a `404` on `hello`.
 */
internal object ProbeVerdicts {
  /** Phone access off on the computer, or suspended on this network (server-contract 2.1, D-42). */
  private val PHONE_ACCESS_CODES = setOf("phone_access_off", "lan_forbidden")
  private const val NOT_FOUND = 404
  private const val UPGRADE_REQUIRED = 426
  private const val TOO_MANY = 429
  private const val SERVER_ERRORS = 500

  /** What `hello` with a challenge said about the paired computer. */
  sealed interface Hello {
    /** A valid proof for the paired server id, from a device that is not revoked: go on to `health`. */
    data object Proven : Hello

    /** `404`: an older server may answer `/api/health` (D-15). */
    data object NeedLegacy : Hello

    /** The paired server id without a valid proof: another computer, unless this phone lost its own key (20.3). */
    data object ProofFailed : Hello

    data class Report(val report: ProbeReport) : Hello
  }

  fun hello(result: ApiResult<ComputerIdentity>, target: ProbeTarget): Hello = when (result) {
    is ApiResult.Ok -> identity(result.value, target)
    is ApiResult.Refused -> when {
      result.status == NOT_FOUND -> Hello.NeedLegacy
      result.code in PHONE_ACCESS_CODES -> Hello.Report(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))
      result.status == TOO_MANY -> Hello.Report(ProbeReport.Failure(ProbeOutcome.Refused, result.retryAfterS))
      result.status >= SERVER_ERRORS -> Hello.Report(ProbeReport.Failure(ProbeOutcome.Refused))
      else -> Hello.Report(ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer))
    }
    is ApiResult.Failed -> Hello.Report(failure(result.error))
  }

  private fun identity(identity: ComputerIdentity, target: ProbeTarget): Hello = when {
    !identity.phoneAccess -> Hello.Report(ProbeReport.Failure(ProbeOutcome.PhoneAccessOff))
    identity.apiVersion < ConnectivityRules.MIN_SERVER_API -> Hello.Report(ProbeReport.ComputerTooOld)
    identity.serverId != target.serverId -> Hello.Report(ProbeReport.DifferentComputer)
    identity.proofValid != true -> Hello.ProofFailed
    identity.revoked == true -> Hello.Report(ProbeReport.ConnectAgain)
    else -> Hello.Proven
  }

  /** `GET /api/health` after a `404` on `hello`: an older PhonoTrainer, or something else (server-contract 6.2). */
  fun legacy(result: ApiResult<Int>): ProbeReport =
    if (result is ApiResult.Ok && result.value < ConnectivityRules.MIN_SERVER_API) {
      ProbeReport.ComputerTooOld
    } else {
      ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer)
    }

  fun health(result: ApiResult<ComputerHealth>, target: ProbeTarget, appVersionCode: Int): ProbeReport = when (result) {
    is ApiResult.Ok -> {
      val health = result.value
      when {
        health.serverId != target.serverId -> ProbeReport.DifferentComputer
        health.apiVersion < ConnectivityRules.MIN_SERVER_API -> ProbeReport.ComputerTooOld
        health.minAppVersion > appVersionCode -> ProbeReport.AppTooOld
        else -> ProbeReport.Success(health)
      }
    }
    is ApiResult.Refused -> when {
      result.code == "credential_rejected" -> ProbeReport.ConnectAgain
      result.code == "app_too_old" || result.status == UPGRADE_REQUIRED -> ProbeReport.AppTooOld
      result.code in PHONE_ACCESS_CODES -> ProbeReport.Failure(ProbeOutcome.PhoneAccessOff)
      result.code == "clock_skew" -> ProbeReport.Failure(ProbeOutcome.Reset)
      else -> ProbeReport.Failure(ProbeOutcome.Refused, result.retryAfterS)
    }
    is ApiResult.Failed -> failure(result.error)
  }

  /** A call that got no usable answer (architecture 19). */
  fun failure(error: AppError.Computer): ProbeReport = when (error) {
    is AppError.Computer.Unreachable -> ProbeReport.Failure(error.reason)
    AppError.Computer.NotOnWifi, AppError.Computer.Offline -> ProbeReport.Failure(ProbeOutcome.NoRoute)
    AppError.Computer.DifferentComputer -> ProbeReport.DifferentComputer
    AppError.Computer.CredentialRejected -> ProbeReport.ConnectAgain
    is AppError.Computer.ClockSkew -> ProbeReport.Failure(ProbeOutcome.Reset)
    AppError.Computer.PhoneAccessOff -> ProbeReport.Failure(ProbeOutcome.PhoneAccessOff)
    AppError.Computer.ComputerTooOld -> ProbeReport.ComputerTooOld
    AppError.Computer.AppTooOld -> ProbeReport.AppTooOld
    is AppError.Computer.Refused -> ProbeReport.Failure(ProbeOutcome.Refused)
    // The guard refused (the address stopped being current or verified mid-probe): probe again.
    AppError.Computer.Guarded -> ProbeReport.Failure(ProbeOutcome.Reset)
    AppError.Computer.Malformed -> ProbeReport.Failure(ProbeOutcome.NotPhonoTrainer)
  }
}
