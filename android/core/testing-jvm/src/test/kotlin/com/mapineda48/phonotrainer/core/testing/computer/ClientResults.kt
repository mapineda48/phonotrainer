package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import java.util.UUID

// Readers of FakeComputerClient's results for the tests: each fails with the whole result when it has another shape.

internal fun <T> ApiResult<T>.ok(): T = (this as? ApiResult.Ok)?.value ?: throw AssertionError("expected Ok, got $this")

internal fun ApiResult<*>.status(): Int = when (this) {
  is ApiResult.Ok -> status
  is ApiResult.Refused -> status
  is ApiResult.Failed -> throw AssertionError("expected an answer, got $this")
}

internal fun ApiResult<*>.refused(): ApiResult.Refused = this as? ApiResult.Refused
  ?: throw AssertionError("expected Refused, got $this")

internal fun ApiResult<*>.failure(): AppError.Computer = (this as? ApiResult.Failed)?.error
  ?: throw AssertionError("expected Failed, got $this")

internal fun newRequestKey(): String = UUID.randomUUID().toString()
