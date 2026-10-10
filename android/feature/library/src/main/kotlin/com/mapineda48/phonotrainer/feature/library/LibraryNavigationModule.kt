package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the destinations of :feature:library to the shell (architecture 16.3). */
@Module
@InstallIn(SingletonComponent::class)
object LibraryNavigationModule {
  @Provides
  @IntoSet
  fun entries(): EntryInstaller = EntryInstaller { navigator ->
    entry<LibraryKey> { LibraryRoute(onOpenClip = { navigator.navigate(ClipKey(it)) }) }
    entry<ClipKey> { key -> ClipStubScreen(key, navigator::navigate) }
  }
}
