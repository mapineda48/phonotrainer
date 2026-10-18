package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes Practice (T4) and Saved (P-11) to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object PracticeNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<PracticeKey> { key -> PracticeRoute(key, navigator) }
    entry<SavedKey> { SavedRoute(navigator) }
  }
}
