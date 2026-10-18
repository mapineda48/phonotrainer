package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.domain.api.PracticeRepository
import com.mapineda48.phonotrainer.core.domain.api.ProgressRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import com.mapineda48.phonotrainer.core.domain.spi.StudyProtection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** `:core:study`'s bindings (architecture 3.4, 10; work-plan 4.2): the study repositories and its two SPIs. */
@Module
@InstallIn(SingletonComponent::class)
internal interface StudyModule {
  @Binds
  fun progress(impl: RoomProgressRepository): ProgressRepository

  @Binds
  fun sets(impl: RoomSetRepository): SetRepository

  @Binds
  fun practice(impl: RoomPracticeRepository): PracticeRepository

  @Binds
  fun saved(impl: RoomSavedRepository): SavedRepository

  @Binds
  fun protection(impl: RoomStudyProtection): StudyProtection

  @Binds
  @IntoSet
  fun removalHook(impl: StudyRemovalHook): ClipRemovalHook
}
