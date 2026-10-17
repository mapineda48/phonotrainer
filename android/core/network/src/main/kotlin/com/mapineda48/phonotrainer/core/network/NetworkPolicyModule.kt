package com.mapineda48.phonotrainer.core.network

import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import com.mapineda48.phonotrainer.core.network.binding.AndroidNetworkPlatform
import com.mapineda48.phonotrainer.core.network.binding.NetworkBinding
import com.mapineda48.phonotrainer.core.network.guard.Ipv4
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The address policy and the network binder (architecture 5.1, 5.2). Its name is frozen at B1: the end-to-end set
 * replaces it with `TestNetworkModule` (loopback allowed, a binder that binds nothing; architecture 21.5), which calls
 * [addressPolicy] to keep the release rules (decisions I-08), so [addressPolicy] stays public and parameterless.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkPolicyModule {
  @Provides
  fun addressPolicy(): AddressPolicy = PrivateAddressPolicy

  /** `:core:connectivity` feeds it the current Wi-Fi or Ethernet network; every socket of the client is bound to it. */
  @Provides
  @Singleton
  fun networkBinder(): ComputerNetworkBinder = NetworkBinding(AndroidNetworkPlatform)
}

/** A candidate must be an IPv4 literal in 10/8, 172.16/12, 192.168/16 or 169.254/16 (architecture 5.2). */
internal object PrivateAddressPolicy : AddressPolicy {
  override fun allowsCandidate(host: String): Boolean {
    if (!Ipv4.isLiteral(host)) return false
    val (a, b) = host.split('.').map(String::toInt)
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
  }
}
