package com.mapineda48.phonotrainer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every F-22 setting in `settings.preferences_pb` (architecture 4.5). Keys are private; enums are stored by name, and
 * a value this version does not know reads as the default, so a newer build's file never breaks an older one.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
  @param:SettingsPreferences private val store: DataStore<Preferences>,
  @ApplicationScope scope: CoroutineScope,
) : SettingsRepository {
  private val state = MutableStateFlow(UserSettings())
  private val loaded = CompletableDeferred<Unit>()

  override val settings: StateFlow<UserSettings> = state.asStateFlow()

  init {
    scope.launch {
      store.data.collect { preferences ->
        state.value = preferences.toSettings()
        loaded.complete(Unit)
      }
    }
  }

  /** Writes [change] and returns once [settings] already shows it, so a caller can read its own write at once. */
  override suspend fun update(change: (UserSettings) -> UserSettings) {
    val written = store.edit { preferences -> preferences.write(change(preferences.toSettings())) }
    state.value = written.toSettings()
  }

  override suspend fun awaitLoaded() = loaded.await()

  private companion object Keys {
    val APPEARANCE = stringPreferencesKey("appearance")
    val COLORS = stringPreferencesKey("colors")
    val PATTERNS = stringPreferencesKey("patterns")
    val MOTION = stringPreferencesKey("motion")
    val DEFAULT_SPEED = floatPreferencesKey("default_speed")
    val PLAY_EACH_SHORT = booleanPreferencesKey("play_each_short")
    val END_OF_SHORT = stringPreferencesKey("end_of_short")
    val CAPTIONS = stringPreferencesKey("captions")
    val LISTEN_FIRST = booleanPreferencesKey("listen_first")
    val TRACK = stringPreferencesKey("track")
    val EXCLUDED_FROM_MIX = stringSetPreferencesKey("excluded_from_mix")
    val QUESTIONS_AFTER_SET = booleanPreferencesKey("questions_after_set")
    val LESSON_GUIDANCE = stringPreferencesKey("lesson_guidance")
    val DOWNLOAD_VIDEOS = booleanPreferencesKey("download_videos")

    /** Bytes of the storage budget; [FREE_SPACE_ONLY] for "Use free space only". */
    val STORAGE_BUDGET = longPreferencesKey("storage_budget_bytes")
    val NOTIFY_READY = booleanPreferencesKey("notify_ready")
    val NOTIFY_ATTENTION = booleanPreferencesKey("notify_attention")
    val WELCOME_DONE = booleanPreferencesKey("welcome_done")
    val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")

    const val FREE_SPACE_ONLY = -1L

    inline fun <reified E : Enum<E>> Preferences.enumOr(key: Preferences.Key<String>, default: E): E =
      this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    fun Preferences.toSettings(): UserSettings {
      val defaults = UserSettings()
      return UserSettings(
        appearance = enumOr(APPEARANCE, defaults.appearance),
        colors = enumOr(COLORS, defaults.colors),
        patterns = enumOr(PATTERNS, defaults.patterns),
        motion = enumOr(MOTION, defaults.motion),
        defaultSpeed = this[DEFAULT_SPEED] ?: defaults.defaultSpeed,
        playEachShort = this[PLAY_EACH_SHORT] ?: defaults.playEachShort,
        endOfShort = enumOr(END_OF_SHORT, defaults.endOfShort),
        captions = enumOr(CAPTIONS, defaults.captions),
        listenFirst = this[LISTEN_FIRST] ?: defaults.listenFirst,
        track = enumOr(TRACK, defaults.track),
        excludedFromMix = this[EXCLUDED_FROM_MIX]?.map(::JobId)?.toSet() ?: defaults.excludedFromMix,
        questionsAfterSet = this[QUESTIONS_AFTER_SET] ?: defaults.questionsAfterSet,
        lessonGuidance = enumOr(LESSON_GUIDANCE, defaults.lessonGuidance),
        downloadVideos = this[DOWNLOAD_VIDEOS] ?: defaults.downloadVideos,
        storageBudget = when (val bytes = this[STORAGE_BUDGET]) {
          null -> defaults.storageBudget
          FREE_SPACE_ONLY -> StorageBudget.FreeSpaceOnly
          else -> StorageBudget.Limit(bytes)
        },
        notifyReady = this[NOTIFY_READY] ?: defaults.notifyReady,
        notifyAttention = this[NOTIFY_ATTENTION] ?: defaults.notifyAttention,
        welcomeDone = this[WELCOME_DONE] ?: defaults.welcomeDone,
        notificationsAsked = this[NOTIFICATIONS_ASKED] ?: defaults.notificationsAsked,
      )
    }

    fun MutablePreferences.write(settings: UserSettings) {
      this[APPEARANCE] = settings.appearance.name
      this[COLORS] = settings.colors.name
      this[PATTERNS] = settings.patterns.name
      this[MOTION] = settings.motion.name
      this[DEFAULT_SPEED] = settings.defaultSpeed
      this[PLAY_EACH_SHORT] = settings.playEachShort
      this[END_OF_SHORT] = settings.endOfShort.name
      this[CAPTIONS] = settings.captions.name
      this[LISTEN_FIRST] = settings.listenFirst
      this[TRACK] = settings.track.name
      this[EXCLUDED_FROM_MIX] = settings.excludedFromMix.map { it.value }.toSet()
      this[QUESTIONS_AFTER_SET] = settings.questionsAfterSet
      this[LESSON_GUIDANCE] = settings.lessonGuidance.name
      this[DOWNLOAD_VIDEOS] = settings.downloadVideos
      this[STORAGE_BUDGET] = when (val budget = settings.storageBudget) {
        StorageBudget.FreeSpaceOnly -> FREE_SPACE_ONLY
        is StorageBudget.Limit -> budget.bytes
      }
      this[NOTIFY_READY] = settings.notifyReady
      this[NOTIFY_ATTENTION] = settings.notifyAttention
      this[WELCOME_DONE] = settings.welcomeDone
      this[NOTIFICATIONS_ASKED] = settings.notificationsAsked
    }
  }
}
