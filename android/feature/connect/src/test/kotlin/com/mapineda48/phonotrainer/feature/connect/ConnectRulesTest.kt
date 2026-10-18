package com.mapineda48.phonotrainer.feature.connect

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import java.time.Instant
import org.junit.Test

/** The pure decisions of Connect (spec F-02 behaviour 3 and States, D-29, D-39). */
class ConnectRulesTest {
  private val desk = ConnectSamples.desk
  private val paired = PairedComputer(
    desk.serverId, desk.name, DeviceId("d1"), "Galaxy phone", desk.address, LibraryId("l1"),
    Instant.parse("2026-10-18T09:00:00Z"), null, null, 6, null,
  )

  @Test
  fun eachRowSaysWhetherItCanBePicked() {
    assertThat(ConnectRules.kindOf(desk, null)).isEqualTo(RowKind.READY)
    assertThat(ConnectRules.kindOf(ConnectSamples.old, null)).isEqualTo(RowKind.TOO_OLD)
    assertThat(ConnectRules.kindOf(desk.copy(phoneAccess = false), null)).isEqualTo(RowKind.PHONE_ACCESS_OFF)
    // Paired: the same computer is "Connect again", any other one waits for "Forget" (D-29).
    assertThat(ConnectRules.kindOf(desk, paired)).isEqualTo(RowKind.PAIRED)
    assertThat(ConnectRules.kindOf(ConnectSamples.other, paired)).isEqualTo(RowKind.OTHER_COMPUTER)
    assertThat(RowKind.entries.filter { it.selectable }).containsExactly(RowKind.READY, RowKind.PAIRED)
  }

  @Test
  fun theSearchPartFollowsTheSearch() {
    assertThat(ConnectRules.searchUi(null, running = false, paired = null)).isEqualTo(SearchUi.Idle)
    assertThat(ConnectRules.searchUi(null, running = true, paired = null)).isEqualTo(SearchUi.Searching)
    assertThat(ConnectRules.searchUi(SearchState.Searching, running = true, paired = null)).isEqualTo(SearchUi.Searching)
    // A search that ended without a final answer found nothing.
    assertThat(ConnectRules.searchUi(SearchState.Searching, running = false, paired = null)).isEqualTo(SearchUi.NothingFound)
    assertThat(ConnectRules.searchUi(SearchState.NothingFound, running = false, paired = null)).isEqualTo(SearchUi.NothingFound)
    assertThat(ConnectRules.searchUi(SearchState.NotOnWifi, running = false, paired = null)).isEqualTo(SearchUi.NotOnWifi)
    val found = SearchState.Found(listOf(desk), preselected = desk)
    assertThat(ConnectRules.searchUi(found, running = true, paired = null))
      .isEqualTo(SearchUi.Found(listOf(ComputerRow(desk, RowKind.READY)), conflict = false, stillLooking = true))
    assertThat(ConnectRules.searchUi(SearchState.Found(emptyList(), null), running = false, paired = null)).isEqualTo(SearchUi.NothingFound)
  }

  @Test
  fun exactlyOneComputerIsPreselectedButNeverWhileTwoAnswersConflict() {
    val one = SearchState.Found(listOf(desk), preselected = desk)
    assertThat(ConnectRules.defaultSelection(one, listOf(ComputerRow(desk, RowKind.READY)))).isEqualTo(ComputerRow.keyOf(desk))

    // F-02-AC10: two names alike, or one id from two addresses: none preselected, whatever the search said.
    val twins = listOf(desk.copy(conflict = true), ConnectSamples.deskTwin)
    val conflict = SearchState.Found(twins, preselected = twins.first(), conflict = true)
    assertThat(ConnectRules.defaultSelection(conflict, twins.map { ComputerRow(it, RowKind.READY) })).isNull()
    val sameId = listOf(desk.copy(conflict = true), ConnectSamples.deskElsewhere)
    assertThat(ConnectRules.defaultSelection(SearchState.Found(sameId, null), sameId.map { ComputerRow(it, RowKind.READY) })).isNull()

    // A too-old computer is never picked for the learner.
    val old = SearchState.Found(listOf(ConnectSamples.old), preselected = ConnectSamples.old)
    assertThat(ConnectRules.defaultSelection(old, listOf(ComputerRow(ConnectSamples.old, RowKind.TOO_OLD)))).isNull()
  }

