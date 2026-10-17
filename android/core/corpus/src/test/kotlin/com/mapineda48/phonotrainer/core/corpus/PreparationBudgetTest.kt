package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.IndexOutcome
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NFR-02 on the JVM: preparing the shorts of the largest real analysis (614 s, 1,489 words, 4.8 MB) takes at most
 * 1.5 s, and the parse and the derivations run on the parsing dispatcher (`Dispatchers.Default` in the app), never on
 * the caller's thread. The phone's own measurement belongs to WP-19 and WP-25.
 */
@RunWith(RobolectricTestRunner::class)
class PreparationBudgetTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val dispatched = AtomicInteger()

  /** `Dispatchers.Default`, counting what is sent to it. */
  private val parsing = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
      dispatched.incrementAndGet()
      Dispatchers.Default.dispatch(context, block)
    }
  }

  private lateinit var h: CorpusHarness

  @Before
  fun setUp() {
    h = CorpusHarness(tmp.newFolder("files"), dispatcher = parsing)
  }

  @After
  fun tearDown() = h.close()

  @Test
  fun theLargestAnalysisIsPreparedWithinTheBudgetOffTheCallersThread() = runBlocking<Unit> {
    val bytes = AnalysisFixtures.syntheticBytes()
    assertThat(bytes.size).isAtLeast(4_000_000)
    // A first clip warms the JIT up, as the app process has before its second clip.
    h.indexer.index(h.addClip("warm-up", bytes))
    val id = h.addClip("largest", bytes)
    dispatched.set(0)

    var outcome: IndexOutcome? = null
    val ms = measureTimeMillis { outcome = h.indexer.index(id) }

    assertThat(outcome).isInstanceOf(IndexOutcome.Indexed::class.java)
    assertThat((outcome as IndexOutcome.Indexed).shorts).isAtLeast(20)
    assertThat(ms).isAtMost(BUDGET_MS)
    // The parse and the derivation each went to the parsing dispatcher.
    assertThat(dispatched.get()).isAtLeast(2)
  }

  private companion object {
    const val BUDGET_MS = 1_500L
  }
}
