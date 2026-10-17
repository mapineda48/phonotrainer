package com.mapineda48.phonotrainer.core.network

import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.channel.WebSocketJobChannel
import com.mapineda48.phonotrainer.core.network.client.HttpComputerClient
import com.mapineda48.phonotrainer.core.network.guard.CleartextGuard
import com.mapineda48.phonotrainer.core.network.http.ComputerHttp
import com.mapineda48.phonotrainer.core.network.http.HttpDebugLog
import com.mapineda48.phonotrainer.core.network.http.NetworkTimeouts
import com.mapineda48.phonotrainer.core.network.peers.ComputerPeers
import com.mapineda48.phonotrainer.core.network.peers.ComputerVerifiedPeers
import com.mapineda48.phonotrainer.core.network.peers.LearnedClockOffset
import com.mapineda48.phonotrainer.core.network.service.ApiFactory
import com.mapineda48.phonotrainer.core.network.signing.PairingRequestSigner
import com.mapineda48.phonotrainer.core.network.transfer.HttpArtifactTransfer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.ConnectionPool

/**
 * The HTTP stack of architecture 5 (WP-05b). The address policy and the binder come from [NetworkPolicyModule], which
 * the end-to-end set replaces; everything else is here and is the same in every build.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
  @Provides
  @Singleton
  fun timeouts(): NetworkTimeouts = NetworkTimeouts()

  @Provides
  @Singleton
  fun connectionPool(timeouts: NetworkTimeouts, binder: ComputerNetworkBinder): ConnectionPool =
    ComputerHttp.pool(timeouts).also { pool -> (binder as? NetworkBinding)?.addListener { pool.evictAll() } }

  @Provides
  @Singleton
  fun cleartextGuard(peers: ComputerPeers, policy: AddressPolicy): CleartextGuard = CleartextGuard(peers, policy)

  @Provides
  @Singleton
  fun computerHttp(
    guard: CleartextGuard,
    store: PairingStore,
    clock: Clock,
    clockOffset: ClockOffset,
    verified: VerifiedPeers,
    binder: ComputerNetworkBinder,
    pool: ConnectionPool,
    timeouts: NetworkTimeouts,
    log: PhonoLog,
  ): ComputerHttp = ComputerHttp.create(
    guard = guard,
    signer = PairingRequestSigner(store, clock, clockOffset),
    verified = verified,
    clockOffset = clockOffset,
    binder = binder,
    pool = pool,
    timeouts = timeouts,
    debugLog = HttpDebugLog.interceptor(log),
  )

  @Provides
  @Singleton
  fun apiFactory(http: ComputerHttp): ApiFactory = ApiFactory(http)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkBindings {
  @Binds
  fun computerClient(impl: HttpComputerClient): ComputerClient

  @Binds
  fun allowedPeers(impl: ComputerPeers): AllowedPeers

  @Binds
  fun verifiedPeers(impl: ComputerVerifiedPeers): VerifiedPeers

  @Binds
  fun clockOffset(impl: LearnedClockOffset): ClockOffset

  @Binds
  fun artifactTransfer(impl: HttpArtifactTransfer): ArtifactTransfer

  @Binds
  fun jobChannel(impl: WebSocketJobChannel): JobChannel
}
