package com.mapineda48.phonotrainer.core.connectivity

import com.mapineda48.phonotrainer.core.connectivity.discovery.UdpDiscoveryTransport
import com.mapineda48.phonotrainer.core.connectivity.platform.AndroidNetworkMonitor
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The network monitor and the discovery transport (architecture 6.1). Its name is frozen at B1: the end-to-end set
 * replaces it with `TestConnectivitySeamsModule` (a scripted monitor and `FakeDiscovery`, architecture 21.5), so it
 * binds these two seams and nothing else.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ConnectivitySeamsModule {
  @Binds
  fun networkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

  @Binds
  fun discoveryTransport(impl: UdpDiscoveryTransport): DiscoveryTransport
}
