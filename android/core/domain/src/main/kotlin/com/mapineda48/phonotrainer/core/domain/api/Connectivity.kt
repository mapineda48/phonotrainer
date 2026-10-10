package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// :core:connectivity (WP-07), architecture 3.4 and 6.

interface ComputerStatusRepository {
  val status: StateFlow<ComputerStatus>

  /** Name, server id, device name, last seen, last sync. */
  val paired: StateFlow<PairedComputer?>

  val backgroundRestricted: StateFlow<Boolean>

  /** Address, this phone's address, short id, API version, last probe. */
  val diagnostics: StateFlow<ConnectionDiagnostics>

  suspend fun checkNow(): ComputerStatus
}

interface PairingRepository {
  /** At most 10 s: Found(list) / NothingFound / NotOnWifi. */
  fun search(): Flow<SearchState>

  /** Identity only; never sends a credential. */
  suspend fun check(address: HostPort): CheckResult

  suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult

  /** Accepted only for the paired server id. */
  suspend fun useNewAddress(address: HostPort): CheckResult

  suspend fun forget(): ForgetResult
}

/** Job channel → probe state machine. */
interface ChannelLiveness {
  fun channelAlive()

  fun channelLost()
}
