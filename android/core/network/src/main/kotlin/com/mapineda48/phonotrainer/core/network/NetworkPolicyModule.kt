package com.mapineda48.phonotrainer.core.network

import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The address policy and the network binder (architecture 5.1, 5.2). Its name is frozen at B1: the end-to-end set
 * replaces it with `TestNetworkModule` (loopback allowed, a no-op binder; architecture 21.5).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkPolicyModule {
  @Provides
  fun addressPolicy(): AddressPolicy = PrivateAddressPolicy

  @Provides
  fun networkBinder(): ComputerNetworkBinder = UnboundNetworkBinder
}

/** Stub until WP-05b: the client is not bound to a network yet. */
internal object UnboundNetworkBinder : ComputerNetworkBinder {
  override fun bind(networkHandle: Long?) = Unit
}

/** A candidate must be an IPv4 literal in 10/8, 172.16/12, 192.168/16 or 169.254/16 (architecture 5.2). */
internal object PrivateAddressPolicy : AddressPolicy {
  override fun allowsCandidate(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part == it.toString() } ?: return false }
    val (a, b) = octets
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
  }
}
