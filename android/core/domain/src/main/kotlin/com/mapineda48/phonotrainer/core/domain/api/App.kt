package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

/** The one source of time (architecture 3.3); tests drive a `TestClock` with the coroutine scheduler. */
interface Clock {
  fun now(): Instant

  fun zone(): ZoneId
}

/** Requests and screens ask; :core:sync decides. */
fun interface SyncTrigger {
  fun request(reason: SyncReason)
}

/**
 * One-shot messages as state (architecture 3.1): the app shell shows [current] as a snackbar and reports it
 * [consumed]; screens never draw their own snackbar host.
 */
interface UserMessages {
  val current: StateFlow<UserMessage?>

  /** Queues [message]; its id should come from [UserMessage.nextId]. Prefer [post] with a kind. */
  fun post(message: UserMessage)

  fun consumed(id: Long)

  /** Queues a message of [kind] under a fresh id from the one allocator and returns that id (decision I-16). */
  fun post(kind: UserMessageKind, args: List<String> = emptyList()): Long {
    val id = UserMessage.nextId()
    post(UserMessage(id, kind, args))
    return id
  }
}

/** The only way to make polite TalkBack announcements, for the event kinds A-04 allows (architecture 18). */
fun interface Announcer {
  fun polite(text: String)
}
