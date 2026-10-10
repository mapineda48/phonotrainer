package com.mapineda48.phonotrainer.feature.connect

import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:connect to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object ConnectNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<ConnectKey> { ConnectStubScreen(navigator::navigate) }
    entry<ConnectionDetailsKey> { ConnectionDetailsStubScreen() }
  }
}
