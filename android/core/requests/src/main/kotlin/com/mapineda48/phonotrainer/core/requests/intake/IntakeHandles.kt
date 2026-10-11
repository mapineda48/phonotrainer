package com.mapineda48.phonotrainer.core.requests.intake

import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The in-process registry of shared sources (architecture 8.3). A shared item's read grant lasts only while the
 * `ShareActivity` that received it lives, so:
 * - the descriptor opened while the sheet was visible is kept here under the request id, for the expedited
 *   `ShareIntakeWorker`, which keeps reading it after the sheet closed (an open descriptor stays readable);
 * - while the sheet holds its requests ([hold], closed in `onDestroy`), the source can be opened again
 *   ("Try audio only" after a copy refused for space, D-49), and [readable] names those requests, so the action
 *   disappears from a row the moment the sheet closes.
 *
 * A process death empties it: a shared copy that did not finish then becomes `COPY_FAILED` `copy_interrupted` (QT-13).
 */
@Singleton
internal class IntakeHandles @Inject constructor() {
  private val sources = ConcurrentHashMap<String, SourceStream>()
  private val holds = MutableStateFlow<Map<String, Int>>(emptyMap())
  private val readableIds = MutableStateFlow<Set<RequestId>>(emptySet())

  /** Requests whose source can be read again now (their share sheet is open). */
  val readable: StateFlow<Set<RequestId>> = readableIds

  fun isReadable(id: RequestId): Boolean = id in readableIds.value

  /** Keeps [stream] for [id], closing one kept before. */
  fun put(id: RequestId, stream: SourceStream) {
    sources.put(id.value, stream)?.takeIf { it !== stream }?.close()
  }

  /** The kept source of [id], still owned by the registry. */
  fun get(id: RequestId): SourceStream? = sources[id.value]

  /** Closes and forgets the kept source of [id]. */
  fun drop(id: RequestId) {
    sources.remove(id.value)?.close()
  }

  /** The share sheet holds [ids] until the returned handle is closed (idempotent). */
  fun hold(ids: Set<RequestId>): AutoCloseable {
    if (ids.isEmpty()) return AutoCloseable { }
    change(ids, +1)
    val closed = AtomicBoolean(false)
    return AutoCloseable {
      if (closed.compareAndSet(false, true)) change(ids, -1)
    }
  }

  private fun change(ids: Set<RequestId>, delta: Int) {
    holds.update { current ->
      val next = current.toMutableMap()
      ids.forEach { id ->
        val count = (next[id.value] ?: 0) + delta
        if (count > 0) next[id.value] = count else next.remove(id.value)
      }
      next
    }
    readableIds.value = holds.value.keys.mapTo(HashSet()) { RequestId(it) }
  }
}
