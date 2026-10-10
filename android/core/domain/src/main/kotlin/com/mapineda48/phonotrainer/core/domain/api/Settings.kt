package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// :core:datastore (WP-02), architecture 3.4 and 4.5.

interface SettingsRepository {
  /** The current settings; the defaults until the store has been read once. */
  val settings: StateFlow<UserSettings>

  suspend fun update(change: (UserSettings) -> UserSettings)

  /**
   * Returns once [settings] holds what the store says (WP-02 addition): the splash stays until then, so the first
   * frame already has the learner's appearance and start destination (architecture 16.5).
   */
  suspend fun awaitLoaded() {}
}

interface CoachMarkRepository {
  fun shouldShow(id: CoachMarkId): Flow<Boolean>

  suspend fun dismiss(id: CoachMarkId)

  suspend fun resetAll()
}
