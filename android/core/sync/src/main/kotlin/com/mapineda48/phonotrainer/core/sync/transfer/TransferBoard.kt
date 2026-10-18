package com.mapineda48.phonotrainer.core.sync.transfer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The file being downloaded now: [doneBytes] of [totalBytes] of artifact [name] of clip [jobId]. */
internal data class LiveTransfer(val jobId: String, val name: String, val doneBytes: Long, val totalBytes: Long)

/**
 * Live download progress in memory (architecture 7.6): the row's `part_bytes` is written at most every 512 KiB, while
 * the sync state, the per-file percent of the Clip page and the "Transfers" notification follow every chunk here.
 */
@Singleton
internal class TransferBoard @Inject constructor() {
  private val live = MutableStateFlow<LiveTransfer?>(null)
  private val running = MutableStateFlow(false)

  val current: StateFlow<LiveTransfer?> = live.asStateFlow()

  /** Whether a download run is going on (between files too). */
  val active: StateFlow<Boolean> = running.asStateFlow()

  fun runStarted() {
    running.value = true
  }

  fun runEnded() {
    live.value = null
    running.value = false
  }

  fun started(jobId: String, name: String, from: Long, total: Long) {
    live.value = LiveTransfer(jobId, name, from, total)
  }

  fun progress(doneBytes: Long) {
    live.value = live.value?.copy(doneBytes = doneBytes)
  }

  fun finished() {
    live.value = null
  }
}
