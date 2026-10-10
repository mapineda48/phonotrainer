package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:learn to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object LearnNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<LearnKey> { LearnStubScreen(navigator::navigate) }
    entry<PhenomenonKey> { key -> PhenomenonStubScreen(key, navigator::navigate) }
    entry<IpaChartKey> { IpaChartStubScreen() }
    entry<GlossaryIndexKey> { GlossaryIndexStubScreen() }
  }
}
