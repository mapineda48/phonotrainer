package com.mapineda48.phonotrainer.core.common.id

import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.util.UUID

/**
 * The app's own ids (architecture 4.2, 8.1): a request's id is a random UUID and its first idempotency key, a re-run
 * gets a new one, a recording is named by one. The computer accepts a UUID in canonical text form, case-insensitive
 * (server-contract 6, `client_request_id`); the app always writes lower case.
 */
object Uuids {
  private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

  /** A random (version 4) UUID in lower-case canonical form. */
  fun random(): String = UUID.randomUUID().toString()

  /** Whether [text] is a UUID in canonical text form (8-4-4-4-12 hex digits, either case). */
  fun isCanonical(text: String?): Boolean = text != null && CANONICAL.matches(text)
}

/** Where ids come from; tests pass a predictable one. */
fun interface UuidSource {
  fun next(): String

  /** A new request id. */
  fun newRequestId(): RequestId = RequestId(next())

  companion object {
    /** Random UUIDs: the source of every build. */
    val RANDOM: UuidSource = UuidSource(Uuids::random)
  }
}
