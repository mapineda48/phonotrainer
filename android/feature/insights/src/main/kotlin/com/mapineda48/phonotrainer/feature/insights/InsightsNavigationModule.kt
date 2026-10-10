package com.mapineda48.phonotrainer.feature.insights

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:insights to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object InsightsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<InsightsKey> { InsightsStubScreen() }
  }
}
