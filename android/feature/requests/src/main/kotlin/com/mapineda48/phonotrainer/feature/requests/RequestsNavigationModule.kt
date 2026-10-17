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

/** Contributes Requests (P-03), the request detail (P-04) and "Add a clip" (P-05) to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object RequestsNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<RequestsKey> { RequestsRoute(navigator) }
    entry<RequestDetailKey> { key -> RequestDetailRoute(key, navigator) }
    entry<AddClipKey> { key -> AddClipRoute(key, navigator) }
  }
}
