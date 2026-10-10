package com.mapineda48.phonotrainer.core.network.api

/**
 * Binds the computer client to the current Wi-Fi or Ethernet network. Fed by :core:connectivity with
 * `Network.getNetworkHandle()`; :core:network rebuilds the `Network` with `Network.fromNetworkHandle` (API 28+), so
 * this API stays JVM and no core module depends on :core:network (work-plan 4.3).
 */
interface ComputerNetworkBinder {
  fun bind(networkHandle: Long?)
}
