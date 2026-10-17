package com.mapineda48.phonotrainer.core.network.client

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.dto.ComputerJson
import com.mapineda48.phonotrainer.core.network.dto.JobDto
import com.mapineda48.phonotrainer.core.network.dto.toJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The error envelope of server-contract 6.1 and 8.1 (`{"detail", "code"}`) with its optional fields (work-plan 4.3,
 * D-14): `job` (already_finished, a finished upload), `offset` (offset_mismatch), `server_time` (clock_skew) and the
 * `Retry-After` header (429). A FastAPI validation list (`detail` as a list of `{"msg"}`, 422) is accepted too; a body
 * that is not JSON leaves `code` and `detail` empty, as a plain 5xx does. A `job` that fails validation is dropped,
 * never trusted.
 */
internal object Refusals {
  fun of(status: Int, body: String?, retryAfter: String?): ApiResult.Refused {
    val envelope = body?.let { text -> runCatching { ComputerJson.parseToJsonElement(text) as? JsonObject }.getOrNull() }
    return ApiResult.Refused(
      status = status,
      code = (envelope?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
      detail = detailOf(envelope?.get("detail")),
      job = (envelope?.get("job") as? JsonObject)?.let { job ->
        runCatching { ComputerJson.decodeFromJsonElement(JobDto.serializer(), job).toJob(lean = false) }.getOrNull()
      },
      offset = (envelope?.get("offset") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
      serverTime = (envelope?.get("server_time") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
      retryAfterS = retryAfter?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
    )
  }

  /** A refusal in the error model (architecture 19), for the calls that report an `AppError` (transfers). */
  fun errorOf(refusal: ApiResult.Refused): AppError.Computer = when (refusal.code) {
    "credential_rejected" -> AppError.Computer.CredentialRejected
    "clock_skew" -> AppError.Computer.ClockSkew(refusal.serverTime ?: 0L)
    "phone_access_off", "lan_forbidden" -> AppError.Computer.PhoneAccessOff
    "app_too_old" -> AppError.Computer.AppTooOld
    else -> AppError.Computer.Refused(refusal.code, refusal.detail)
  }

  private fun detailOf(detail: JsonElement?): String = when (detail) {
    null, is JsonNull -> ""
    is JsonPrimitive -> if (detail.isString) detail.content else detail.toString()
    is JsonArray -> detail.joinToString("; ") { item ->
      ((item as? JsonObject)?.get("msg") as? JsonPrimitive)?.content ?: item.toString()
    }
    else -> ""
  }
}
