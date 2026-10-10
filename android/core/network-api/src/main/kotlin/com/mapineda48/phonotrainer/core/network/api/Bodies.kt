package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import java.time.Instant

// Request and response bodies of work-plan Appendix A.6, mirroring server-contract 6 field by field.

data class PairRequest(
  val code: String,
  val deviceName: String,
  val appVersion: Int,
  val appVersionName: String,
  val previousDeviceId: DeviceId?,
  val previousTokenProof: String?,
)

data class LinkCreate(val clientRequestId: RequestId, val url: String, val audioOnly: Boolean, val separateDialogue: Boolean?)

data class UploadDeclaration(
  val clientRequestId: RequestId,
  val fileName: String,
  val bytes: Long,
  val sha256: String,
  val separateDialogue: Boolean?,
)

sealed interface UploadState {
  data class Open(
    val clientRequestId: RequestId,
    val offset: Long,
    val bytes: Long,
    val expiresAt: Instant,
    val maxChunkBytes: Long,
    val resumable: Boolean,
  ) : UploadState

  data class Done(val job: RemoteJob) : UploadState
}

/** v1-complete: PATCH. */
sealed interface UploadChunk {
  /** 204 Upload-Offset. */
  data class Accepted(val offset: Long) : UploadChunk

  data class Finished(val job: Created<RemoteJob>) : UploadChunk
}

data class RerunBody(val clientRequestId: RequestId, val audioOnly: Boolean?)
