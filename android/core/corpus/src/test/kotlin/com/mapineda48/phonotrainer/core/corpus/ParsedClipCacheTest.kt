package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The parsed-clip cache (architecture 9.5; work-plan WP-10 Proof "LRU of three"): the three most recently used clips
 * stay parsed, a new revision replaces the old one, callers asking at once share one parse, and a failed or abandoned
 * parse is never cached.
 */
class ParsedClipCacheTest {
  private val cache = ParsedClipCache()
  private val loads = AtomicInteger()

  @Test
  fun itKeepsTheThreeMostRecentlyUsedClips() = runTest {
    get("a")
    get("b")
    get("c")
    get("a") // a is used again: b is now the least recently used
    get("d")

    assertThat(cache.cachedIds()).containsExactly(JobId("c"), JobId("a"), JobId("d")).inOrder()
    assertThat(loads.get()).isEqualTo(4)

    get("a")
    get("c")
    get("d")
    assertThat(loads.get()).isEqualTo(4)

    get("b")
    assertThat(loads.get()).isEqualTo(5)
    assertThat(cache.cachedIds()).hasSize(ParsedClipCache.CAPACITY)
  }

  @Test
  fun aNewRevisionIsParsedAgainAndReplacesTheOldOne() = runTest {
    val first = get("a", revision = "r1")
    val second = get("a", revision = "r2")

    assertThat(second).isNotSameInstanceAs(first)
    assertThat(second.revision).isEqualTo("r2")
    assertThat(cache.cachedIds()).containsExactly(JobId("a"))
    assertThat(get("a", revision = "r2")).isSameInstanceAs(second)
    assertThat(loads.get()).isEqualTo(2)
  }

  @Test
  fun callersAskingAtOnceShareOneParse() = runTest {
    val gate = CompletableDeferred<Unit>()
    val load: suspend () -> ClipData = {
      loads.incrementAndGet()
      gate.await()
      AnalysisFixtures.clipData(JobId("a"), "r1")
    }
    val feed = async(start = CoroutineStart.UNDISPATCHED) { cache.get(JobId("a"), "r1", load) }
    val lesson = async(start = CoroutineStart.UNDISPATCHED) { cache.get(JobId("a"), "r1", load) }

    gate.complete(Unit)

    assertThat(lesson.await()).isSameInstanceAs(feed.await())
    assertThat(loads.get()).isEqualTo(1)
  }

  @Test
  fun aFailedParseIsNotCached() = runTest {
    val failure = runCatching {
      cache.get(JobId("a"), "r1") { throw ClipDataException(AppError.Content.Corrupt) }
    }.exceptionOrNull()

    assertThat(failure).isInstanceOf(ClipDataException::class.java)

    assertThat(cache.cachedIds()).isEmpty()
    assertThat(get("a", revision = "r1").revision).isEqualTo("r1")
  }

  @Test
  fun aWaiterParsesItselfWhenTheCallerThatParsedIsCancelled() = runTest {
    val owner = async(start = CoroutineStart.UNDISPATCHED) {
      cache.get(JobId("a"), "r1") { awaitCancellation() }
    }
    val waiter = async(start = CoroutineStart.UNDISPATCHED) { get("a", revision = "r1") }

    owner.cancel()

    assertThat(waiter.await().revision).isEqualTo("r1")
    assertThat(loads.get()).isEqualTo(1)
  }

  @Test
  fun evictedClipsAreParsedAgain() = runTest {
    get("a")
    get("b")

    cache.evict(setOf(JobId("a")))

    assertThat(cache.cachedIds()).containsExactly(JobId("b"))
    get("a")
    assertThat(loads.get()).isEqualTo(3)
  }

  private suspend fun get(id: String, revision: String = "r1"): ClipData = cache.get(JobId(id), revision) {
    loads.incrementAndGet()
    AnalysisFixtures.clipData(JobId(id), revision)
  }
}
