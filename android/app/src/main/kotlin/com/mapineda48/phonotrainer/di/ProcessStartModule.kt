package com.mapineda48.phonotrainer.di

import com.mapineda48.phonotrainer.core.common.di.ProcessStart
import com.mapineda48.phonotrainer.core.domain.api.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

/**
 * The instant this process started (architecture 4.6): read from the injected [Clock] once, when `PhonoApplication`
 * is injected in `Application.onCreate`, before any activity, worker or receiver of the app is created. End-to-end
 * tests read it from their `TestClock`, so rows they write compare with it on the same clock.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessStartModule {
  @Provides
  @Singleton
  @ProcessStart
  fun processStart(clock: Clock): Instant = clock.now()
}
