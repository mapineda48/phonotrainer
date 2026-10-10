package com.mapineda48.phonotrainer.core.network.api

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import java.io.File
import kotlinx.coroutines.flow.Flow

/** Resumable artifact downloads (architecture 7.4). */
interface ArtifactTransfer {
  suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome
}

/** A part already [expectedBytes] long is verified, never requested (A-M5). */
data class DownloadRequest(
  val url: String,
  val target: File,
  val resumeFrom: Long,
  val ifRange: String?,
  val expectedBytes: Long,
)

sealed interface DownloadOutcome {
  data class Complete(val etag: String?, val bytes: Long) : DownloadOutcome

  /** 200 where 206 was expected. */
  data object RestartedFromZero : DownloadOutcome

  /** 416: verify the part, restart only if it does not match. */
  data object RangeNotSatisfiable : DownloadOutcome

  data class Failed(val error: AppError.Computer) : DownloadOutcome
}

/** Completes or fails when the channel is lost (architecture 5.5). */
fun interface JobChannel {
  fun open(): Flow<ChannelEvent>
}
