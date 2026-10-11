package com.mapineda48.phonotrainer.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
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
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope + io) {
    context.preferencesDataStoreFile(DataStoreFiles.SETTINGS)
  }

  @Provides
  @Singleton
  @PairingPreferences
  fun pairingDataStore(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope + io) {
    context.preferencesDataStoreFile(DataStoreFiles.PAIRING)
  }
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
