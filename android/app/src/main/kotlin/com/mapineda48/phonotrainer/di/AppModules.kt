package com.mapineda48.phonotrainer.di

import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.di.DefaultDispatcher
import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.shell.ShellAnnouncer
import com.mapineda48.phonotrainer.shell.ShellUserMessages
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The clock (architecture 3.3). Its name is frozen at B1: the end-to-end set replaces it with `TestClockModule`
 * (architecture 21.5).
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
  @Provides
  fun clock(): Clock = SystemClock
}

/** The device's wall clock and time zone. */
internal object SystemClock : Clock {
  override fun now(): Instant = Instant.now()

  override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** The concurrency of architecture 3.3: one application scope and the injected dispatchers. */
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
  @Provides
  @Singleton
  @ApplicationScope
  fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher)

  @Provides
  @IoDispatcher
  fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

  @Provides
  @DefaultDispatcher
  fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

/** The app-level services the shell implements (architecture 3.1, 18). */
@Module
@InstallIn(SingletonComponent::class)
interface ShellModule {
  @Binds
  fun userMessages(impl: ShellUserMessages): UserMessages

  @Binds
  fun announcer(impl: ShellAnnouncer): Announcer
}
