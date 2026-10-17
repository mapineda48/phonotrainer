package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.AnalysisParser
import com.mapineda48.phonotrainer.core.analysis.ChangeAdvice
import com.mapineda48.phonotrainer.core.analysis.ClipDerivation
import com.mapineda48.phonotrainer.core.analysis.DerivedClip
import com.mapineda48.phonotrainer.core.analysis.ShortsV1
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.corpus.TestClips.phrase
import com.mapineda48.phonotrainer.core.corpus.TestClips.plain
import com.mapineda48.phonotrainer.core.corpus.TestClips.word
import com.mapineda48.phonotrainer.core.database.library.dao.IndexState
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The clip indexer (architecture 9.5; work-plan WP-10 Proof): it stores exactly what `:core:analysis` derives from the
 * verified clip data, replaces a clip's rows in one transaction when a new revision arrives, re-indexes the clips an
 * app update left on an older rule version, and answers Missing or Corrupt without writing anything when the clip
 * data cannot be read.
 */
@RunWith(RobolectricTestRunner::class)
class ClipIndexerTest {
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
  fun indexingStoresWhatTheAnalysisCoreDerivesAndRecordsTheRevision() = runBlocking<Unit> {
    val bytes = CorpusHarness.bytes(TestClips.flappingCorpus())
    val id = h.addClip("a", bytes)
    val expected = derive(id, bytes)

    val outcome = h.indexer.index(id)

    assertThat(outcome).isEqualTo(IndexOutcome.Indexed(shorts = 9, occurrences = 17))
    assertThat(outcome).isEqualTo(IndexOutcome.Indexed(expected.shorts.size, expected.occurrences.size))
    assertThat(storedShorts(id)).containsExactlyElementsIn(expected.shorts.map { it.toEntity(id, ShortsV1.RULE_VERSION) })
      .inOrder()
    assertThat(storedOccurrences(id)).containsExactlyElementsIn(expected.occurrences.map { it.toEntity() })
    assertThat(h.db.shortDao().indexState("a")).isEqualTo(IndexState("a", revision(bytes), ShortsV1.RULE_VERSION))
  }

