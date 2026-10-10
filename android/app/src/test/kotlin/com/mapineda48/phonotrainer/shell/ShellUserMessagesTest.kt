package com.mapineda48.phonotrainer.shell

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import org.junit.Test

/** One message at a time, in order, each until the shell reports it consumed (architecture 3.1). */
class ShellUserMessagesTest {
  @Test
  fun messagesWaitTheirTurn() {
    val messages = ShellUserMessages()
    val first = UserMessage(1, UserMessageKind.CLIP_GONE)
    val second = UserMessage(2, UserMessageKind.CANCEL_UNDONE, listOf("A walk in the park"))

    messages.post(first)
    messages.post(second)
    assertThat(messages.current.value).isEqualTo(first)

    messages.consumed(2)
    assertThat(messages.current.value).isEqualTo(first)
    messages.consumed(1)
    assertThat(messages.current.value).isEqualTo(second)
    messages.consumed(2)
    assertThat(messages.current.value).isNull()
  }
}
