package com.mapineda48.phonotrainer.feature.onboarding

import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the Welcome to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<WelcomeKey> { WelcomeRoute(onDone = navigator::back) }
  }
}
