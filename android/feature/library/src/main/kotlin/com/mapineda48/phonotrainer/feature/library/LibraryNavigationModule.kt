package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.ui.extension.ClipReductionMeters
import com.mapineda48.phonotrainer.core.ui.extension.ClipTranscriptTab
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.Optional

/**
 * Contributes the Library and the Clip page to the shell (architecture 16.3). The Clip page's v1-complete slots
 * (work-plan 4.4) are optional: absent in v1-core, so it shows Shorts and Summary only and no reduction meters.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryNavigationModule {
  @Provides
  @IntoSet
  fun entries(transcript: Optional<ClipTranscriptTab>, meters: Optional<ClipReductionMeters>): EntryInstaller {
    val slots = ClipSlots(transcript = transcript.orElse(null), meters = meters.orElse(null))
    return EntryInstaller { navigator ->
      entry<LibraryKey> { LibraryRoute(navigator) }
      entry<ClipKey> { key -> ClipRoute(key, navigator, slots) }
    }
  }
}
