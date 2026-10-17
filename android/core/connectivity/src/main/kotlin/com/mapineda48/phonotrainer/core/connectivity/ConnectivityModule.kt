package com.mapineda48.phonotrainer.core.connectivity

import com.mapineda48.phonotrainer.core.connectivity.discovery.DatagramBinder
import com.mapineda48.phonotrainer.core.connectivity.pairing.DefaultPairingRepository
import com.mapineda48.phonotrainer.core.connectivity.pairing.ForgetCleanup
import com.mapineda48.phonotrainer.core.connectivity.pairing.RoomForgetCleanup
import com.mapineda48.phonotrainer.core.connectivity.platform.AndroidBackgroundRestrictionSource
import com.mapineda48.phonotrainer.core.connectivity.platform.AppForeground
import com.mapineda48.phonotrainer.core.connectivity.platform.AppVersion
import com.mapineda48.phonotrainer.core.connectivity.platform.BackgroundRestrictionSource
import com.mapineda48.phonotrainer.core.connectivity.platform.ComputerWork
import com.mapineda48.phonotrainer.core.connectivity.platform.NetworkDatagramBinder
import com.mapineda48.phonotrainer.core.connectivity.platform.PackageAppVersion
import com.mapineda48.phonotrainer.core.connectivity.platform.ProcessForeground
import com.mapineda48.phonotrainer.core.connectivity.platform.WorkManagerComputerWork
import com.mapineda48.phonotrainer.core.connectivity.status.DefaultComputerStatusRepository
import com.mapineda48.phonotrainer.core.connectivity.status.MachineChannelLiveness
import com.mapineda48.phonotrainer.core.connectivity.status.ResourceStatusWords
import com.mapineda48.phonotrainer.core.connectivity.status.StatusWords
import com.mapineda48.phonotrainer.core.domain.api.ChannelLiveness
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The repositories of architecture 6 and the platform pieces behind them (WP-07). */
@Module
@InstallIn(SingletonComponent::class)
internal interface ConnectivityModule {
  @Binds
  fun status(impl: DefaultComputerStatusRepository): ComputerStatusRepository

  @Binds
  fun pairing(impl: DefaultPairingRepository): PairingRepository

  @Binds
  fun liveness(impl: MachineChannelLiveness): ChannelLiveness

  @Binds
  fun datagramBinder(impl: NetworkDatagramBinder): DatagramBinder

  @Binds
  fun foreground(impl: ProcessForeground): AppForeground

  @Binds
  fun appVersion(impl: PackageAppVersion): AppVersion

  @Binds
  fun backgroundRestrictions(impl: AndroidBackgroundRestrictionSource): BackgroundRestrictionSource

  @Binds
  fun computerWork(impl: WorkManagerComputerWork): ComputerWork

  @Binds
  fun forgetCleanup(impl: RoomForgetCleanup): ForgetCleanup

  @Binds
  fun statusWords(impl: ResourceStatusWords): StatusWords
}
