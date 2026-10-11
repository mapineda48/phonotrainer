package com.mapineda48.phonotrainer.core.testing.fake.app

import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The F-23 question, settable (work-plan 4.6): set [state] to arrange whether it is offered; [answers] records
 * "not_now" and "notify_me" in order, [promptResults] the system prompt's answers.
 */
class FakeNotificationPermissionAsker(
  shouldAsk: Boolean = true,
  override val systemPermission: String? = "android.permission.POST_NOTIFICATIONS",
) : NotificationPermissionAsker {
  val state = MutableStateFlow(shouldAsk)
  val answers = mutableListOf<String>()
  val promptResults = mutableListOf<Boolean>()

  override val shouldAsk: StateFlow<Boolean> = state

  override suspend fun notNow() {
    answers += "not_now"
    state.value = false
  }

  override suspend fun notifyMe(): Boolean {
    answers += "notify_me"
    state.value = false
    return systemPermission != null
  }

  override fun onSystemPromptResult(granted: Boolean) {
    promptResults += granted
  }
}
