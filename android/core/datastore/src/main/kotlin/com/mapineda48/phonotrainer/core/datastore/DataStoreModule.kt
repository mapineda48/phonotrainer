package com.mapineda48.phonotrainer.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.credential.CredentialCipher
import com.mapineda48.phonotrainer.core.datastore.credential.KeystoreCredentialCipher
import com.mapineda48.phonotrainer.core.datastore.pairing.DataStorePairingStore
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus

/** `datastore/settings.preferences_pb`: backed up (architecture 4.5, 20.1). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsPreferences

/** `datastore/pairing.preferences_pb`: never backed up; holds only the encrypted credential (architecture 20.3). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PairingPreferences

/** File names of architecture 4.5; the backup rules of :app name the settings file. */
object DataStoreFiles {
  const val SETTINGS = "settings"
  const val PAIRING = "pairing"
}

/**
 * The two files (architecture 4.5). A file that is not a whole preferences file (a partial or foreign Auto Backup
 * restore of the settings file, a write cut short) is replaced by an empty one on its first read, so the app starts as
 * on a fresh install for that file and never crashes (wave-1 architecture review, finding 4). Only that file's
 * contents are lost; the databases, the queue and the clip files are separate files.
 * - **settings**: every setting at its default (a missing value reads as its default, as an unknown one does), the
 *   Welcome and every coach mark shown again as after "Restart the tour", and the notification question asked again
 *   at the next request (spec F-01, F-22). No message: the learner can set everything again in Settings.
 * - **pairing**: the phone reads as not paired, never half-read (a record needs every field, [PairingStore]): Home
 *   shows "Not connected to a computer" + "Connect" (spec F-03), the share sheet says the request waits on the phone
 *   until it connects (spec 8.4), and pairing again with the same computer keeps everything (spec 7.4); the computer
 *   sees a new phone (architecture 6.3). The credential key stays in the keystore; the next pairing seals with it.
 *
 * A read that fails with any other `IOException` reads as an empty file too, without replacing it (`dataOrEmpty`).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
  @Provides
  @Singleton
  @SettingsPreferences
  fun settingsDataStore(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    log: PhonoLog,
  ): DataStore<Preferences> = preferencesStore(scope + io, log, CORRUPT_SETTINGS) {
    context.preferencesDataStoreFile(DataStoreFiles.SETTINGS)
  }

  @Provides
  @Singleton
  @PairingPreferences
  fun pairingDataStore(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    log: PhonoLog,
  ): DataStore<Preferences> = preferencesStore(scope + io, log, CORRUPT_PAIRING) {
    context.preferencesDataStoreFile(DataStoreFiles.PAIRING)
  }

  /** Logged (event code and error class only, architecture 20.4) when a damaged file is replaced by an empty one. */
  private const val CORRUPT_SETTINGS: String = "datastore.settings_corrupt"
  private const val CORRUPT_PAIRING: String = "datastore.pairing_corrupt"

  private fun preferencesStore(
    scope: CoroutineScope,
    log: PhonoLog,
    corruptEvent: String,
    file: () -> File,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
      log.w(corruptEvent, error)
      emptyPreferences()
    },
    scope = scope,
    produceFile = file,
  )
}

/**
 * The typed wrappers over the two files. Kept apart from [DataStoreModule], which the end-to-end tests replace
 * (`TestDataStoreModule`), so the stores stay real there.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DataStoreBindings {
  @Binds
  fun settings(impl: DataStoreSettingsRepository): SettingsRepository

  @Binds
  fun coachMarks(impl: DataStoreCoachMarkRepository): CoachMarkRepository

  @Binds
  fun pairing(impl: DataStorePairingStore): PairingStore

  @Binds
  fun credentialCipher(impl: KeystoreCredentialCipher): CredentialCipher
}
