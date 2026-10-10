package com.mapineda48.phonotrainer.core.connectivity

import com.mapineda48.phonotrainer.core.domain.model.Datagram
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.NetworkSnapshot
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The network monitor and the discovery transport (architecture 6.1). Its name is frozen at B1: the end-to-end set
 * replaces it with `TestConnectivitySeamsModule` (a scripted monitor and `FakeDiscovery`, architecture 21.5).
 */
@Module
@InstallIn(SingletonComponent::class)
interface ConnectivitySeamsModule {
  @Binds
  fun networkMonitor(impl: StubNetworkMonitor): NetworkMonitor

  @Binds
  fun discoveryTransport(impl: StubDiscoveryTransport): DiscoveryTransport
}

/** Stub: no network. WP-07 replaces it with the `ConnectivityManager` callback. */
@Singleton
class StubNetworkMonitor @Inject constructor() : NetworkMonitor {
  override val network: StateFlow<NetworkSnapshot> = MutableStateFlow(
    NetworkSnapshot(NetworkKind.NONE, networkHandle = null, ipv4 = null, prefixLength = null, vpnActive = false, validated = false),
  )
}

/** Stub: nothing answers. */
class StubDiscoveryTransport @Inject constructor() : DiscoveryTransport {
  override fun probe(datagram: ByteArray, broadcastTargets: List<String>, schedule: List<Duration>, listenFor: Duration): Flow<Datagram> =
    emptyFlow()
}
