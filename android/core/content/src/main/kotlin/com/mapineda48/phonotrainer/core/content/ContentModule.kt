package com.mapineda48.phonotrainer.core.content

import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The bindings of :core:content (architecture 2.7: each module keeps its own Hilt module). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContentModule {
  @Binds
  internal abstract fun didacticContent(impl: DidacticContentImpl): DidacticContent

  @Binds
  internal abstract fun referenceRepository(impl: ReferenceRepositoryImpl): ReferenceRepository

  @Binds
  internal abstract fun bundledAssets(impl: AndroidBundledAssets): BundledAssets
}
