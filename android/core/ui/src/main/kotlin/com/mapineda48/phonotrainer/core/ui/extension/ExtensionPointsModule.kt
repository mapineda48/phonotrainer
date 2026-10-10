package com.mapineda48.phonotrainer.core.ui.extension

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares every extension point of work-plan 4.4 as optional, so v1-core screens inject `Optional<…>` and render
 * the fallback while it is empty. Only a v1-complete owner binds one, in its own module.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ExtensionPointsModule {
  @BindsOptionalOf
  fun mouthRenderer(): MouthRenderer

  @BindsOptionalOf
  fun recordSection(): RecordSection

  @BindsOptionalOf
  fun phraseDetailsEntry(): PhraseDetailsEntry

  @BindsOptionalOf
  fun clipTranscriptTab(): ClipTranscriptTab

  @BindsOptionalOf
  fun insightsAvailability(): InsightsAvailability

  @BindsOptionalOf
  fun clipReductionMeters(): ClipReductionMeters
}
