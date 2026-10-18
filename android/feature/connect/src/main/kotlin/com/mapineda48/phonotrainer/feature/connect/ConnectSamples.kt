package com.mapineda48.phonotrainer.feature.connect

import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.DecoderInfo
import com.mapineda48.phonotrainer.core.domain.model.FoundComputer
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import java.time.Instant

/**
 * Sample states of both pages, for the previews and the screen tests. Names are invented; every address is a
 * documentation address (RFC 5737), never a real one.
 */
internal object ConnectSamples {
  val now: Instant = Instant.parse("2026-10-18T10:00:00Z")

  val desk = FoundComputer(ServerId("a1b2c3d4e5f6a7b8"), "Study desk", HostPort("192.0.2.10", 8000), 6, phoneAccess = true, tooOld = false, conflict = false)
  val deskTwin = desk.copy(serverId = ServerId("f0e1d2c3b4a59687"), address = HostPort("192.0.2.11", 8000), conflict = true)
  val deskElsewhere = desk.copy(address = HostPort("192.0.2.12", 8000), conflict = true)
  val old = FoundComputer(ServerId("0123456789abcdef"), "Old laptop", HostPort("192.0.2.30", 8000), 5, phoneAccess = true, tooOld = true, conflict = false)
  val other = FoundComputer(ServerId("fedcba9876543210"), "Living room", HostPort("192.0.2.40", 8000), 6, phoneAccess = true, tooOld = false, conflict = false)

  val searching = ConnectUiState(mode = ConnectMode.FROM_HOME, search = SearchUi.Searching, deviceName = "Galaxy phone")

  val firstRun = searching.copy(mode = ConnectMode.FIRST_RUN)

  /** One computer found, preselected, its code being typed. */
  val found = ConnectUiState(
    mode = ConnectMode.FROM_HOME,
    search = SearchUi.Found(listOf(ComputerRow(desk, RowKind.READY), ComputerRow(old, RowKind.TOO_OLD)), conflict = false, stillLooking = false),
    selectedKey = ComputerRow.keyOf(desk),
    code = "482",
    deviceName = "Galaxy phone",
  )

  /** Two answers with one name (D-39, F-02-AC10): every one a row, none picked, the note above. */
  val conflict = ConnectUiState(
    mode = ConnectMode.FROM_HOME,
    search = SearchUi.Found(listOf(ComputerRow(desk.copy(conflict = true), RowKind.READY), ComputerRow(deskTwin, RowKind.READY)), conflict = true, stillLooking = false),
    deviceName = "Galaxy phone",
  )

  /** One server id answering from two addresses (the second run of F-02-AC10). */
  val sameIdTwice = conflict.copy(
    search = SearchUi.Found(listOf(ComputerRow(desk.copy(conflict = true), RowKind.READY), ComputerRow(deskElsewhere, RowKind.READY)), conflict = true, stillLooking = true),
  )

  val nothingFound = ConnectUiState(mode = ConnectMode.FIRST_RUN, search = SearchUi.NothingFound, deviceName = "Galaxy phone")

  val notOnWifi = ConnectUiState(mode = ConnectMode.FROM_HOME, search = SearchUi.NotOnWifi, deviceName = "Galaxy phone")

  /** "Enter the address instead" answered by a computer without phone access for this network (D-42). */
  val phoneAccessOff = nothingFound.copy(
    mode = ConnectMode.FROM_HOME,
    addressOpen = true,
    address = "192.0.2.10:8000",
    addressCheck = AddressCheck.Answered(CheckResult.PhoneAccessOff),
  )

  val tooOld = phoneAccessOff.copy(addressCheck = AddressCheck.Answered(CheckResult.TooOld))

  /** The typed address answered: the computer is listed under the field and picked. */
  val typed = nothingFound.copy(
    mode = ConnectMode.FROM_HOME,
    addressOpen = true,
    address = "192.0.2.10:8000",
    addressCheck = AddressCheck.Answered(CheckResult.Ok(desk)),
    typed = ComputerRow(desk, RowKind.READY, typed = true),
    selectedKey = ComputerRow.keyOf(desk),
  )

  fun codeError(error: CodeError): ConnectUiState = found.copy(code = "", codeError = error, codeErrorSerial = 1)

  /** "Connect again": only the paired computer can be picked; another one is muted. */
  val connectAgain = ConnectUiState(
    mode = ConnectMode.CONNECT_AGAIN,
    pairedName = "Study desk",
    search = SearchUi.Found(listOf(ComputerRow(desk, RowKind.PAIRED), ComputerRow(other, RowKind.OTHER_COMPUTER)), conflict = false, stillLooking = false),
    selectedKey = ComputerRow.keyOf(desk),
    deviceName = "Galaxy phone",
  )

  val newAddress = ConnectUiState(mode = ConnectMode.NEW_ADDRESS, pairedName = "Study desk", addressOpen = true, deviceName = "Galaxy phone")

  val paired = found.copy(done = ConnectDone.Paired("Study desk"))

  val newAddressDone = newAddress.copy(done = ConnectDone.NewAddress("Study desk"))

  val decoders = listOf(
    DecoderInfo("video/av01", 3840, 2160, 60.0, hardware = true),
    DecoderInfo("video/avc", 4096, 2176, 60.0, hardware = true),
    DecoderInfo("video/x-vnd.on2.vp9", 1920, 1080, null, hardware = false),
  )

  val diagnostics = ConnectionDiagnostics(
    address = HostPort("192.0.2.10", 8000),
    shortServerId = "a1b2c3d4",
    apiVersion = 6,
    lastProbe = ProbeOutcome.Ok,
    lastProbeAt = now.minusSeconds(10),
    addressChangedAt = null,
    waitingRequests = 2,
    requestsOnComputer = 1,
    wantedDownloads = 3,
    backgroundRestricted = false,
    decoders = decoders,
    phoneAddress = "192.0.2.20",
  )

  val detailsConnected = DetailsUiState(
    status = ComputerStatus.Connected("Study desk", analyzing = 0, inLine = 1, lowOnSpace = false),
    paired = true,
    diagnostics = diagnostics,
    decoders = decoders,
    now = now,
  )

  /** Paired, the computer answering without phone access for this network (F-02-AC12). */
  val detailsPhoneAccessOff = detailsConnected.copy(
    status = ComputerStatus.NotReachable("Study desk", now.minusSeconds(3_600), addressMayHaveChanged = false, ProbeOutcome.PhoneAccessOff),
    diagnostics = diagnostics.copy(lastProbe = ProbeOutcome.PhoneAccessOff, lastProbeAt = now.minusSeconds(120)),
  )

  /** Paired, nothing answered after a network change; the address changed earlier. */
  val detailsNoAnswer = detailsConnected.copy(
    status = ComputerStatus.NotReachable("Study desk", now.minusSeconds(10_800), addressMayHaveChanged = true, ProbeOutcome.NoAnswer(3)),
    diagnostics = diagnostics.copy(
      lastProbe = ProbeOutcome.NoAnswer(3),
      lastProbeAt = now.minusSeconds(60),
      addressChangedAt = now.minusSeconds(86_400),
      backgroundRestricted = true,
    ),
  )

  /** Never paired, not on Wi-Fi: little to say, the decoders still listed. */
  val detailsNotConnected = DetailsUiState(
    status = ComputerStatus.NotConnected,
    paired = false,
    diagnostics = DetailsUiState.EMPTY_DIAGNOSTICS,
    decoders = decoders,
    now = now,
  )

  val platform = PlatformInfo(appVersion = "1.0", androidRelease = "16", sdk = 36)
}
