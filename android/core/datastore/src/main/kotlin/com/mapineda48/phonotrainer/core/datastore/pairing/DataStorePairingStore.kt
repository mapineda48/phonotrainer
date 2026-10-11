package com.mapineda48.phonotrainer.core.datastore.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.datastore.PairingPreferences
import com.mapineda48.phonotrainer.core.datastore.dataOrEmpty
import com.mapineda48.phonotrainer.core.datastore.credential.CredentialCipher
import com.mapineda48.phonotrainer.core.datastore.credential.CredentialUnreadableException
import com.mapineda48.phonotrainer.core.datastore.credential.SealedCredential
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import java.nio.CharBuffer
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [PairingStore] over `pairing.preferences_pb` (architecture 4.5). Keys are private; instants are epoch milliseconds;
 * the credential is the [CredentialCipher]'s ciphertext and IV. A file missing any field of the record reads as "not
 * paired", and so does a file that is damaged or cannot be read (`DataStoreModule`).
 */
@Singleton
class DataStorePairingStore @Inject constructor(
  @param:PairingPreferences private val store: DataStore<Preferences>,
  private val cipher: CredentialCipher,
  @param:IoDispatcher private val io: CoroutineDispatcher,
) : PairingStore {
  override val pairing: Flow<PairingRecord?> = store.dataOrEmpty().map { it.toRecord() }.distinctUntilChanged()

  override suspend fun current(): PairingRecord? = pairing.first()

  override suspend fun save(
    grant: PairGrant,
    address: HostPort,
    deviceName: String,
    pairedAt: Instant,
    clockOffsetS: Long,
  ) {
    val token = grant.token.encodeUtf8()
    try {
      val sealed = withContext(io) { cipher.seal(token) }
      store.edit { preferences ->
        preferences.clear()
        preferences[SERVER_ID] = grant.serverId.value
        preferences[SERVER_NAME] = grant.name
        preferences[ADDRESS_HOST] = address.host
        preferences[ADDRESS_PORT] = address.port
        preferences[DEVICE_ID] = grant.deviceId.value
        preferences[DEVICE_NAME] = deviceName
        preferences[LIBRARY_ID] = grant.libraryId.value
        preferences[PAIRED_AT] = pairedAt.toEpochMilli()
        preferences[API_VERSION] = grant.apiVersion
        preferences[CLOCK_OFFSET_S] = clockOffsetS
        preferences[CREDENTIAL] = sealed.ciphertext
        preferences[CREDENTIAL_IV] = sealed.iv
      }
    } finally {
      token.fill(0)
      grant.token.fill(Char(0))
    }
  }

  override suspend fun <T> withDeviceKey(block: (key: ByteArray) -> T): DeviceKeyResult<T> {
    val preferences = store.dataOrEmpty().first()
    if (preferences.toRecord() == null) return DeviceKeyResult.NotPaired
    val ciphertext = preferences[CREDENTIAL]
    val iv = preferences[CREDENTIAL_IV]
    if (ciphertext == null || iv == null) return DeviceKeyResult.Unreadable
    val token = try {
      withContext(io) { cipher.open(SealedCredential(ciphertext, iv)) }
    } catch (unreadable: CredentialUnreadableException) {
      return DeviceKeyResult.Unreadable
    }
    val key = try {
      Sha256.digest(token)
    } finally {
      token.fill(0)
    }
    return try {
      DeviceKeyResult.Ok(block(key))
    } finally {
      key.fill(0)
    }
  }

  override suspend fun recordSeen(at: Instant, apiVersion: Int, capabilities: Capabilities?) = editPaired {
    it[LAST_SEEN] = at.toEpochMilli()
    it[API_VERSION] = apiVersion
    if (capabilities != null) it.writeCapabilities(capabilities)
  }

  override suspend fun updateAddress(address: HostPort, at: Instant) = editPaired {
    if (it[ADDRESS_HOST] != address.host || it[ADDRESS_PORT] != address.port) {
      it[ADDRESS_HOST] = address.host
      it[ADDRESS_PORT] = address.port
      it[ADDRESS_CHANGED_AT] = at.toEpochMilli()
    }
  }

  override suspend fun updateLibraryId(libraryId: LibraryId) = editPaired { it[LIBRARY_ID] = libraryId.value }

  override suspend fun updateClockOffset(offsetS: Long) = editPaired { it[CLOCK_OFFSET_S] = offsetS }

  override suspend fun recordProbe(outcome: ProbeOutcome, at: Instant) = editPaired {
    it[LAST_PROBE] = ProbeCodec.encode(outcome)
    it[LAST_PROBE_AT] = at.toEpochMilli()
  }

  override suspend fun recordUpload(bytes: Long, duration: Duration) {
    if (bytes <= 0 || duration.isNegative || duration.isZero) return
    val rate = bytes * MILLIS_PER_SECOND / duration.toMillis().coerceAtLeast(1).toDouble()
    editPaired { preferences ->
      val samples = (preferences.uploadRates() + rate).takeLast(PairingStore.UPLOAD_SAMPLES)
      preferences[UPLOAD_RATES] = samples.joinToString(",")
    }
  }

  override suspend fun clear() {
    store.edit { it.clear() }
    withContext(io) { cipher.forget() }
  }

  private suspend fun editPaired(change: (MutablePreferences) -> Unit) {
    store.edit { preferences -> if (preferences.toRecord() != null) change(preferences) }
  }

  private companion object Keys {
    val SERVER_ID = stringPreferencesKey("server_id")
    val SERVER_NAME = stringPreferencesKey("server_name")
    val ADDRESS_HOST = stringPreferencesKey("address_host")
    val ADDRESS_PORT = intPreferencesKey("address_port")
    val ADDRESS_CHANGED_AT = longPreferencesKey("address_changed_at_ms")
    val DEVICE_ID = stringPreferencesKey("device_id")
    val DEVICE_NAME = stringPreferencesKey("device_name")
    val LIBRARY_ID = stringPreferencesKey("library_id")
    val PAIRED_AT = longPreferencesKey("paired_at_ms")
    val LAST_SEEN = longPreferencesKey("last_seen_ms")
    val API_VERSION = intPreferencesKey("api_version")
    val CLOCK_OFFSET_S = longPreferencesKey("clock_offset_s")
    val LAST_PROBE = stringPreferencesKey("last_probe")
    val LAST_PROBE_AT = longPreferencesKey("last_probe_at_ms")

    /** Bytes per second of the last measured uploads, oldest first, comma-separated. */
    val UPLOAD_RATES = stringPreferencesKey("upload_rates")
    val CREDENTIAL = byteArrayPreferencesKey("credential")
    val CREDENTIAL_IV = byteArrayPreferencesKey("credential_iv")

    // Capabilities of the last `health` answer (server-contract 6.2); CAPS_KNOWN marks that one arrived.
    val CAPS_KNOWN = booleanPreferencesKey("caps_known")
    val CAPS_LINK_DOWNLOADS = booleanPreferencesKey("caps_link_downloads")
    val CAPS_DOWNLOADER_VERSION = stringPreferencesKey("caps_downloader_version")
    val CAPS_DIALOGUE_SEPARATION = booleanPreferencesKey("caps_dialogue_separation")
    val CAPS_RENDITIONS = booleanPreferencesKey("caps_renditions")
    val CAPS_PHONE_VIDEO = booleanPreferencesKey("caps_phone_video")
    val CAPS_RESUMABLE_UPLOADS = booleanPreferencesKey("caps_resumable_uploads")
    val CAPS_RECORD_COMPARE = booleanPreferencesKey("caps_record_compare")
    val CAPS_MAX_UPLOAD_BYTES = longPreferencesKey("caps_max_upload_bytes")
    val CAPS_MAX_CHUNK_BYTES = longPreferencesKey("caps_max_chunk_bytes")
    val CAPS_MAX_LINK_DURATION_S = longPreferencesKey("caps_max_link_duration_s")
    val CAPS_FREE_BYTES = longPreferencesKey("caps_free_bytes")

    const val MILLIS_PER_SECOND = 1000.0

    fun Preferences.toRecord(): PairingRecord? {
      val serverId = this[SERVER_ID] ?: return null
      val serverName = this[SERVER_NAME] ?: return null
      val host = this[ADDRESS_HOST] ?: return null
      val port = this[ADDRESS_PORT] ?: return null
      val deviceId = this[DEVICE_ID] ?: return null
      val deviceName = this[DEVICE_NAME] ?: return null
      val libraryId = this[LIBRARY_ID] ?: return null
      val pairedAt = this[PAIRED_AT] ?: return null
      val apiVersion = this[API_VERSION] ?: return null
      return PairingRecord(
        serverId = ServerId(serverId),
        serverName = serverName,
        address = HostPort(host, port),
        deviceId = DeviceId(deviceId),
        deviceName = deviceName,
        libraryId = LibraryId(libraryId),
        pairedAt = Instant.ofEpochMilli(pairedAt),
        lastSeen = this[LAST_SEEN]?.let(Instant::ofEpochMilli),
        apiVersion = apiVersion,
        capabilities = readCapabilities(),
        clockOffsetS = this[CLOCK_OFFSET_S] ?: 0,
        lastProbe = this[LAST_PROBE]?.let(ProbeCodec::decode),
        lastProbeAt = this[LAST_PROBE_AT]?.let(Instant::ofEpochMilli),
        addressChangedAt = this[ADDRESS_CHANGED_AT]?.let(Instant::ofEpochMilli),
        uploadThroughput = uploadRates().takeIf { it.isNotEmpty() }?.average(),
      )
    }

    fun Preferences.uploadRates(): List<Double> =
      this[UPLOAD_RATES].orEmpty().split(',').mapNotNull { it.toDoubleOrNull()?.takeIf { rate -> rate > 0 } }

    fun Preferences.readCapabilities(): Capabilities? {
      if (this[CAPS_KNOWN] != true) return null
      return Capabilities(
        linkDownloads = this[CAPS_LINK_DOWNLOADS] ?: false,
        downloaderVersion = this[CAPS_DOWNLOADER_VERSION],
        dialogueSeparation = this[CAPS_DIALOGUE_SEPARATION] ?: false,
        renditions = this[CAPS_RENDITIONS] ?: false,
        phoneVideo = this[CAPS_PHONE_VIDEO] ?: false,
        resumableUploads = this[CAPS_RESUMABLE_UPLOADS] ?: false,
        recordCompare = this[CAPS_RECORD_COMPARE] ?: false,
        maxUploadBytes = this[CAPS_MAX_UPLOAD_BYTES] ?: 0,
        maxChunkBytes = this[CAPS_MAX_CHUNK_BYTES] ?: 0,
        maxLinkDurationS = this[CAPS_MAX_LINK_DURATION_S] ?: 0,
        freeBytes = this[CAPS_FREE_BYTES] ?: 0,
      )
    }

    fun MutablePreferences.writeCapabilities(capabilities: Capabilities) {
      this[CAPS_KNOWN] = true
      this[CAPS_LINK_DOWNLOADS] = capabilities.linkDownloads
      capabilities.downloaderVersion?.let { this[CAPS_DOWNLOADER_VERSION] = it } ?: remove(CAPS_DOWNLOADER_VERSION)
      this[CAPS_DIALOGUE_SEPARATION] = capabilities.dialogueSeparation
      this[CAPS_RENDITIONS] = capabilities.renditions
      this[CAPS_PHONE_VIDEO] = capabilities.phoneVideo
      this[CAPS_RESUMABLE_UPLOADS] = capabilities.resumableUploads
      this[CAPS_RECORD_COMPARE] = capabilities.recordCompare
      this[CAPS_MAX_UPLOAD_BYTES] = capabilities.maxUploadBytes
      this[CAPS_MAX_CHUNK_BYTES] = capabilities.maxChunkBytes
      this[CAPS_MAX_LINK_DURATION_S] = capabilities.maxLinkDurationS
      this[CAPS_FREE_BYTES] = capabilities.freeBytes
    }

    /** UTF-8 bytes of a secret without making a `String` of it; the encoder's buffer is wiped. */
    fun CharArray.encodeUtf8(): ByteArray {
      val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
      val bytes = ByteArray(buffer.remaining())
      buffer.get(bytes)
      if (buffer.hasArray()) buffer.array().fill(0)
      return bytes
    }
  }
}

