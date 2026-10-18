package com.mapineda48.phonotrainer.feature.connect

import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes Connect (P-06, spec F-02) and Connection details (P-07) to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object ConnectNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<ConnectKey> { key -> ConnectRoute(key, navigator) }
    entry<ConnectionDetailsKey> { ConnectionDetailsRoute(navigator) }
  }
}
