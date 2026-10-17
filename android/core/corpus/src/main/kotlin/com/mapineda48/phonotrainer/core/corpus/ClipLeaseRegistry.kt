package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.domain.model.ClipLease
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.spi.ClipLeases
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Which clips a screen shows right now (architecture 7.7, QT-10): `ClipContentRepository.open` hands out a [ClipLease]
 * per screen, and the removal of a clip is deferred while one is open. Leases are counted per clip, so two screens on
 * one clip keep it until both close; closing a lease twice counts once. When the last lease of a clip closes, the clip
 * is announced on [released] (to the sync engine, which then applies a deferred removal) and to the in-module
 * listeners (the indexer's deferred drops).
 */
@Singleton
internal class ClipLeaseRegistry @Inject constructor() : ClipLeases {
  private val open = HashMap<JobId, Int>()
  private val listeners = CopyOnWriteArrayList<(JobId) -> Unit>()
  private val releases = MutableSharedFlow<JobId>(
    extraBufferCapacity = RELEASE_BUFFER,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /** A new lease on [id]; [ClipLease.close] releases it. */
  fun open(id: JobId): ClipLease {
    synchronized(open) { open[id] = (open[id] ?: 0) + 1 }
    return Lease(id)
  }

  override fun isOpen(id: JobId): Boolean = synchronized(open) { id in open }

  /**
   * The clips whose last lease closed. Hot and without replay: a collector sees the releases after it subscribed, so
   * the sync engine also re-checks deferred removals at each pass.
   */
  override val released: Flow<JobId> = releases.asSharedFlow()

  /** Calls [listener] (on the thread that closed the lease) each time a clip's last lease closes. */
  fun onReleased(listener: (JobId) -> Unit) {
    listeners += listener
  }

  private fun release(id: JobId) {
    val last = synchronized(open) {
      val count = open[id] ?: return
      if (count > 1) {
        open[id] = count - 1
        false
      } else {
        open.remove(id)
        true
      }
    }
    if (!last) return
    releases.tryEmit(id)
    listeners.forEach { it(id) }
  }

  private inner class Lease(override val id: JobId) : ClipLease {
    private val closed = AtomicBoolean(false)

    override fun close() {
      if (closed.compareAndSet(false, true)) release(id)
    }
  }

  private companion object {
    /** Releases come one per closed screen; more than this many unread at once only drops the oldest. */
    const val RELEASE_BUFFER = 64
  }
}
