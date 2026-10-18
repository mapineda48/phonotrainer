package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import com.mapineda48.phonotrainer.core.navigation.ConnectMode

/** What a row of the list says about its computer, and whether it can be picked (spec F-02 States). */
internal enum class RowKind(val selectable: Boolean) {
  /** A computer this phone can connect to. */
  READY(true),

  /** The computer this phone is paired with ("Connect again" after a revoke, spec F-02 States). */
  PAIRED(true),

  /** "Needs a newer PhonoTrainer on your computer": not selectable. */
  TOO_OLD(false),

  /** Phone access is off there or not approved for this network (D-42): not selectable. */
  PHONE_ACCESS_OFF(false),

  /** Another computer while this phone is paired: "Forget it first in Settings" (D-29), muted. */
  OTHER_COMPUTER(false),
}

/** One computer that answered, as a row of the list. [typed]: it answered the address the learner entered. */
@Immutable
internal data class ComputerRow(val computer: FoundComputer, val kind: RowKind, val typed: Boolean = false) {
  /** One row per server id and address (architecture 6.1): the same id from two addresses is two rows. */
  val key: String get() = keyOf(computer)

  companion object {
    fun keyOf(computer: FoundComputer): String = "${computer.serverId.value}@${computer.address.host}:${computer.address.port}"
  }
}

/** The search part of the page (spec F-02 behaviour 3, States). */
internal sealed interface SearchUi {
  /** No search (the new-address mode opens on the address field). */
  data object Idle : SearchUi

  /** "Looking for your computer…" with nothing found yet. */
  data object Searching : SearchUi

  /** The computers that answered; [stillLooking] while the 10 s are not over; [conflict]: the D-39 note. */
  data class Found(val rows: List<ComputerRow>, val conflict: Boolean, val stillLooking: Boolean) : SearchUi

  /** "No computer answered." with the checklist. */
  data object NothingFound : SearchUi

  /** "Connect to your home Wi-Fi first." */
  data object NotOnWifi : SearchUi
}

/** The answer under the address field. */
internal sealed interface AddressCheck {
  data object Checking : AddressCheck

  /** The text is not an IPv4 address and port. */
  data object Invalid : AddressCheck

  data class Answered(val result: CheckResult) : AddressCheck
}

/** Why the code did not connect (spec F-02 States "Error" rows), shown under the cells. */
internal sealed interface CodeError {
  data object Invalid : CodeError

  data object Expired : CodeError

  data object Locked : CodeError

  /** `rate_limited` with its `Retry-After` in seconds, when the computer sent one. */
  data class RateLimited(val retryAfterS: Int?) : CodeError

  data object AppTooOld : CodeError

  data object PhoneAccessOff : CodeError

  /** The address answered as another computer than the row picked. */
  data object DifferentComputer : CodeError

  /** This phone is paired with [name]; pairing another computer needs "Forget" first (D-29). */
  data class OtherComputer(val name: String?) : CodeError

  data object NotReachable : CodeError

  data object NotOnWifi : CodeError

  data object ComputerTooOld : CodeError

  data object Failed : CodeError
}

/** How Connect ended. */
internal sealed interface ConnectDone {
  /** "Connected to {name}" (F-02 behaviour 6). */
  data class Paired(val name: String) : ConnectDone

  /** The paired computer proved itself at the address the learner entered (F-02-AC6). */
  data class NewAddress(val name: String) : ConnectDone
}

/** Everything Connect shows (P-06). */
@Immutable
internal data class ConnectUiState(
  val mode: ConnectMode = ConnectMode.FROM_HOME,
  /** The paired computer's name, when this phone is paired. */
  val pairedName: String? = null,
  val search: SearchUi = SearchUi.Idle,
  /** The computer that answered the typed address, listed first and picked. */
  val typed: ComputerRow? = null,
  val selectedKey: String? = null,
  val addressOpen: Boolean = false,
  val address: String = "",
  val addressCheck: AddressCheck? = null,
  val code: String = "",
  val deviceName: String = "",
  val pairing: Boolean = false,
  val codeError: CodeError? = null,
  /** Grows with every code error, so the screen puts the focus back in the cells each time (F-02-AC3). */
  val codeErrorSerial: Int = 0,
  val done: ConnectDone? = null,
) {
  /** The rows of the list: the typed answer first, then the search's, each key once. */
  val rows: List<ComputerRow>
    get() {
      val found = (search as? SearchUi.Found)?.rows.orEmpty()
      return (listOfNotNull(typed) + found).distinctBy { it.key }
    }

  val selected: ComputerRow?
    get() = rows.firstOrNull { it.key == selectedKey }?.takeIf { it.kind.selectable }

  val canConnect: Boolean
    get() = selected != null && code.length == CODE_LENGTH && deviceName.isNotBlank() && !pairing && done == null

  companion object {
    const val CODE_LENGTH = 6

    /** The longest phone name the field keeps (the computer cleans and shortens names itself, server-contract 2.3). */
    const val NAME_MAX = 40
  }
}

