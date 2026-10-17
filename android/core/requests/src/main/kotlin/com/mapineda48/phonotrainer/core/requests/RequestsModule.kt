package com.mapineda48.phonotrainer.core.requests

import android.content.Context
import com.mapineda48.phonotrainer.core.analysis.links.LinksV1
import com.mapineda48.phonotrainer.core.common.id.UuidSource
import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.requests.android.AndroidFreeSpace
import com.mapineda48.phonotrainer.core.requests.android.ContentSourceReader
import com.mapineda48.phonotrainer.core.requests.android.MediaAudioExtraction
import com.mapineda48.phonotrainer.core.requests.android.ProcessVisibility
import com.mapineda48.phonotrainer.core.requests.android.ResourceRequestWords
import com.mapineda48.phonotrainer.core.requests.android.WorkManagerRequestWork
import com.mapineda48.phonotrainer.core.requests.store.AppVisibility
import com.mapineda48.phonotrainer.core.requests.store.QueueFiles
import com.mapineda48.phonotrainer.core.requests.store.QueueIds
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.store.RequestWords
import com.mapineda48.phonotrainer.core.requests.intake.AudioExtraction
import com.mapineda48.phonotrainer.core.requests.intake.FreeSpace
import com.mapineda48.phonotrainer.core.requests.intake.SourceReader
import com.mapineda48.phonotrainer.core.requests.reconcile.QueueReconciler
import com.mapineda48.phonotrainer.core.requests.send.QueueSender
import com.mapineda48.phonotrainer.core.requests.send.UploadPause
import com.mapineda48.phonotrainer.core.requests.startup.RequestsStartupTask
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The device queue's bindings (architecture 8, work-plan WP-09): the repository, the link rules of `:core:analysis`,
 * the sender and reconciler the sync engine calls, and the start-up copy checks.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface RequestsModule {
  @Binds
  fun requests(impl: RoomRequestRepository): RequestRepository

  @Binds
  fun linkParser(impl: LinksV1): LinkParser

  @Binds
  fun sender(impl: QueueSender): RequestSender

  @Binds
  fun reconciler(impl: QueueReconciler): RequestReconciler

  @Binds
  @IntoSet
  fun copyChecks(impl: RequestsStartupTask): StartupTask

  @Binds
  fun sourceReader(impl: ContentSourceReader): SourceReader

  @Binds
  fun freeSpace(impl: AndroidFreeSpace): FreeSpace

  @Binds
  fun audioExtraction(impl: MediaAudioExtraction): AudioExtraction

  @Binds
  fun work(impl: WorkManagerRequestWork): RequestWork

  @Binds
  fun visibility(impl: ProcessVisibility): AppVisibility

  @Binds
  fun words(impl: ResourceRequestWords): RequestWords

  companion object {
    @Provides
    @Singleton
    fun files(@ApplicationContext context: Context): QueueFiles = QueueFiles(context.filesDir)

    @Provides
    @QueueIds
    fun ids(): UuidSource = UuidSource.RANDOM

    @Provides
    fun pause(): UploadPause = UploadPause.DELAY
  }
}
