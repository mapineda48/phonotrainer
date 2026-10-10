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

/** Contributes the destinations of :feature:settings to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object SettingsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<SettingsKey> { SettingsStubScreen(navigator::navigate) }
    entry<StorageKey> { StorageStubScreen() }
    entry<YourComputerKey> { YourComputerStubScreen() }
    entry<NotificationSettingsKey> { NotificationSettingsStubScreen() }
    entry<AboutKey> { AboutStubScreen(navigator::navigate) }
    entry<LicencesKey> { LicencesStubScreen() }
    entry<HelpKey> { HelpStubScreen(navigator::navigate) }
  }
}
