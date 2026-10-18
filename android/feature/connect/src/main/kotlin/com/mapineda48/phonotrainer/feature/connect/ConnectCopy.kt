package com.mapineda48.phonotrainer.feature.connect

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.CheckResult

/** The words of Connect's answers (spec F-02 States), in one place for the screen and its tests. */
internal object ConnectCopy {
  /** The status word of a row (design 10.2 P-06: name, "PhonoTrainer {version}" and a status word). */
  fun rowStatus(row: ComputerRow, pairedName: String?, resources: Resources): String = when (row.kind) {
    RowKind.READY -> resources.getString(if (row.typed) R.string.connect_row_typed else R.string.connect_row_ready)
    RowKind.PAIRED -> resources.getString(R.string.connect_row_paired)
    RowKind.TOO_OLD -> resources.getString(R.string.connect_row_too_old)
    RowKind.PHONE_ACCESS_OFF -> resources.getString(R.string.connect_row_phone_access_off)
    RowKind.OTHER_COMPUTER ->
      pairedName?.let { resources.getString(R.string.connect_row_other_computer, it) }
        ?: resources.getString(R.string.connect_row_other_computer_unnamed)
  }

  /** The line under the address field; null while nothing was asked. */
  fun addressAnswer(check: AddressCheck, resources: Resources): String = when (check) {
    AddressCheck.Checking -> resources.getString(R.string.connect_address_checking)
    AddressCheck.Invalid -> resources.getString(R.string.connect_address_invalid)
    is AddressCheck.Answered -> when (val result = check.result) {
      is CheckResult.Ok -> when {
        result.computer.tooOld -> resources.getString(R.string.connect_address_too_old)
        !result.computer.phoneAccess -> phoneAccessOff(resources)
        else -> resources.getString(R.string.connect_address_found, result.computer.name)
      }
      CheckResult.PhoneAccessOff -> phoneAccessOff(resources)
      CheckResult.TooOld -> resources.getString(R.string.connect_address_too_old)
      CheckResult.NotPhonoTrainer -> resources.getString(R.string.connect_address_not_phonotrainer)
      CheckResult.NoAnswer -> resources.getString(R.string.connect_address_no_answer)
      CheckResult.NotPrivate -> resources.getString(R.string.connect_address_not_private)
      CheckResult.DifferentComputer -> resources.getString(R.string.connect_address_different)
    }
  }

  /** Whether the answer under the address field is a problem (a caution line) rather than progress or success. */
  fun addressIsProblem(check: AddressCheck): Boolean = when (check) {
    AddressCheck.Checking -> false
    AddressCheck.Invalid -> true
    is AddressCheck.Answered -> (check.result as? CheckResult.Ok)?.computer?.let { it.tooOld || !it.phoneAccess } ?: true
  }

  /** The critical line under the code cells. */
  fun codeError(error: CodeError, resources: Resources): String = when (error) {
    CodeError.Invalid -> resources.getString(R.string.connect_error_invalid)
    CodeError.Expired -> resources.getString(R.string.connect_error_expired)
    CodeError.Locked -> resources.getString(R.string.connect_error_locked)
    is CodeError.RateLimited ->
      error.retryAfterS?.let { resources.getString(R.string.connect_error_rate_limited, it) }
        ?: resources.getString(R.string.connect_error_rate_limited_unknown)
    CodeError.AppTooOld -> resources.getString(R.string.connect_error_app_too_old)
    CodeError.PhoneAccessOff ->
      resources.getString(R.string.connect_error_phone_access_off, resources.getString(R.string.connect_command_phone_on))
    CodeError.DifferentComputer -> resources.getString(R.string.connect_error_different)
    is CodeError.OtherComputer ->
      error.name?.let { resources.getString(R.string.connect_row_other_computer, it) }
        ?: resources.getString(R.string.connect_row_other_computer_unnamed)
    CodeError.NotReachable -> resources.getString(R.string.connect_error_not_reachable)
    CodeError.NotOnWifi -> resources.getString(R.string.connect_error_not_on_wifi)
    CodeError.ComputerTooOld -> resources.getString(R.string.connect_error_computer_too_old)
    CodeError.Failed -> resources.getString(R.string.connect_error_failed)
  }

  /** "That address answered, but phone access isn't on there for this network. …" (F-02 States, D-42). */
  private fun phoneAccessOff(resources: Resources): String =
    resources.getString(R.string.connect_address_phone_access_off, resources.getString(R.string.connect_command_phone_on))
}
