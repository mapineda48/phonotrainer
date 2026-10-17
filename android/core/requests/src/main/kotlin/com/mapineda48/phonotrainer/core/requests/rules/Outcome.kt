package com.mapineda48.phonotrainer.core.requests.rules

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.network.api.ApiResult

/** The phone classes of server-contract 8.1 (architecture 8.5), plus "unreachable", which ends a pass. */
internal sealed interface Outcome {
  /** No answer, a timeout, a reset: the computer is not there right now; the attempt counts and the pass stops. */
  data object Unreachable : Outcome

  /** Retry on the schedule. */
  data class Transient(val code: String?, val message: String?) : Outcome

  /** The whole queue pauses (credential rejected, too old, phone access off); nothing is counted or failed (QT-8). */
  data class Blocking(val code: String) : Outcome

  /** Uploads pause, links continue (`507 no_space`). */
  data object UploadsBlocked : Outcome

  /** The request needs the learner. */
  data class Permanent(val code: String, val message: String?) : Outcome

  /** An app defect (logged); the queue treats it as transient where a request waits, as finished for an operation. */
  data class Defect(val code: String, val message: String?) : Outcome
}

/** Classifies an answer that is not a success (server-contract 8.1). */
internal object Outcomes {
  private val BLOCKING = setOf(Codes.CREDENTIAL_REJECTED, Codes.PHONE_ACCESS_OFF, Codes.APP_TOO_OLD, Codes.LAN_FORBIDDEN)
  private val TRANSIENT = setOf(
    Codes.CLOCK_SKEW, Codes.CANNOT_CHECK_LINK, Codes.UPLOAD_BUSY, Codes.LENGTH_MISMATCH, Codes.CHECKSUM_MISMATCH,
    Codes.RATE_LIMITED, Codes.SERVER_ERROR, Codes.ARTIFACT_NOT_READY, Codes.OFFSET_MISMATCH, Codes.STORE_UNAVAILABLE,
  )
  private val PERMANENT = setOf(
    Codes.INVALID_URL, Codes.REFUSED_ADDRESS, Codes.TOO_LARGE, Codes.UNSUPPORTED_TYPE, Codes.SOURCE_GONE,
  )

  fun of(result: ApiResult<*>): Outcome = when (result) {
    is ApiResult.Ok -> error("a success is not an outcome to classify")
    is ApiResult.Refused -> refused(result.status, result.code, result.detail)
    is ApiResult.Failed -> failed(result.error)
  }

  fun refused(status: Int, code: String, detail: String?): Outcome {
    val message = detail?.takeIf { it.isNotBlank() }
    return when {
      code in BLOCKING -> Outcome.Blocking(code)
      code == Codes.NO_SPACE -> Outcome.UploadsBlocked
      code in TRANSIENT -> Outcome.Transient(code, message)
      code in PERMANENT -> Outcome.Permanent(code, message)
      status >= SERVER_ERRORS || status == TOO_MANY_REQUESTS || code.isEmpty() -> Outcome.Transient(code.ifEmpty { null }, message)
      else -> Outcome.Defect(code, message)
    }
  }

  fun failed(error: AppError.Computer): Outcome = when (error) {
    AppError.Computer.NotOnWifi, AppError.Computer.Offline, is AppError.Computer.Unreachable -> Outcome.Unreachable
    AppError.Computer.CredentialRejected -> Outcome.Blocking(Codes.CREDENTIAL_REJECTED)
    AppError.Computer.PhoneAccessOff -> Outcome.Blocking(Codes.PHONE_ACCESS_OFF)
    AppError.Computer.AppTooOld -> Outcome.Blocking(Codes.APP_TOO_OLD)
    AppError.Computer.ComputerTooOld -> Outcome.Blocking("computer_too_old")
    AppError.Computer.DifferentComputer -> Outcome.Blocking("different_computer")
    AppError.Computer.Guarded -> Outcome.Blocking("guarded")
    is AppError.Computer.ClockSkew -> Outcome.Transient(Codes.CLOCK_SKEW, null)
    AppError.Computer.Malformed -> Outcome.Transient("malformed", null)
    is AppError.Computer.Refused -> refused(0, error.code, error.detail)
  }

  private const val SERVER_ERRORS = 500
  private const val TOO_MANY_REQUESTS = 429
}
