package com.mapineda48.phonotrainer.core.connectivity

import com.mapineda48.phonotrainer.core.domain.api.ChannelLiveness
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CheckResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ConnectionDiagnostics
import com.mapineda48.phonotrainer.core.domain.model.ForgetResult
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.SearchState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// Working stubs of the contract freeze (work-plan 2.3): never paired, nothing found. WP-07 replaces this file.

@Singleton
class StubComputerStatusRepository @Inject constructor() : ComputerStatusRepository {
  override val status: StateFlow<ComputerStatus> = MutableStateFlow(ComputerStatus.NotConnected)
  override val paired: StateFlow<PairedComputer?> = MutableStateFlow(null)
  override val backgroundRestricted: StateFlow<Boolean> = MutableStateFlow(false)
  override val diagnostics: StateFlow<ConnectionDiagnostics> = MutableStateFlow(
    ConnectionDiagnostics(
      address = null,
      shortServerId = null,
      apiVersion = null,
      lastProbe = null,
      lastProbeAt = null,
      addressChangedAt = null,
      waitingRequests = 0,
      requestsOnComputer = 0,
      wantedDownloads = 0,
      backgroundRestricted = false,
      decoders = emptyList(),
    ),
  )

  override suspend fun checkNow(): ComputerStatus = status.value
}

class StubPairingRepository @Inject constructor() : PairingRepository {
  override fun search(): Flow<SearchState> = flowOf(SearchState.Searching, SearchState.NothingFound)

  override suspend fun check(address: HostPort): CheckResult = CheckResult.NoAnswer

  override suspend fun pair(target: PairingTarget, code: String, deviceName: String): PairResult =
    PairResult.Failed(AppError.Computer.Offline)

  override suspend fun useNewAddress(address: HostPort): CheckResult = CheckResult.NoAnswer

  override suspend fun forget(): ForgetResult = ForgetResult.Forgotten
}

class StubChannelLiveness @Inject constructor() : ChannelLiveness {
  override fun channelAlive() = Unit

  override fun channelLost() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
interface ConnectivityModule {
  @Binds
  fun status(impl: StubComputerStatusRepository): ComputerStatusRepository

  @Binds
  fun pairing(impl: StubPairingRepository): PairingRepository

  @Binds
  fun liveness(impl: StubChannelLiveness): ChannelLiveness
}
