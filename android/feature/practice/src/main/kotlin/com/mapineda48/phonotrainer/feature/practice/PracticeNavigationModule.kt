package com.mapineda48.phonotrainer.feature.practice

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:practice to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object PracticeNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<PracticeKey> { PracticeStubScreen(navigator::navigate) }
    entry<SavedKey> { SavedStubScreen() }
  }
}
