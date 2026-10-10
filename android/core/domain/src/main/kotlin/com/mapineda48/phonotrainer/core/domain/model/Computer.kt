package com.mapineda48.phonotrainer.core.domain.model

import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import java.time.Instant

/** Every connectivity state of spec 7.5, one subtype per row (architecture 3.4). */
sealed interface ComputerStatus {
  data object NotConnected : ComputerStatus

  data object Offline : ComputerStatus

  data object NotOnWifi : ComputerStatus

  data object Looking : ComputerStatus

  data object Checking : ComputerStatus

  data class Connected(val name: String, val analyzing: Int, val inLine: Int, val lowOnSpace: Boolean) : ComputerStatus

  data class NotReachable(
    val name: String,
    val lastSeen: Instant?,
    val addressMayHaveChanged: Boolean,
    val lastProbe: ProbeOutcome,
  ) : ComputerStatus

  data class DifferentComputer(val name: String) : ComputerStatus

  data object ConnectAgain : ComputerStatus

  data object ComputerTooOld : ComputerStatus

  data object AppTooOld : ComputerStatus
}
// "Syncing" is ComputerStatus.Connected combined with SyncState.Active (SyncController), never a separate claim.

// Work-plan Appendix A.1: computer, connection and pairing (WP-07, WP-13).

/** "The last probe in words" (spec 7.5). */
sealed interface ProbeOutcome {
  data object Ok : ProbeOutcome

  /** "No answer after 3 s". */
  data class NoAnswer(val afterS: Int) : ProbeOutcome

  data object Refused : ProbeOutcome

  data object NoRoute : ProbeOutcome

  data object Reset : ProbeOutcome

  /** Binding the socket failed with EPERM (architecture 5.1). */
  data object VpnBlocks : ProbeOutcome

  /** `phone_access` false, or `lan_forbidden`. */
  data object PhoneAccessOff : ProbeOutcome

  data object NotPhonoTrainer : ProbeOutcome

  /** DifferentComputer (architecture 5.3). */
  data object ProofFailed : ProbeOutcome
}

data class PairedComputer(
  val serverId: ServerId,
  val name: String,
  val deviceId: DeviceId,
  val deviceName: String,
  val address: HostPort,
  val libraryId: LibraryId,
  val pairedAt: Instant,
  val lastSeen: Instant?,
  val lastSync: Instant?,
  val apiVersion: Int,
  val capabilities: Capabilities?,
)

data class ConnectionDiagnostics(
  val address: HostPort?,
  val shortServerId: String?,
  val apiVersion: Int?,
  val lastProbe: ProbeOutcome?,
  val lastProbeAt: Instant?,
  val addressChangedAt: Instant?,
  val waitingRequests: Int,
  val requestsOnComputer: Int,
  val wantedDownloads: Int,
  val backgroundRestricted: Boolean,
  val decoders: List<DecoderInfo>,
  /**
   * "This phone on this Wi-Fi: {address}" (spec D-39): [NetworkSnapshot.ipv4] of the bound Wi-Fi or Ethernet; on
   * screen only, never in "Copy diagnostics" (architecture 20.3).
   */
  val phoneAddress: String? = null,
)

/** One computer that answered discovery or a typed address. [conflict]: another source used the same id or name. */
data class FoundComputer(
  val serverId: ServerId,
  val name: String,
  val address: HostPort,
  val apiVersion: Int,
  val phoneAccess: Boolean,
  val tooOld: Boolean,
  val conflict: Boolean,
)

sealed interface SearchState {
  data object Searching : SearchState

  data class Found(
    val computers: List<FoundComputer>,
    /** Null when none, two or more, or a conflict answered. */
    val preselected: FoundComputer?,
    /**
     * Two answers share a name, or one server id answers from two addresses: the D-39 note above the list
     * (architecture 6.1).
     */
    val conflict: Boolean = false,
  ) : SearchState

  data object NothingFound : SearchState

  data object NotOnWifi : SearchState
}

/** The answer to a typed address, or to `useNewAddress`. */
sealed interface CheckResult {
  data class Ok(val computer: FoundComputer) : CheckResult

  data object PhoneAccessOff : CheckResult

  data object TooOld : CheckResult

  data object NotPhonoTrainer : CheckResult

  data object NoAnswer : CheckResult

  data object NotPrivate : CheckResult

  /** useNewAddress: another server id, or no valid proof. */
  data object DifferentComputer : CheckResult
}

data class PairingTarget(val address: HostPort, val serverId: ServerId?, val name: String?)

sealed interface PairResult {
  data class Paired(val computer: PairedComputer) : PairResult

  data object CodeInvalid : PairResult

  data object CodeExpired : PairResult

  data object CodeLocked : PairResult

  data class RateLimited(val retryAfterS: Int?) : PairResult

  data object AppTooOld : PairResult

  data object PhoneAccessOff : PairResult

  data class Failed(val error: AppError.Computer) : PairResult
}

sealed interface ForgetResult {
  data object Forgotten : ForgetResult

  data class Failed(val error: AppError) : ForgetResult
}

enum class NetworkKind { NONE, MOBILE_ONLY, WIFI, ETHERNET }

data class NetworkSnapshot(
  val kind: NetworkKind,
  val networkHandle: Long?,
  val ipv4: String?,
  val prefixLength: Int?,
  val vpnActive: Boolean,
  val validated: Boolean,
)

/** One UDP answer to discovery (architecture 6.1). Not a data class: it holds a [ByteArray]. */
class Datagram(val bytes: ByteArray, val sourceHost: String, val sourcePort: Int)
