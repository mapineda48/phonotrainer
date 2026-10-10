package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import kotlinx.coroutines.flow.StateFlow

/**
 * The one address the cleartext guard lets requests reach: the paired computer's current address, or, inside
 * [withCandidate], the address being checked (architecture 5.1, 5.2).
 */
interface AllowedPeers {
  val current: StateFlow<HostPort?>

  fun setCurrent(address: HostPort?)

  suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T
}
