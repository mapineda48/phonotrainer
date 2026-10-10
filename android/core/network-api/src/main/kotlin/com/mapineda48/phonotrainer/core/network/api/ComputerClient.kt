package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerHealth
import com.mapineda48.phonotrainer.core.domain.model.remote.ComputerIdentity
import com.mapineda48.phonotrainer.core.domain.model.remote.PairGrant
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteArtifactStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import java.io.File

/** One method per row of server-contract 6.10 (work-plan 4.3); implemented by :core:network (WP-05b). */
interface ComputerClient {
  /**
   * Never sends a header; with [challengeFor] it sends a fresh challenge and checks the proof (architecture 5.3).
   */
  suspend fun hello(at: HostPort, challengeFor: DeviceId? = null): ApiResult<ComputerIdentity>

  /** GET /api/health of a pre-6 server: its api_version (D-15). */
  suspend fun legacyHealth(at: HostPort): ApiResult<Int>

  suspend fun pair(at: HostPort, request: PairRequest): ApiResult<PairGrant>

  /** The current verified peer, signed. */
  suspend fun health(): ApiResult<ComputerHealth>

  suspend fun unpairSelf(): ApiResult<Unit>

  suspend fun library(): ApiResult<RemoteLibrary>

  suspend fun job(id: JobId): ApiResult<RemoteJob>

  suspend fun requestLookup(key: RequestId): ApiResult<RemoteJob>

  /** Raw JSON for `ReferenceRepository.replace`. */
  suspend fun reference(): ApiResult<String>

  suspend fun createLink(body: LinkCreate): ApiResult<Created<RemoteJob>>

  suspend fun openUpload(body: UploadDeclaration): ApiResult<UploadState>

  suspend fun putUpload(key: RequestId, file: File, onProgress: (Long) -> Unit): ApiResult<Created<RemoteJob>>

  /** v1-complete. */
  suspend fun uploadStatus(key: RequestId): ApiResult<UploadState>

  /** v1-complete. */
  suspend fun patchUpload(key: RequestId, file: File, offset: Long, length: Long): ApiResult<UploadChunk>

  suspend fun deleteUpload(key: RequestId): ApiResult<Unit>

  suspend fun cancel(id: JobId): ApiResult<RemoteJob>

  suspend fun deleteJob(id: JobId): ApiResult<Unit>

  suspend fun rerun(id: JobId, body: RerunBody): ApiResult<Created<RemoteJob>>

  /** v1-complete. */
  suspend fun requestPhoneVideo(id: JobId): ApiResult<RemoteArtifactStatus>

  /** v1-complete. */
  suspend fun contour(id: JobId, startS: Double, endS: Double): ApiResult<String>

  /** v1-complete. */
  suspend fun compare(id: JobId, take: File, contentType: String, startS: Double, endS: Double): ApiResult<String>
}
