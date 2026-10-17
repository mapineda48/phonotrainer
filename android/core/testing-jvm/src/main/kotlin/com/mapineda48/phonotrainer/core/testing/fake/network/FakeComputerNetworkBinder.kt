package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.network.api.ComputerNetworkBinder

/**
 * Settable [ComputerNetworkBinder] (work-plan 2.3): records every network handle `:core:connectivity` binds, so a test
 * can assert that the client follows the current Wi-Fi or Ethernet network and is unbound (null) without one.
 */
class FakeComputerNetworkBinder : ComputerNetworkBinder {
  private val bound = mutableListOf<Long?>()

  /** Every handle bound, in order; null for "no Wi-Fi or Ethernet network". */
  val handles: List<Long?> get() = synchronized(bound) { bound.toList() }

  /** The handle bound last, or null when none was or the last binding was "no network". */
  val current: Long? get() = synchronized(bound) { bound.lastOrNull() }

  override fun bind(networkHandle: Long?) {
    synchronized(bound) { bound += networkHandle }
  }
}
