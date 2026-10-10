package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers

/** In-memory [VerifiedPeers]; [serverIdOf] tells which computer proved an address. */
class FakeVerifiedPeers : VerifiedPeers {
  private val verified = mutableMapOf<HostPort, ServerId>()

  fun serverIdOf(address: HostPort): ServerId? = verified[address]

  override fun markVerified(address: HostPort, serverId: ServerId) {
    verified[address] = serverId
  }

  override fun isVerified(address: HostPort): Boolean = address in verified

  override fun reset() {
    verified.clear()
  }
}
