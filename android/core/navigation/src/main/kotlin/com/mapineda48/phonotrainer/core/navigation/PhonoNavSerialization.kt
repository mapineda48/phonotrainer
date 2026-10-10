package com.mapineda48.phonotrainer.core.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The one serializers configuration of every saved back stack (architecture 16.1): each key is registered as a
 * polymorphic [NavKey], so stacks survive rotation and process death. A key missing here fails the saved-state round
 * trip of `PhonoNavSerializationTest`.
 */
object PhonoNavSerialization {
  val module: SerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
      subclass(HomeKey::class)
      subclass(WatchKey::class)
      subclass(LearnKey::class)
      subclass(PracticeKey::class)
      subclass(LibraryKey::class)
      subclass(ClipKey::class)
      subclass(RequestsKey::class)
      subclass(RequestDetailKey::class)
      subclass(AddClipKey::class)
      subclass(ConnectKey::class)
      subclass(ConnectionDetailsKey::class)
      subclass(PhenomenonKey::class)
      subclass(IpaChartKey::class)
      subclass(InsightsKey::class)
      subclass(SavedKey::class)
      subclass(SettingsKey::class)
      subclass(StorageKey::class)
      subclass(YourComputerKey::class)
      subclass(NotificationSettingsKey::class)
      subclass(AboutKey::class)
      subclass(LicencesKey::class)
      subclass(HelpKey::class)
      subclass(WelcomeKey::class)
      subclass(GlossaryIndexKey::class)
      subclass(LessonKey::class)
      subclass(PhraseDetailsKey::class)
    }
  }

  /** For `rememberNavBackStack(PhonoNavSerialization.configuration, …)`. */
  val configuration: SavedStateConfiguration = SavedStateConfiguration { serializersModule = module }

  private val json = Json { serializersModule = module }

  /** A [NavTarget] as the string extra of an internal intent (architecture 16.4). */
  fun encode(target: NavTarget): String = json.encodeToString(NavTarget.serializer(), target)

  /** The [NavTarget] of an internal intent's extra, or null when it is absent or not one. */
  fun decode(text: String?): NavTarget? =
    text?.let { runCatching { json.decodeFromString(NavTarget.serializer(), it) }.getOrNull() }

  /**
   * A stable request code for a notification's `PendingIntent` (architecture 13): identity ignores extras, so each
   * target gets its own code (and its own `data` URI).
   */
  fun requestCode(target: NavTarget): Int = encode(target).hashCode()
}
