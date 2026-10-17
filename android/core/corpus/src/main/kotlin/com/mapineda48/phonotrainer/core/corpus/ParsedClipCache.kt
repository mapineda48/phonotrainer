package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The parsed clips the feed, the lesson and the Clip page share (architecture 9.5): the [capacity] most recently used
 * [ClipData], keyed by job id and clip-data revision, so a new revision of a clip is parsed again and replaces the old
 * one. Two callers asking for the same clip at once share one parse.
 */
internal class ParsedClipCache(private val capacity: Int = CAPACITY) {
  init {
    require(capacity > 0) { "the cache holds at least one clip" }
  }

  private data class Key(val id: JobId, val revision: String)

  /** Thrown to the callers that waited on a parse whose own caller was cancelled: they parse again themselves. */
  private class LoadAbandoned : Exception()

  private val mutex = Mutex()

  /** Least recently used first (access order). */
  private val entries = LinkedHashMap<Key, ClipData>(capacity + 1, LOAD_FACTOR, true)
  private val loading = HashMap<Key, CompletableDeferred<ClipData>>()

  /** The cached clip of [id] at [revision], or [load]'s result, which is then cached. [load]'s exceptions propagate. */
  suspend fun get(id: JobId, revision: String, load: suspend () -> ClipData): ClipData {
    val key = Key(id, revision)
    while (true) {
      var owner = false
      val pending = mutex.withLock {
        entries[key]?.let { return it }
        loading[key] ?: CompletableDeferred<ClipData>().also {
          loading[key] = it
          owner = true
        }
      }
      if (!owner) {
        try {
          return pending.await()
        } catch (abandoned: LoadAbandoned) {
          continue
        }
      }
      val data = try {
        load()
      } catch (e: Throwable) {
        // Never left half done, even when the caller was cancelled: the waiters would wait forever.
        withContext(NonCancellable) { mutex.withLock { loading.remove(key) } }
        pending.completeExceptionally(if (e is CancellationException) LoadAbandoned() else e)
        throw e
      }
      withContext(NonCancellable) {
        mutex.withLock {
          loading.remove(key)
          put(key, data)
        }
      }
      pending.complete(data)
      return data
    }
  }

  /** Forgets the clips of [ids] (removed, or dropped from the index). */
  suspend fun evict(ids: Set<JobId>) {
    mutex.withLock { entries.keys.removeAll { it.id in ids } }
  }

  /** The job ids cached now, least recently used first (tests). */
  suspend fun cachedIds(): List<JobId> = mutex.withLock { entries.keys.map { it.id } }

  private fun put(key: Key, data: ClipData) {
    entries.keys.removeAll { it.id == key.id && it != key }
    entries[key] = data
    val extra = entries.size - capacity
    if (extra > 0) {
      val eldest = entries.keys.take(extra)
      eldest.forEach(entries::remove)
    }
  }

  companion object {
    /** Architecture 9.5: an LRU cache of three clips. */
    const val CAPACITY: Int = 3

    private const val LOAD_FACTOR = 0.75f
  }
}
