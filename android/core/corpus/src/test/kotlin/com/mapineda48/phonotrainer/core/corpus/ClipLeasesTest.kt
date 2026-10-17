package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Leases (architecture 7.7, spec QT-10; work-plan WP-10 Proof "a lease defers removal"): a screen's lease on a clip
 * keeps its shorts, occurrences and parsed copy while the clip is removed, until the last lease on it closes; the sync
 * engine hears about each release.
 */
@RunWith(RobolectricTestRunner::class)
class ClipLeasesTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var h: CorpusHarness

  @Before
  fun setUp() {
    h = CorpusHarness(tmp.newFolder("files"))
  }

  @After
  fun tearDown() = h.close()

  @Test
  fun leasesAreCountedPerClipAndAClosedLeaseCountsOnce() {
    val registry = ClipLeaseRegistry()
    val a = JobId("a")
    val first = registry.open(a)
    val second = registry.open(a)

    first.close()
    first.close()
    assertThat(registry.isOpen(a)).isTrue()

    second.close()
    assertThat(registry.isOpen(a)).isFalse()
    assertThat(registry.isOpen(JobId("never"))).isFalse()
  }

  @Test
  fun theSyncEngineHearsWhenTheLastLeaseOfAClipCloses() = runBlocking<Unit> {
    val registry = ClipLeaseRegistry()
    val heard = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { registry.released.take(2).toList() }
    val a1 = registry.open(JobId("a"))
    val a2 = registry.open(JobId("a"))
    val b = registry.open(JobId("b"))

    a1.close() // a is still shown by another screen
    b.close()
    a2.close()

    assertThat(withTimeout(TIMEOUT_MS) { heard.await() }).containsExactly(JobId("b"), JobId("a")).inOrder()
  }

  @Test
  fun aLeaseDefersTheRemovalOfAClipUntilItCloses() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    val lease = h.content.open(id)

    h.removalHook.onClipsRemoved(setOf(id))

    // The open feed keeps everything it shows.
    assertThat(h.indexer.isDropDeferred(id)).isTrue()
    assertThat(shortCount(id)).isEqualTo(9)
    assertThat(h.content.clipData(id).phrases).hasSize(9)
    assertThat(h.shorts.shorts(id).first()).hasSize(9)

    lease.close()

    h.eventually { shortCount(id) == 0 }
    assertThat(h.count("SELECT COUNT(*) FROM occurrence WHERE job_id = 'a'")).isEqualTo(0)
    assertThat(h.db.shortDao().indexState("a")?.indexedRevision).isNull()
    assertThat(h.loader.cachedIds()).doesNotContain(id)
    assertThat(h.indexer.isDropDeferred(id)).isFalse()
  }

  @Test
  fun everyLeaseOnTheClipMustCloseFirst() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    val feed = h.content.open(id)
    val lesson = h.content.open(id)
    h.indexer.drop(setOf(id))

    feed.close()
    yield()
    assertThat(shortCount(id)).isEqualTo(9)

    lesson.close()
    h.eventually { shortCount(id) == 0 }
  }

  @Test
  fun aClipNobodyShowsIsDroppedAtOnce() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    val other = h.addClip("b", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    h.indexer.index(other)
    h.content.open(other) // another clip's lease changes nothing

    h.removalHook.onClipsRemoved(setOf(id))

    assertThat(shortCount(id)).isEqualTo(0)
    assertThat(h.indexer.isDropDeferred(id)).isFalse()
    assertThat(shortCount(other)).isEqualTo(9)
  }

  @Test
  fun indexingTheClipAgainCancelsADeferredDrop() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    val lease = h.content.open(id)
    h.indexer.drop(setOf(id))

    // The clip came back (downloaded again) while the screen was open.
    h.indexer.index(id)
    lease.close()

    yield()
    h.indexer.index(JobId("unrelated")) // passes through the indexer's lock after any drop the release launched
    assertThat(h.indexer.isDropDeferred(id)).isFalse()
    assertThat(shortCount(id)).isEqualTo(9)
  }

  private fun shortCount(id: JobId): Int = h.count("SELECT COUNT(*) FROM short WHERE job_id = '${id.value}'")

  private companion object {
    const val TIMEOUT_MS = 5_000L
  }
}
