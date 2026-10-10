package com.mapineda48.phonotrainer.feature.home

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:home to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object HomeNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<HomeKey> { HomeStubScreen(navigator::navigate) }
  }
}
