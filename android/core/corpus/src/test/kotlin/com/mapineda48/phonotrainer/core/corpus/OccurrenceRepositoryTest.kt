package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.analysis.AnalysisParser
import com.mapineda48.phonotrainer.core.analysis.ChangeAdvice
import com.mapineda48.phonotrainer.core.analysis.ClipDerivation
import com.mapineda48.phonotrainer.core.analysis.ExamplePicker
import com.mapineda48.phonotrainer.core.analysis.OccurrenceRecord
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.corpus.TestClips.phrase
import com.mapineda48.phonotrainer.core.corpus.TestClips.plain
import com.mapineda48.phonotrainer.core.corpus.TestClips.word
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.CorpusTotals
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The corpus views computed on the phone (spec D-16, D-48; architecture 9.4; work-plan WP-10 Proof): the two count
 * units over playable clips only, examples, symbol examples, quiz pools, word variants and totals, each checked against
 * `:core:analysis`'s own rules on the real derivations.
 */
@RunWith(RobolectricTestRunner::class)
class OccurrenceRepositoryTest {
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
  fun twelveEligibleFlapsInNineShortsCountNineShortsAndTwelveOccurrences() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())

    val counts = h.occurrences.changeCounts().first()

    // F-15-AC1: Learn's card reads "9 shorts in your clips", the page "12 times in your clips".
    assertThat(counts[FLAPPING]).isEqualTo(ChangeCount(shorts = 9, occurrences = 12))
    assertThat(counts[ChangeLabel("linking")]).isEqualTo(ChangeCount(shorts = 2, occurrences = 2))
    assertThat(counts[ChangeLabel("t_deletion")]).isEqualTo(ChangeCount(shorts = 1, occurrences = 1))
    assertThat(counts).hasSize(3)
  }

  @Test
  fun everyChangeCountsAsManyShortsAsItsChangeModePlays() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    index("b", AnalysisFixtures.synthetic(words = 600, phrases = 90, seed = 11))
    index("c", AnalysisFixtures.synthetic(words = 400, phrases = 60, seed = 23))
    val counts = h.occurrences.changeCounts().first()
    val labels = counts.keys + h.reference.reference.value.labels.keys

    assertThat(counts.size).isAtLeast(10)
    for (label in labels) {
      // F-03-AC7: "Flapping · 34" opens 34 shorts, and an absent key (0) opens none.
      val played = h.shorts.sequence(FeedMode.Change(label)).items
      assertWithMessage(label.value).that(played.size).isEqualTo(counts[label]?.shorts ?: 0)
      assertWithMessage(label.value).that(played.filter { it.spotlight?.change != label }).isEmpty()
    }
  }

  @Test
  fun aClipWhoseStudyPackIsMissingCountsInNeitherUnitUntilItArrives() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    index("b", TestClips.flappingCorpus(), audio = mapOf(CorpusHarness.AUDIO_MIX to "DOWNLOADING"))

    assertThat(h.occurrences.changeCounts().first()[FLAPPING]).isEqualTo(ChangeCount(9, 12))
    assertThat(h.shorts.sequence(FeedMode.Change(FLAPPING)).items.map { it.id.jobId }.toSet()).containsExactly(JobId("a"))
    assertThat(h.occurrences.examples(FLAPPING, limit = 100).map { it.word.jobId }.toSet()).containsExactly(JobId("a"))
    assertThat(h.occurrences.totals().first().clips).isEqualTo(1)

    h.putArtifact("b", CorpusHarness.AUDIO_MIX, MediaResolver.AUDIO_MIX, CorpusHarness.READY, CorpusHarness.WAV, "wav")
    h.changed()

    val counted = withTimeout(TIMEOUT_MS) {
      h.occurrences.changeCounts().first { it[FLAPPING] == ChangeCount(18, 24) }
    }
    assertThat(counted[ChangeLabel("linking")]).isEqualTo(ChangeCount(4, 4))
  }

  @Test
  fun aClipWaitingForItsDeletionOnTheComputerCountsNoMore() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    index("b", TestClips.flappingCorpus())

    h.exec("UPDATE clip SET deleted_on_computer = 1 WHERE job_id = 'b'")

    assertThat(h.occurrences.changeCounts().first()[FLAPPING]).isEqualTo(ChangeCount(9, 12))
  }

  @Test
  fun examplesAreOnePerWordClearestFirstAndAtMostEight() = runBlocking<Unit> {
    val a = index("a", TestClips.flappingCorpus())
    val b = index(
      "b",
      TestClips.analysis(
        phrase(0.0, 6.0, word("water", 0.5, "flapping", diffCost = 0.02), word("City", 2.0, "flapping", diffCost = 0.03)),
      ),
    )

    val examples = h.occurrences.examples(FLAPPING)

    assertThat(examples.map { it.wordText }).containsExactly(
      "water", "City", "waters", "betters", "better", "citys", "little", "matter",
    ).inOrder()
    assertThat(examples.take(2).map { it.word.jobId }).containsExactly(JobId("b"), JobId("b"))
    assertThat(examples.map { it.diffCost }).isInOrder()
    assertThat(examples.map { it.clipTitle }.toSet()).containsExactly("Clip a", "Clip b")
    // The same rule as :core:analysis's ExamplePicker on the derived rows.
    val picked = ExamplePicker.pick((a + b).filter { it.change == FLAPPING }, ExamplePicker.LIMIT)
    assertThat(examples.map { it.word to it.change }).containsExactlyElementsIn(picked.map { it.word to it.change }).inOrder()
  }

  @Test
  fun examplesCanStayInOneClipAndNeverIncludeUnreliableWords() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    index("b", TestClips.analysis(phrase(0.0, 6.0, word("water", 0.5, "flapping", diffCost = 0.02))))

    assertThat(h.occurrences.examples(FLAPPING, onlyClip = JobId("b")).map { it.word.jobId }).containsExactly(JobId("b"))
    val all = h.occurrences.examples(FLAPPING, limit = 100)
    // "butter" low confidence and "got" too short are no examples; the second "butter" is.
    assertThat(all.map { it.wordText }).doesNotContain("got")
    assertThat(all.single { it.wordText == "butter" }.diffCost).isWithin(1e-9).of(0.9)
    assertThat(h.occurrences.examples(FLAPPING, limit = 0)).isEmpty()
  }

  @Test
  fun symbolExamplesMatchTheHeardOrTheDictionaryFormAsWholeSymbols() = runBlocking<Unit> {
    index(
      "sym",
      TestClips.analysis(
        phrase(
          0.0, 6.0,
          word("water", 0.5, "flapping", diffCost = 0.1),
          word("winter", 1.5, "flapping", canonical = "w ɪ n t ɚ", realized = "w ɪ n ɾ̃ ɚ", diffCost = 0.2),
          word("church", 2.5, "t_deletion", canonical = "tʃ ɝ tʃ", realized = "tʃ ɝ ʃ", diffCost = 0.05),
        ),
      ),
    )

    // F-16-AC3: [ɾ] is heard in "water"; the nasal flap of "winter" is another symbol.
    assertThat(h.occurrences.examplesWithSymbol("ɾ").map { it.wordText }).containsExactly("water")
    // A /t/ through the dictionary form (architecture 9.4), never the "t" of "tʃ".
    assertThat(h.occurrences.examplesWithSymbol("t").map { it.wordText }).containsExactly("water", "winter").inOrder()
    assertThat(h.occurrences.examplesWithSymbol("ʃ").map { it.wordText }).containsExactly("church")
    assertThat(h.occurrences.examplesWithSymbol("ˈtʃ").map { it.wordText }).containsExactly("church")
    assertThat(h.occurrences.examplesWithSymbol("ʒ")).isEmpty()
    assertThat(h.occurrences.examplesWithSymbol("")).isEmpty()
  }

  @Test
  fun symbolExamplesStopAtSixByDefault() = runBlocking<Unit> {
    val records = index("a", TestClips.flappingCorpus())

    val examples = h.occurrences.examplesWithSymbol("ɾ")

    assertThat(examples).hasSize(ExamplePicker.SYMBOL_LIMIT)
    val tokenizer = IpaTokenizer(h.reference.reference.value.ipaTokens)
    val picked = ExamplePicker.withSymbol(records, "ɾ", tokenizer)
    assertThat(examples.map { it.word to it.change }).containsExactlyElementsIn(picked.map { it.word to it.change }).inOrder()
  }

  @Test
  fun theQuizPoolHoldsUpToNPerChangeMostDivergentFirstWithTheWordsOtherLabels() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    index("b", TestClips.analysis(phrase(0.0, 6.0, word("better", 0.5, "flapping", "vowel_reduction", diffCost = 0.95))))

    val pool = h.occurrences.quizPool(perChange = 5)

    assertThat(pool.keys).containsExactly(FLAPPING, ChangeLabel("linking"), ChangeLabel("t_deletion"), REDUCTION).inOrder()
    assertThat(pool.getValue(FLAPPING).map { it.wordText }).containsExactly("better", "butter", "pretty", "party", "getting")
      .inOrder()
    assertThat(pool.getValue(FLAPPING).first().otherChanges).containsExactly(REDUCTION)
    assertThat(pool.getValue(FLAPPING).drop(1).flatMap(Occurrence::otherChanges)).isEmpty()
    assertThat(pool.getValue(REDUCTION).single().otherChanges).containsExactly(FLAPPING)
    assertThat(pool.getValue(ChangeLabel("linking"))).hasSize(2)
    val everything = h.occurrences.quizPool().getValue(FLAPPING)
    assertThat(everything).hasSize(13)
    assertThat(everything.map { it.wordText }).doesNotContain("got")
    assertThat(h.occurrences.quizPool(perChange = 0)).isEmpty()
  }

  @Test
  fun variantsGroupAWordByHowItWasHeard() = runBlocking<Unit> {
    index(
      "v",
      TestClips.analysis(
        phrase(0.0, 6.0, word("water", 0.5, "flapping", "vowel_reduction", diffCost = 0.3)),
        phrase(10.0, 16.0, word("water", 10.5, "flapping", diffCost = 0.1)),
        phrase(20.0, 26.0, word("Water,", 20.5, "flapping", diffCost = 0.2)),
        phrase(30.0, 36.0, word("water", 30.5, "glottalization", realized = "w ɔ ʔ ɚ", diffCost = 0.4)),
        phrase(40.0, 46.0, word("water", 40.5, "flapping", lowConfidence = true)),
        phrase(50.0, 56.0, word("waiter", 50.5, "flapping")),
      ),
    )

    val variants = h.occurrences.variants("WATER")

    assertThat(variants.map { it.realizedIpa to it.count }).containsExactly("wɔɾɚ" to 3, "wɔʔɚ" to 1).inOrder()
    assertThat(variants.first().examples.map { it.diffCost }).containsExactly(0.1, 0.2, 0.3).inOrder()
    assertThat(h.occurrences.variants("…")).isEmpty()
    assertThat(h.occurrences.variants("nothing")).isEmpty()
  }

  @Test
  fun totalsCountWhatThePlayableClipsHold() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus(), wordCount = 40)
    index("b", TestClips.flappingCorpus(), wordCount = 70, audio = emptyMap())
    h.addClip("c", analysis = null, wordCount = 10)

    assertThat(h.occurrences.totals().first()).isEqualTo(CorpusTotals(clips = 1, shorts = 9, words = 40, occurrences = 15))
  }

  /** Seeds and indexes clip [id] from [analysis]; returns its derived occurrence records. */
  private suspend fun index(
    id: String,
    analysis: JsonObject,
    audio: Map<String, String> = mapOf(CorpusHarness.AUDIO_MIX to CorpusHarness.READY),
    wordCount: Int? = null,
  ): List<OccurrenceRecord> {
    val bytes = CorpusHarness.bytes(analysis)
    val job = h.addClip(id, bytes, audio = audio, wordCount = wordCount)
    h.indexer.index(job)
    val clip = AnalysisParser.parse(job, "r", bytes.inputStream(), gzip = false)
    return ClipDerivation.derive(clip, ChangeAdvice(h.reference.reference.value)).occurrences
  }

  private companion object {
    val FLAPPING = ChangeLabel("flapping")
    val REDUCTION = ChangeLabel("vowel_reduction")
    const val TIMEOUT_MS = 5_000L
  }
}
