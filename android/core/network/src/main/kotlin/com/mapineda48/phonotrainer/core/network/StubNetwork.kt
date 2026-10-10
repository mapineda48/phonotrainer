package com.mapineda48.phonotrainer.core.network

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.network.api.AllowedPeers
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.ClockOffset
import com.mapineda48.phonotrainer.core.network.api.ComputerClient
import com.mapineda48.phonotrainer.core.network.api.Created
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import com.mapineda48.phonotrainer.core.network.api.LinkCreate
import com.mapineda48.phonotrainer.core.network.api.PairRequest
import com.mapineda48.phonotrainer.core.network.api.RerunBody
import com.mapineda48.phonotrainer.core.network.api.UploadChunk
import com.mapineda48.phonotrainer.core.network.api.UploadDeclaration
import com.mapineda48.phonotrainer.core.network.api.UploadState
import com.mapineda48.phonotrainer.core.network.api.VerifiedPeers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

// Working stubs of the contract freeze (work-plan 2.3): no computer is ever reached. WP-05b replaces this file.

/** Every call fails as "offline" without sending a byte. */
@Singleton
internal class StubComputerClient @Inject constructor() : ComputerClient {
  private val offline = ApiResult.Failed(AppError.Computer.Offline)

  override suspend fun hello(at: HostPort, challengeFor: DeviceId?): ApiResult<ComputerIdentity> = offline

  override suspend fun legacyHealth(at: HostPort): ApiResult<Int> = offline

  override suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant> = offline

  override suspend fun health(): ApiResult<ComputerHealth> = offline

  override suspend fun unpairSelf(): ApiResult<Unit> = offline

  override suspend fun library(): ApiResult<RemoteLibrary> = offline

  override suspend fun job(id: JobId): ApiResult<RemoteJob> = offline

  override suspend fun requestLookup(key: RequestId): ApiResult<RemoteJob> = offline

  override suspend fun reference(): ApiResult<String> = offline

  override suspend fun createLink(body: LinkCreate): ApiResult<Created<RemoteJob>> = offline

  override suspend fun openUpload(body: UploadDeclaration): ApiResult<UploadState> = offline

  override suspend fun putUpload(key: RequestId, file: File, onProgress: (Long) -> Unit): ApiResult<Created<RemoteJob>> =
    offline

  override suspend fun uploadStatus(key: RequestId): ApiResult<UploadState> = offline

  override suspend fun patchUpload(key: RequestId, file: File, offset: Long, length: Long): ApiResult<UploadChunk> =
    offline

  override suspend fun deleteUpload(key: RequestId): ApiResult<Unit> = offline

  override suspend fun cancel(id: JobId): ApiResult<RemoteJob> = offline

  override suspend fun deleteJob(id: JobId): ApiResult<Unit> = offline

  override suspend fun rerun(id: JobId, body: RerunBody): ApiResult<Created<RemoteJob>> = offline

  override suspend fun requestPhoneVideo(id: JobId): ApiResult<RemoteArtifactStatus> = offline

  override suspend fun contour(id: JobId, startS: Double, endS: Double): ApiResult<String> = offline

  override suspend fun compare(id: JobId, take: File, contentType: String, startS: Double, endS: Double): ApiResult<String> =
    offline
}

@Singleton
internal class StubAllowedPeers @Inject constructor() : AllowedPeers {
  private val state = MutableStateFlow<HostPort?>(null)

  override val current: StateFlow<HostPort?> = state

  override fun setCurrent(address: HostPort?) {
    state.value = address
  }

  override suspend fun <T> withCandidate(address: HostPort, block: suspend () -> T): T = block()
}

@Singleton
internal class StubVerifiedPeers @Inject constructor() : VerifiedPeers {
  private val verified = mutableSetOf<HostPort>()

  override fun markVerified(address: HostPort, serverId: ServerId) {
    synchronized(verified) { verified += address }
  }

  override fun isVerified(address: HostPort): Boolean = synchronized(verified) { address in verified }

  override fun reset() = synchronized(verified) { verified.clear() }
}

@Singleton
internal class StubClockOffset @Inject constructor() : ClockOffset {
  override val offsetS: StateFlow<Long> = MutableStateFlow(0L)

  override fun update(serverTime: Long) = Unit
}

internal class StubArtifactTransfer @Inject constructor() : ArtifactTransfer {
  override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome =
    DownloadOutcome.Failed(AppError.Computer.Offline)
}

internal class StubJobChannel @Inject constructor() : JobChannel {
  override fun open(): Flow<ChannelEvent> = emptyFlow()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
  @Binds
  fun computerClient(impl: StubComputerClient): ComputerClient

  @Binds
  fun allowedPeers(impl: StubAllowedPeers): AllowedPeers

  @Binds
  fun verifiedPeers(impl: StubVerifiedPeers): VerifiedPeers

  @Binds
  fun clockOffset(impl: StubClockOffset): ClockOffset

  @Binds
  fun artifactTransfer(impl: StubArtifactTransfer): ArtifactTransfer

  @Binds
  fun jobChannel(impl: StubJobChannel): JobChannel
}
