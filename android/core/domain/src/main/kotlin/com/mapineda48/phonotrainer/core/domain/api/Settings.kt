package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// :core:datastore (WP-02), architecture 3.4 and 4.5.

interface SettingsRepository {
  val settings: StateFlow<UserSettings>

  suspend fun update(change: (UserSettings) -> UserSettings)
}

interface CoachMarkRepository {
  fun shouldShow(id: CoachMarkId): Flow<Boolean>

  suspend fun dismiss(id: CoachMarkId)

  suspend fun resetAll()
}
