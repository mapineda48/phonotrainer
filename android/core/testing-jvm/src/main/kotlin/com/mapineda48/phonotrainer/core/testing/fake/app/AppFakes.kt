package com.mapineda48.phonotrainer.core.testing.fake.app

import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

// App-level fakes of work-plan 4.6 (integrator-owned).

/** In-memory settings; set [state] directly to arrange a test. */
class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
  val state = MutableStateFlow(initial)

  override val settings: StateFlow<UserSettings> = state

  override suspend fun update(change: (UserSettings) -> UserSettings) {
    state.value = change(state.value)
  }
}

/** Coach marks seen, in memory. */
class FakeCoachMarkRepository : CoachMarkRepository {
  val seen = MutableStateFlow(emptySet<CoachMarkId>())

  override fun shouldShow(id: CoachMarkId): Flow<Boolean> = seen.map { id !in it }

  override suspend fun dismiss(id: CoachMarkId) {
    seen.value += id
  }

  override suspend fun resetAll() {
    seen.value = emptySet()
  }
}

/** One message at a time, like the shell's; [posted] keeps every message ever posted. */
class FakeUserMessages : UserMessages {
  private val queue = ArrayDeque<UserMessage>()
  private val state = MutableStateFlow<UserMessage?>(null)
  val posted = mutableListOf<UserMessage>()

  override val current: StateFlow<UserMessage?> = state

  override fun post(message: UserMessage) {
    posted += message
    queue.addLast(message)
    if (state.value == null) state.value = queue.removeFirst()
  }

  override fun consumed(id: Long) {
    if (state.value?.id == id) state.value = queue.removeFirstOrNull()
  }
}

/** Records every announcement. */
class FakeAnnouncer : Announcer {
  val announced = mutableListOf<String>()

  override fun polite(text: String) {
    announced += text
  }
}

/** Records every reason a sync was asked for. */
class FakeSyncTrigger : SyncTrigger {
  val reasons = mutableListOf<SyncReason>()

  override fun request(reason: SyncReason) {
    reasons += reason
  }
}

/** Open from the start unless [open] is false; [release] opens it. */
class FakeStartupGate(open: Boolean = true) : StartupGate {
  private val done = CompletableDeferred<Unit>().apply { if (open) complete(Unit) }

  fun release() {
    done.complete(Unit)
  }

  override suspend fun awaitDone() = done.await()
}
