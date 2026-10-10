package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob

/** The outcome of one call to the computer (work-plan 4.3). */
sealed interface ApiResult<out T> {
  data class Ok<T>(val value: T, val status: Int) : ApiResult<T>

  /** Server-contract 8.1, with the optional fields of the error body (D-14). */
  data class Refused(
    val status: Int,
    val code: String,
    val detail: String,
    /** already_finished (cancel), done (upload declaration). */
    val job: RemoteJob? = null,
    /** offset_mismatch (complete). */
    val offset: Long? = null,
    /** clock_skew. */
    val serverTime: Long? = null,
    /** 429 Retry-After. */
    val retryAfterS: Int? = null,
  ) : ApiResult<Nothing>

  /** No answer, the guard, or a malformed answer. */
  data class Failed(val error: AppError.Computer) : ApiResult<Nothing>
}

/** A value and whether this call created it (201) or found it (200, idempotent replay). */
data class Created<T>(val value: T, val created: Boolean)