  @Test
  fun theSameClipDataIsNotIndexedTwice() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)

    assertThat(h.indexer.index(id)).isEqualTo(IndexOutcome.Unchanged)
    assertThat(h.loader.parses).isEqualTo(1)
  }

  @Test
  fun aNewRevisionReplacesEveryRowOfTheClipAndNoOtherClip() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    val other = h.addClip("b", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    h.indexer.index(other)
    val smaller = CorpusHarness.bytes(
      TestClips.analysis(
        phrase(0.0, 6.0, plain("the", 0.1), word("city", 0.5, "flapping")),
        phrase(10.0, 16.0, word("last", 10.5, "t_deletion", canonical = "l æ s t", realized = "l æ s")),
      ),
    )
    h.putClipData("a", smaller)

    val outcome = h.indexer.index(id)

    assertThat(outcome).isEqualTo(IndexOutcome.Indexed(shorts = 2, occurrences = 2))
    assertThat(storedShorts(id).map { it.firstSegment }).containsExactly(0, 1).inOrder()
    assertThat(storedOccurrences(id).map { it.wordText to it.change }).containsExactly("city" to "flapping", "last" to "t_deletion")
    assertThat(h.db.shortDao().indexState("a")?.indexedRevision).isEqualTo(revision(smaller))
    assertThat(storedShorts(other)).hasSize(9)
    assertThat(storedOccurrences(other)).hasSize(17)
  }

  @Test
  fun anAppUpdateThatRaisesTheRuleVersionReindexesTheClipsWithClipData() = runBlocking<Unit> {
    val bytes = CorpusHarness.bytes(TestClips.flappingCorpus())
    val older = h.addClip("older", bytes)
    val current = h.addClip("current", bytes)
    h.indexer.index(older)
    h.indexer.index(current)
    // What a build with an older rule version left: other rows, another rule version.
    h.exec("DELETE FROM short WHERE job_id = 'older'")
    h.exec("UPDATE clip SET index_rule_version = ${ShortsV1.RULE_VERSION - 1} WHERE job_id = 'older'")
    val never = h.addClip("never", bytes)
    h.addClip("no-data", analysis = null)

    h.indexer.reindexStale()

    assertThat(storedShorts(older)).hasSize(9)
    assertThat(h.db.shortDao().indexState("older")).isEqualTo(IndexState("older", revision(bytes), ShortsV1.RULE_VERSION))
    assertThat(storedShorts(never)).hasSize(9)
    assertThat(h.db.shortDao().indexState("no-data")).isEqualTo(IndexState("no-data", null, null))
    assertThat(h.db.shortDao().staleIndexes(ShortsV1.RULE_VERSION)).isEmpty()
    assertThat(storedShorts(current)).hasSize(9)
  }

  @Test
  fun clipDataThatDoesNotParseIsCorruptAndWritesNothing() = runBlocking<Unit> {
    val id = h.addClip("bad", "{\"segments\": [{\"start\": \"soon\"}]}".toByteArray())

    assertThat(h.indexer.index(id)).isEqualTo(IndexOutcome.Corrupt)
    assertThat(h.count("SELECT COUNT(*) FROM short")).isEqualTo(0)
    assertThat(h.count("SELECT COUNT(*) FROM occurrence")).isEqualTo(0)
    assertThat(h.db.shortDao().indexState("bad")).isEqualTo(IndexState("bad", null, null))
  }

  @Test
  fun aCorruptNewRevisionLeavesThePreviousIndexWhole() = runBlocking<Unit> {
    val bytes = CorpusHarness.bytes(TestClips.flappingCorpus())
    val id = h.addClip("a", bytes)
    h.indexer.index(id)
    h.putClipData("a", AnalysisFixtures.gzip(bytes)) // gzip bytes where plain JSON is expected

    assertThat(h.indexer.index(id)).isEqualTo(IndexOutcome.Corrupt)
    assertThat(storedShorts(id)).hasSize(9)
    assertThat(h.db.shortDao().indexState("a")?.indexedRevision).isEqualTo(revision(bytes))
  }

  @Test
  fun aClipWithoutClipDataOnThePhoneIsMissing() = runBlocking<Unit> {
    val noData = h.addClip("no-data", analysis = null)
    val gone = h.addClip("gone", CorpusHarness.bytes(TestClips.flappingCorpus()))
    // The row says verified, but the file is not there any more.
    File(h.files.filesDir, "clips/gone").deleteRecursively()

    assertThat(h.indexer.index(noData)).isEqualTo(IndexOutcome.Missing)
    assertThat(h.indexer.index(gone)).isEqualTo(IndexOutcome.Missing)
    assertThat(h.indexer.index(JobId("unknown"))).isEqualTo(IndexOutcome.Missing)
    assertThat(h.count("SELECT COUNT(*) FROM short")).isEqualTo(0)
  }

  @Test
  fun theCompactRenditionIsPreferredAndReadAsGzip() = runBlocking<Unit> {
    val bytes = CorpusHarness.bytes(TestClips.flappingCorpus())
    val compact = AnalysisFixtures.gzip(bytes)
    val id = h.addClip("a", bytes)
    h.putClipData("a", compact, ClipDataLoader.COMPACT)

    assertThat(h.indexer.index(id)).isEqualTo(IndexOutcome.Indexed(shorts = 9, occurrences = 17))
    assertThat(h.db.shortDao().indexState("a")?.indexedRevision).isEqualTo(revision(compact))
  }

  @Test
  fun droppingRemovesTheRowsTheIndexStateAndTheParsedCopy() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(TestClips.flappingCorpus()))
    val kept = h.addClip("b", CorpusHarness.bytes(TestClips.flappingCorpus()))
    h.indexer.index(id)
    h.indexer.index(kept)

    h.indexer.drop(setOf(id))

    assertThat(storedShorts(id)).isEmpty()
    assertThat(storedOccurrences(id)).isEmpty()
    assertThat(h.db.shortDao().indexState("a")).isEqualTo(IndexState("a", null, null))
    assertThat(h.loader.cachedIds()).containsExactly(kept)
    assertThat(storedShorts(kept)).hasSize(9)
  }

  private fun derive(id: JobId, bytes: ByteArray): DerivedClip {
    val clip = AnalysisParser.parse(id, revision(bytes), bytes.inputStream(), gzip = false)
    return ClipDerivation.derive(clip, ChangeAdvice(h.reference.reference.value))
  }

  private suspend fun storedShorts(id: JobId) = h.db.shortDao().shortsOf(id.value).first().map { it.short }

  private suspend fun storedOccurrences(id: JobId): List<OccurrenceEntity> =
    storedShorts(id).flatMap { h.db.occurrenceDao().rowsOfShort(id.value, it.firstSegment) }

  private fun revision(bytes: ByteArray): String = Sha256.hex(bytes).take(16)
}
