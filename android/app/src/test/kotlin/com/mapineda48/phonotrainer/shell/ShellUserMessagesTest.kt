package com.mapineda48.phonotrainer.shell

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import org.junit.Test

/**
 * One message at a time, in order, each until the shell reports it consumed (architecture 3.1); ids come from the one
 * allocator, and a repeated id never stalls the queue (decision I-16).
 */
class ShellUserMessagesTest {
  @Test
  fun postingAKindTakesAFreshIdEveryTime() {
    val messages = ShellUserMessages()
    val first = messages.post(UserMessageKind.CLIP_GONE)
    val second = messages.post(UserMessageKind.CANCEL_UNDONE, listOf("A walk in the park"))

    assertThat(second).isNotEqualTo(first)
    assertThat(messages.current.value).isEqualTo(UserMessage(first, UserMessageKind.CLIP_GONE))
    messages.consumed(first)
    assertThat(messages.current.value).isEqualTo(UserMessage(second, UserMessageKind.CANCEL_UNDONE, listOf("A walk in the park")))
  }

  @Test
  fun aMessagePostedWithAnIdAlreadyWaitingGetsAnotherOne() {
    val messages = ShellUserMessages()
    messages.post(UserMessage(5, UserMessageKind.CLIP_GONE))
    messages.post(UserMessage(5, UserMessageKind.REQUEST_REMOVED))

    messages.consumed(5)
    val next = checkNotNull(messages.current.value)
    // The shell shows a message until it reports that id consumed; a second message with the consumed id would
    // never be shown, or would be consumed with the first.
    assertThat(next.kind).isEqualTo(UserMessageKind.REQUEST_REMOVED)
    assertThat(next.id).isNotEqualTo(5L)
    messages.consumed(5)
    assertThat(messages.current.value).isEqualTo(next)
    messages.consumed(next.id)
    assertThat(messages.current.value).isNull()
  }

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
