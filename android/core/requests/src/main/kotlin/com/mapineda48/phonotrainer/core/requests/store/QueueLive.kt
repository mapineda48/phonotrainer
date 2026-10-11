package com.mapineda48.phonotrainer.core.requests.store

import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Bytes moved so far and the total, when known. */
internal data class Moved(val bytes: Long, val total: Long?)

/**
 * What the queue knows only while the process lives (architecture 8.4): the last job facts the computer reported for
 * this phone's requests (stage, percent, phrase n of m, the downloader version of a failure), and the bytes copied or
 * sent right now. Rows hold everything that must survive a restart; these only make a row more precise.
 */
@Singleton
internal class QueueLive @Inject constructor() {
  private val jobState = MutableStateFlow<Map<String, RemoteJob>>(emptyMap())
  private val sentState = MutableStateFlow<Map<String, Moved>>(emptyMap())
  private val copiedState = MutableStateFlow<Map<String, Moved>>(emptyMap())

  /** The latest facts of each own job, by job id. */
  val jobs: StateFlow<Map<String, RemoteJob>> = jobState

  /** Upload progress by request id. */
  val sent: StateFlow<Map<String, Moved>> = sentState

  /** Copy progress by request id. */
  val copied: StateFlow<Map<String, Moved>> = copiedState

  fun job(job: RemoteJob) = jobState.update { it + (job.id.value to job) }

  /** A complete listing replaces what is known. */
  fun listing(jobs: List<RemoteJob>) {
    jobState.value = jobs.associateBy { it.id.value }
  }

  fun jobGone(id: String) = jobState.update { it - id }

  fun sending(id: RequestId, bytes: Long, total: Long?) = sentState.update { it + (id.value to Moved(bytes, total)) }

  fun sendingDone(id: RequestId) = sentState.update { it - id.value }

  fun copying(id: RequestId, bytes: Long, total: Long?) = copiedState.update { it + (id.value to Moved(bytes, total)) }

  fun copyingDone(id: RequestId) = copiedState.update { it - id.value }
}
