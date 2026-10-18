package com.mapineda48.phonotrainer.core.sync.meta

import com.mapineda48.phonotrainer.core.database.library.dao.SyncMetaDao
import com.mapineda48.phonotrainer.core.database.library.entity.SyncMetaEntity
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A mass deletion waiting for the learner (architecture 7.7): [jobIds] would disappear, [count] of [ofTotal] clips of
 * the computer named [serverName].
 */
internal data class PendingMassDeletion(
  val jobIds: List<String>,
  val count: Int,
  val ofTotal: Int,
  val serverName: String,
)

/** The `sync_meta` keys of architecture 4.2, typed, named once. */
internal class SyncMeta @Inject constructor(private val dao: SyncMetaDao) {
  /** When the last complete listing of the paired computer was reconciled: "Last synced {time}" (F-21). */
  suspend fun lastListingAt(): Instant? = dao.get(LAST_LISTING_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli)

  fun observeLastListingAt(): Flow<Instant?> =
    dao.observe(LAST_LISTING_AT).map { it?.toLongOrNull()?.let(Instant::ofEpochMilli) }

  suspend fun recordListing(serverId: String, libraryId: String, at: Instant) {
    put(LISTING_SERVER_ID, serverId)
    put(LISTING_LIBRARY_ID, libraryId)
    put(LAST_LISTING_AT, at.toEpochMilli().toString())
  }

  /** The server and library of the last complete listing reconciled, or null before the first one. */
  suspend fun lastListing(): Pair<String, String>? {
    val server = dao.get(LISTING_SERVER_ID) ?: return null
    val library = dao.get(LISTING_LIBRARY_ID) ?: return null
    return server to library
  }

  /** The API version of the reference body last accepted by `ReferenceRepository.replace` (architecture 7.1 step 5). */
  suspend fun referenceApiVersion(): Int? = dao.get(REFERENCE_API_VERSION)?.toIntOrNull()

  suspend fun recordReference(apiVersion: Int) = put(REFERENCE_API_VERSION, apiVersion.toString())

  suspend fun pendingMassDeletion(): PendingMassDeletion? = dao.get(MASS_DELETION)?.let(::decode)

  fun observePendingMassDeletion(): Flow<PendingMassDeletion?> = dao.observe(MASS_DELETION).map { it?.let(::decode) }

  suspend fun setPendingMassDeletion(prompt: PendingMassDeletion?) {
    if (prompt == null) {
      dao.delete(MASS_DELETION)
      return
    }
    val json = buildJsonObject {
      put(COUNT, prompt.count)
      put(OF_TOTAL, prompt.ofTotal)
      put(SERVER_NAME, prompt.serverName)
      put(JOB_IDS, JsonArray(prompt.jobIds.map(::JsonPrimitive)))
    }
    put(MASS_DELETION, json.toString())
  }

  /** The current wait of the background `sync-on-wifi` retry (architecture 7.2), zero when it starts again. */
  suspend fun wifiBackoffMs(): Long = dao.get(WIFI_BACKOFF_MS)?.toLongOrNull() ?: 0

  suspend fun setWifiBackoffMs(ms: Long) = put(WIFI_BACKOFF_MS, ms.toString())

  private suspend fun put(key: String, value: String) = dao.put(SyncMetaEntity(key, value))

  private fun decode(text: String): PendingMassDeletion? = try {
    val json = Json.parseToJsonElement(text).jsonObject
    PendingMassDeletion(
      jobIds = json.getValue(JOB_IDS).jsonArray.map { it.jsonPrimitive.content },
      count = json.getValue(COUNT).jsonPrimitive.int,
      ofTotal = json.getValue(OF_TOTAL).jsonPrimitive.int,
      serverName = json.getValue(SERVER_NAME).jsonPrimitive.content,
    )
  } catch (unreadable: RuntimeException) {
    // A value this build did not write: no prompt, the next listing asks again if it still applies.
    null
  }

  companion object {
    const val LAST_LISTING_AT = "last_listing_at"
    const val LISTING_SERVER_ID = "listing_server_id"
    const val LISTING_LIBRARY_ID = "listing_library_id"
    const val REFERENCE_API_VERSION = "reference_api_version"
    const val MASS_DELETION = "mass_deletion"
    const val WIFI_BACKOFF_MS = "wifi_backoff_ms"

    private const val COUNT = "count"
    private const val OF_TOTAL = "of_total"
    private const val SERVER_NAME = "server_name"
    private const val JOB_IDS = "job_ids"
  }
}
