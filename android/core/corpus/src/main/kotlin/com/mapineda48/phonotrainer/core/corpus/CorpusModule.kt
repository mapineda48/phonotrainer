package com.mapineda48.phonotrainer.core.corpus

import android.content.Context
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ShortRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipIndexer
import com.mapineda48.phonotrainer.core.domain.spi.ClipLeases
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The corpus's part of clip removal (architecture 7.7): when the sync engine removes clips from this phone (removed by
 * the learner, deleted on the computer, or their files damaged), their derived rows and parsed copies go too, each as
 * soon as no screen holds a lease on it.
 */
internal class CorpusRemovalHook @Inject constructor(private val indexer: RoomClipIndexer) : ClipRemovalHook {
  override suspend fun onClipsRemoved(ids: Set<JobId>) = indexer.drop(ids)
}

/** `:core:corpus`'s bindings (architecture 3.4, 9.5; work-plan 4.2). */
@Module
@InstallIn(SingletonComponent::class)
internal interface CorpusModule {
  @Binds
  fun content(impl: RoomClipContentRepository): ClipContentRepository

  @Binds
  fun shorts(impl: RoomShortRepository): ShortRepository

  @Binds
  fun occurrences(impl: RoomOccurrenceRepository): OccurrenceRepository

  @Binds
  fun indexer(impl: RoomClipIndexer): ClipIndexer

  @Binds
  fun leases(impl: ClipLeaseRegistry): ClipLeases

  @Binds
  @IntoSet
  fun removalHook(impl: CorpusRemovalHook): ClipRemovalHook
}

/** Where the clip files are: the app's `filesDir` (architecture 4.6). */
@Module
@InstallIn(SingletonComponent::class)
internal object CorpusFilesModule {
  @Provides
  @Singleton
  fun files(@ApplicationContext context: Context): CorpusFiles = CorpusFiles(context.filesDir)
}