/** The last probe outcome as a short stable code in the pairing file; an unknown code reads as null. */
internal object ProbeCodec {
  private const val NO_ANSWER = "no_answer:"

  fun encode(outcome: ProbeOutcome): String = when (outcome) {
    ProbeOutcome.Ok -> "ok"
    is ProbeOutcome.NoAnswer -> NO_ANSWER + outcome.afterS
    ProbeOutcome.Refused -> "refused"
    ProbeOutcome.NoRoute -> "no_route"
    ProbeOutcome.Reset -> "reset"
    ProbeOutcome.VpnBlocks -> "vpn_blocks"
    ProbeOutcome.PhoneAccessOff -> "phone_access_off"
    ProbeOutcome.NotPhonoTrainer -> "not_phonotrainer"
    ProbeOutcome.ProofFailed -> "proof_failed"
  }

  fun decode(code: String): ProbeOutcome? = when {
    code.startsWith(NO_ANSWER) -> code.removePrefix(NO_ANSWER).toIntOrNull()?.let(ProbeOutcome::NoAnswer)
    else -> when (code) {
      "ok" -> ProbeOutcome.Ok
      "refused" -> ProbeOutcome.Refused
      "no_route" -> ProbeOutcome.NoRoute
      "reset" -> ProbeOutcome.Reset
      "vpn_blocks" -> ProbeOutcome.VpnBlocks
      "phone_access_off" -> ProbeOutcome.PhoneAccessOff
      "not_phonotrainer" -> ProbeOutcome.NotPhonoTrainer
      "proof_failed" -> ProbeOutcome.ProofFailed
      else -> null
    }
  }
}
