package com.mapineda48.phonotrainer.core.testing.fake.network

import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.remote.ChannelEvent
import com.mapineda48.phonotrainer.core.network.api.ArtifactTransfer
import com.mapineda48.phonotrainer.core.network.api.DownloadOutcome
import com.mapineda48.phonotrainer.core.network.api.DownloadRequest
import com.mapineda48.phonotrainer.core.network.api.JobChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Settable [ArtifactTransfer]: [outcome] decides each download; [requests] records them. */
class FakeArtifactTransfer(
  var outcome: (DownloadRequest) -> DownloadOutcome = { DownloadOutcome.Failed(AppError.Computer.Offline) },
) : ArtifactTransfer {
  val requests = mutableListOf<DownloadRequest>()

  override suspend fun download(request: DownloadRequest, onProgress: (Long) -> Unit): DownloadOutcome {
    requests += request
    return outcome(request)
  }
}

/** A job channel a test feeds with [emit]; every [open] shares the same events. */
class FakeJobChannel : JobChannel {
  private val events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 64)
  var opened = 0
    private set

  suspend fun emit(event: ChannelEvent) = events.emit(event)

  override fun open(): Flow<ChannelEvent> {
    opened++
    return events
  }
}