  @Test
  fun connectAgainPicksThePairedComputerWhenItIsTheOnlyOne() {
    val rows = listOf(ComputerRow(desk, RowKind.PAIRED), ComputerRow(ConnectSamples.other, RowKind.OTHER_COMPUTER))
    val found = SearchState.Found(listOf(desk, ConnectSamples.other), preselected = null)
    assertThat(ConnectRules.defaultSelection(found, rows)).isEqualTo(ComputerRow.keyOf(desk))
  }

  @Test
  fun everyAnswerToTheCodeHasItsWords() {
    assertThat(ConnectRules.codeError(PairResult.CodeInvalid, null, false)).isEqualTo(CodeError.Invalid)
    assertThat(ConnectRules.codeError(PairResult.CodeExpired, null, false)).isEqualTo(CodeError.Expired)
    assertThat(ConnectRules.codeError(PairResult.CodeLocked, null, false)).isEqualTo(CodeError.Locked)
    assertThat(ConnectRules.codeError(PairResult.RateLimited(30), null, false)).isEqualTo(CodeError.RateLimited(30))
    assertThat(ConnectRules.codeError(PairResult.RateLimited(null), null, false)).isEqualTo(CodeError.RateLimited(null))
    assertThat(ConnectRules.codeError(PairResult.AppTooOld, null, false)).isEqualTo(CodeError.AppTooOld)
    assertThat(ConnectRules.codeError(PairResult.PhoneAccessOff, null, false)).isEqualTo(CodeError.PhoneAccessOff)
    val different = PairResult.Failed(AppError.Computer.DifferentComputer)
    assertThat(ConnectRules.codeError(different, null, false)).isEqualTo(CodeError.DifferentComputer)
    // Paired with another computer: "Forget it first" (D-29), never "a different computer".
    assertThat(ConnectRules.codeError(different, "Study desk", targetIsPaired = false)).isEqualTo(CodeError.OtherComputer("Study desk"))
    assertThat(ConnectRules.codeError(different, "Study desk", targetIsPaired = true)).isEqualTo(CodeError.DifferentComputer)
    assertThat(ConnectRules.codeError(PairResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(3))), null, false))
      .isEqualTo(CodeError.NotReachable)
    assertThat(ConnectRules.codeError(PairResult.Failed(AppError.Computer.NotOnWifi), null, false)).isEqualTo(CodeError.NotOnWifi)
    assertThat(ConnectRules.codeError(PairResult.Failed(AppError.Computer.ComputerTooOld), null, false)).isEqualTo(CodeError.ComputerTooOld)
    assertThat(ConnectRules.codeError(PairResult.Failed(AppError.Computer.Refused("not_stored", "")), null, false)).isEqualTo(CodeError.Failed)
  }

  @Test
  fun aPastedCodeKeepsItsSixDigits() {
    assertThat(ConnectRules.codeOf("482 913")).isEqualTo("482913")
    assertThat(ConnectRules.codeOf("Pairing code: 482 913 (valid)")).isEqualTo("482913")
    assertThat(ConnectRules.codeOf("12345678")).isEqualTo("123456")
    assertThat(ConnectRules.codeOf("abc")).isEmpty()
  }

  @Test
  fun aDeadCodeIsClearedAndAnyOtherKept() {
    assertThat(ConnectRules.clearsCode(CodeError.Invalid)).isTrue()
    assertThat(ConnectRules.clearsCode(CodeError.Expired)).isTrue()
    assertThat(ConnectRules.clearsCode(CodeError.Locked)).isTrue()
    assertThat(ConnectRules.clearsCode(CodeError.RateLimited(30))).isFalse()
    assertThat(ConnectRules.clearsCode(CodeError.NotReachable)).isFalse()
  }
}
