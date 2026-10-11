package com.mapineda48.phonotrainer.shell

import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot messages as state (architecture 3.1): one is [current] at a time, the shell shows it as a snackbar and
 * reports it consumed, and the next in line follows. A message survives rotation (it lives here, not in the UI). Ids
 * are unique among the messages here: one posted with an id already current or waiting gets a fresh one from
 * [UserMessage.nextId], so consuming one message never consumes or hides another (decision I-16).
 */
@Singleton
class ShellUserMessages @Inject constructor() : UserMessages {
  private val lock = Any()
  private val waiting = ArrayDeque<UserMessage>()
  private val state = MutableStateFlow<UserMessage?>(null)

  override val current: StateFlow<UserMessage?> = state.asStateFlow()

  override fun post(message: UserMessage) = synchronized(lock) {
    val taken = state.value?.id == message.id || waiting.any { it.id == message.id }
    val unique = if (taken) message.copy(id = UserMessage.nextId()) else message
    if (state.value == null) state.value = unique else waiting.addLast(unique)
  }

  override fun consumed(id: Long) = synchronized(lock) {
    if (state.value?.id == id) state.value = waiting.removeFirstOrNull()
  }
}

/** Polite announcements (architecture 18): the shell renders [latest] in a polite live region. */
@Singleton
class ShellAnnouncer @Inject constructor() : Announcer {
  private val text = MutableStateFlow<String?>(null)

  val latest: StateFlow<String?> = text.asStateFlow()

  override fun polite(text: String) {
    this.text.value = text
  }
}
