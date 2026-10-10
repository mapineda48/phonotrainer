package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ServerId

/** Addresses whose `hello` returned a valid identity proof for this phone's key (architecture 5.3). */
interface VerifiedPeers {
  fun markVerified(address: HostPort, serverId: ServerId)

  fun isVerified(address: HostPort): Boolean

  fun reset()
}
