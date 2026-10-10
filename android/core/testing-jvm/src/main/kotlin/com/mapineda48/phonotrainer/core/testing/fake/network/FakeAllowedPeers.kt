package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [AllowedPeers]: [candidates] records every address checked through [withCandidate]. */
class FakeAllowedPeers(initial: HostPort? = null) : AllowedPeers {
  private val state = MutableStateFlow(initial)
  private val checked = mutableListOf<HostPort>()

  val candidates: List<HostPort>
    get() = checked.toList()

  override val current: StateFlow<HostPort?> = state

  override fun setCurrent(address: HostPort?) {
    state.value = address
  }

  override suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T {
    checked += address
    return block()
  }
}