/** The pure decisions of Connect, tested on their own. */
internal object ConnectRules {
  /** The kind of [computer]'s row for a phone paired with [paired] (or not paired). */
  fun kindOf(computer: FoundComputer, paired: PairedComputer?): RowKind = when {
    paired != null && computer.serverId != paired.serverId -> RowKind.OTHER_COMPUTER
    computer.tooOld -> RowKind.TOO_OLD
    !computer.phoneAccess -> RowKind.PHONE_ACCESS_OFF
    paired != null -> RowKind.PAIRED
    else -> RowKind.READY
  }

  /** The search part for the latest search answer; [running] while the search has not ended. */
  fun searchUi(latest: SearchState?, running: Boolean, paired: PairedComputer?): SearchUi = when (latest) {
    null -> if (running) SearchUi.Searching else SearchUi.Idle
    SearchState.Searching -> if (running) SearchUi.Searching else SearchUi.NothingFound
    is SearchState.Found ->
      if (latest.computers.isEmpty()) {
        if (running) SearchUi.Searching else SearchUi.NothingFound
      } else {
        SearchUi.Found(latest.computers.map { ComputerRow(it, kindOf(it, paired)) }, latest.conflict, running)
      }
    SearchState.NothingFound -> SearchUi.NothingFound
    SearchState.NotOnWifi -> SearchUi.NotOnWifi
  }

  /**
   * The row picked when the learner has not picked one: the search's own preselection (exactly one computer and no
   * conflict, spec F-02 behaviour 3), or, for "Connect again", the paired computer when it is the only one that can be
   * picked. Never anything while two answers conflict (D-39, F-02-AC10).
   */
  fun defaultSelection(latest: SearchState?, rows: List<ComputerRow>): String? {
    val found = latest as? SearchState.Found ?: return null
    if (found.conflict || rows.any { it.computer.conflict }) return null
    found.preselected?.let { pre -> rows.firstOrNull { it.computer == pre && it.kind.selectable }?.let { return it.key } }
    return rows.singleOrNull { it.kind == RowKind.PAIRED }?.key
  }

  /** What the code's answer means on screen; null for success. */
  fun codeError(result: PairResult, pairedName: String?, targetIsPaired: Boolean): CodeError? = when (result) {
    is PairResult.Paired -> null
    PairResult.CodeInvalid -> CodeError.Invalid
    PairResult.CodeExpired -> CodeError.Expired
    PairResult.CodeLocked -> CodeError.Locked
    is PairResult.RateLimited -> CodeError.RateLimited(result.retryAfterS?.takeIf { it > 0 })
    PairResult.AppTooOld -> CodeError.AppTooOld
    PairResult.PhoneAccessOff -> CodeError.PhoneAccessOff
    is PairResult.Failed -> when (result.error) {
      AppError.Computer.DifferentComputer ->
        if (pairedName != null && !targetIsPaired) CodeError.OtherComputer(pairedName) else CodeError.DifferentComputer
      AppError.Computer.PhoneAccessOff -> CodeError.PhoneAccessOff
      AppError.Computer.AppTooOld -> CodeError.AppTooOld
      AppError.Computer.ComputerTooOld -> CodeError.ComputerTooOld
      AppError.Computer.NotOnWifi, AppError.Computer.Offline -> CodeError.NotOnWifi
      is AppError.Computer.Unreachable -> CodeError.NotReachable
      else -> CodeError.Failed
    }
  }

  /** The code as typed or pasted: its digits only (a pasted "482 913" is "482913"), at most six. */
  fun codeOf(text: String): String = text.filter { it in '0'..'9' }.take(ConnectUiState.CODE_LENGTH)

  /** A dead code is cleared (wrong, expired, locked); any other answer keeps it for a second try. */
  fun clearsCode(error: CodeError): Boolean =
    error == CodeError.Invalid || error == CodeError.Expired || error == CodeError.Locked
}
