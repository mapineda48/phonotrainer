package com.mapineda48.phonotrainer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Coach marks seen, in the settings file (architecture 4.5): backed up, so a restored phone does not repeat them. */
@Singleton
class DataStoreCoachMarkRepository @Inject constructor(
  @param:SettingsPreferences private val store: DataStore<Preferences>,
) : CoachMarkRepository {
  override fun shouldShow(id: CoachMarkId): Flow<Boolean> =
    store.data.map { id.value !in it[SEEN].orEmpty() }.distinctUntilChanged()

  override suspend fun dismiss(id: CoachMarkId) {
    store.edit { it[SEEN] = it[SEEN].orEmpty() + id.value }
  }

  override suspend fun resetAll() {
    store.edit { it.remove(SEEN) }
  }

  private companion object {
    val SEEN = stringSetPreferencesKey("coach_marks_seen")
  }
}
