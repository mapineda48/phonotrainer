package com.mapineda48.phonotrainer.feature.settings

import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes Settings and its sub-pages to the shell (architecture 16.3; design 10.2 P-12). */
@Module
@InstallIn(SingletonComponent::class)
object SettingsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<SettingsKey> { SettingsRoute(navigator) }
    entry<StorageKey> { StorageRoute(navigator) }
    entry<YourComputerKey> { YourComputerRoute(navigator) }
    entry<NotificationSettingsKey> { NotificationSettingsRoute(navigator) }
    entry<AboutKey> { AboutRoute(navigator) }
    entry<LicencesKey> { LicencesRoute(navigator) }
    entry<HelpKey> { HelpRoute(navigator) }
  }
}
