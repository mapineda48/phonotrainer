package com.mapineda48.phonotrainer.core.sync

import android.content.Context
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.sync.engine.ChannelState
import com.mapineda48.phonotrainer.core.sync.engine.DownloadRunner
import com.mapineda48.phonotrainer.core.sync.engine.ForegroundState
import com.mapineda48.phonotrainer.core.sync.engine.ProcessForegroundState
import com.mapineda48.phonotrainer.core.sync.engine.SyncEngine
import com.mapineda48.phonotrainer.core.sync.engine.SyncOrchestrator
import com.mapineda48.phonotrainer.core.sync.engine.SyncPasses
import com.mapineda48.phonotrainer.core.sync.engine.SyncWork
import com.mapineda48.phonotrainer.core.sync.live.JobChannelSession
import com.mapineda48.phonotrainer.core.sync.repository.RoomClipRepository
import com.mapineda48.phonotrainer.core.sync.startup.StartupCheck
import com.mapineda48.phonotrainer.core.sync.startup.SyncDirs
import com.mapineda48.phonotrainer.core.sync.storage.AndroidDeviceSpace
import com.mapineda48.phonotrainer.core.sync.storage.DeviceSpace
import com.mapineda48.phonotrainer.core.sync.storage.RoomStorageRepository
import com.mapineda48.phonotrainer.core.sync.transfer.ClipFiles
import com.mapineda48.phonotrainer.core.sync.transfer.Downloader
import com.mapineda48.phonotrainer.core.sync.work.WorkManagerSyncWork
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The sync engine's public bindings (architecture 3.4, 7): exactly the five interfaces of `:core:domain` it provides.
 * Its name and these bindings are what `e2e/library/LibraryStateTest` uninstalls and replaces with fakes (WP-16's
 * per-class `@UninstallModules`), so everything else `:core:sync` binds lives in [SyncInternalModule].
 */
@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
  @Binds
  fun controller(impl: SyncEngine): SyncController

  @Binds
  fun trigger(impl: SyncEngine): SyncTrigger

  @Binds
  fun clips(impl: RoomClipRepository): ClipRepository

  @Binds
  fun storage(impl: RoomStorageRepository): StorageRepository

  @Binds
  fun startupGate(impl: StartupCheck): StartupGate
}

/** The seams inside `:core:sync` (its workers, the foreground session, the files and the phone's storage). */
@Module
@InstallIn(SingletonComponent::class)
internal interface SyncInternalModule {
  @Binds
  fun passes(impl: SyncOrchestrator): SyncPasses

  @Binds
  fun work(impl: WorkManagerSyncWork): SyncWork

  @Binds
  fun foreground(impl: ProcessForegroundState): ForegroundState

  @Binds
  fun channel(impl: JobChannelSession): ChannelState

  @Binds
  fun space(impl: AndroidDeviceSpace): DeviceSpace

  companion object {
    @Provides
    fun runner(downloader: Downloader): DownloadRunner = DownloadRunner { progress -> downloader.run(progress) }

    @Provides
    @Singleton
    fun files(@ApplicationContext context: Context): ClipFiles = ClipFiles(context.filesDir)

    @Provides
    fun dirs(@ApplicationContext context: Context): SyncDirs = SyncDirs(context.filesDir, context.cacheDir)
  }
}
