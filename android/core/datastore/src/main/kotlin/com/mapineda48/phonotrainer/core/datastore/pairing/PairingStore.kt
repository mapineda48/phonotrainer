package com.mapineda48.phonotrainer.core.datastore.pairing

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The paired computer as `pairing.preferences_pb` keeps it (architecture 4.5, 6.3). The credential is not here: it
 * stays encrypted in the same file and is reached only through [PairingStore.withDeviceKey].
 */
data class PairingRecord(
  val serverId: ServerId,
  val serverName: String,
  /** The last address that worked. */
  val address: HostPort,
  val deviceId: DeviceId,
  val deviceName: String,
  /** The last library id the computer reported. */
  val libraryId: LibraryId,
  val pairedAt: Instant,
  val lastSeen: Instant?,
  /** The last API version the computer reported. */
  val apiVersion: Int,
  /** The capabilities of the last `health` answer; null until one arrived. */
  val capabilities: Capabilities?,
  /** The computer's clock minus the phone's, in seconds, learned from `hello` and `clock_skew` (5.3). */
  val clockOffsetS: Long,
  val lastProbe: ProbeOutcome?,
  val lastProbeAt: Instant?,
  /** When [address] last changed to another one (Connection details). */
  val addressChangedAt: Instant?,
  /**
   * The moving average of the last measured upload rates to this computer, in bytes per second; null until one was
   * measured (the device queue then assumes 200 MB fit one background run, 8.5).
   */
  val uploadThroughput: Double?,
)

/** How a call that needs the device key ended. */
sealed interface DeviceKeyResult<out T> {
  data class Ok<T>(val value: T) : DeviceKeyResult<T>

  /** No computer is paired. */
  data object NotPaired : DeviceKeyResult<Nothing>

  /**
   * The stored credential cannot be decrypted (restored to another phone, key invalidated): the pairing is treated as
   * revoked, "Connect again" pairs as a new device (architecture 20.3, 6.3).
   */
  data object Unreadable : DeviceKeyResult<Nothing>
}

/**
 * The only access to `pairing.preferences_pb` (architecture 4.5): never backed up, holds the encrypted credential
 * (20.3). Every update except [save] does nothing while no computer is paired, so no partial record is ever written.
 */
interface PairingStore {
  /** The paired computer, or null; emits on every change. */
  val pairing: Flow<PairingRecord?>

  /** The paired computer now, or null. */
  suspend fun current(): PairingRecord?

  /**
   * Stores a new pairing in one write (architecture 6.3): the computer's answer, the address it came from, this
   * phone's name, the clock offset learned from it, and the token, encrypted. Replaces any earlier pairing.
   * [PairGrant.token] is wiped before this returns, whether or not the write succeeded.
   */
  suspend fun save(grant: PairGrant, address: HostPort, deviceName: String, pairedAt: Instant, clockOffsetS: Long)

  /**
   * Runs [block] with the device key `K = sha256(token)` (server-contract 3.2), for a request signature, an identity
   * proof or a re-activation proof (architecture 5.3, 6.3). The token and the key exist in memory only during this
   * call: both arrays are wiped before it returns, so [block] must not keep the key.
   */
  suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T>

  /** A verified answer from the computer: last seen, its API version, and its capabilities when `health` sent them. */
  suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities? = null)

  /** The computer now answers at [address] (a re-discovery or `useNewAddress`). */
  suspend fun updateAddress(address: HostPort, at: Instant)

  suspend fun updateLibraryId(libraryId: LibraryId)

  suspend fun updateClockOffset(offsetS: Long)

  /** The last probe in words (spec 7.5) and when it ran. */
  suspend fun recordProbe(outcome: ProbeOutcome, at: Instant)

  /** One measured upload: [bytes] sent in [duration]; joins the moving average of the last [UPLOAD_SAMPLES]. */
  suspend fun recordUpload(bytes: Long, duration: Duration)

  /** "Forget this computer": removes the whole pairing and deletes the credential key (architecture 6.3). */
  suspend fun clear()

  companion object {
    /** How many measured uploads the throughput average covers. */
    const val UPLOAD_SAMPLES: Int = 5
  }
}
