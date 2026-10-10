package com.mapineda48.phonotrainer.feature.requests

import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:requests to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object RequestsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<RequestsKey> { RequestsStubScreen(navigator::navigate) }
    entry<RequestDetailKey> { RequestDetailStubScreen() }
    entry<AddClipKey> { AddClipStubScreen() }
  }
}
