package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.AnalysisParser
import com.mapineda48.phonotrainer.core.analysis.ChangeAdvice
import com.mapineda48.phonotrainer.core.analysis.ClipDerivation
import com.mapineda48.phonotrainer.core.corpus.TestClips.phrase
import com.mapineda48.phonotrainer.core.corpus.TestClips.plain
import com.mapineda48.phonotrainer.core.corpus.TestClips.word
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.FeedSequence
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shorts and the feed's sequences (spec 5.3, F-06, F-07, F-09; architecture 9.2, 9.3): In order plays every short
 * of a playable clip, a change mode spotlights a reliable instance of its change in every short (F-06-AC5) clearest
 * first with one short per word first, Saved plays the saved items most recently saved first on the saved word.
 */
@RunWith(RobolectricTestRunner::class)
class ShortRepositoryTest {
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
  fun aClipsShortsComeInTimelineOrderWithTheirDefaultSpotlight() = runBlocking<Unit> {
    val bytes = CorpusHarness.bytes(THREE_SHORTS)
    val id = h.addClip("s", bytes)
    h.indexer.index(id)
    val derived = ClipDerivation.derive(
      AnalysisParser.parse(id, "r", bytes.inputStream(), gzip = false),
      ChangeAdvice(h.reference.reference.value),
    )

    val shorts = h.shorts.shorts(id).first()

    assertThat(shorts.map { it.id }).containsExactly(ShortId(id, 0), ShortId(id, 1), ShortId(id, 2)).inOrder()
    assertThat(shorts.map { it.ordinal }).containsExactly(1, 2, 3).inOrder()
    assertThat(shorts.map { it.ofTotal }.toSet()).containsExactly(3)
    assertThat(shorts.map { it.spotlight }).containsExactlyElementsIn(derived.shorts.map { it.defaultSpotlight }).inOrder()
    assertThat(shorts.map { it.playStartMs to it.playEndMs })
      .containsExactlyElementsIn(derived.shorts.map { it.bounds.playStartMs to it.bounds.playEndMs }).inOrder()
    assertThat(shorts.map { it.markedWords }).containsExactly(1, 0, 1).inOrder()
  }

  @Test
  fun inOrderPlaysEveryShortOfAPlayableClipIncludingThoseWithoutASpotlight() = runBlocking<Unit> {
    val id = index("s", THREE_SHORTS)
    val away = index("away", THREE_SHORTS, audio = emptyMap())

    val sequence = h.shorts.sequence(FeedMode.InOrder(id))

    assertThat(sequence.items.map { it.id.firstSegment }).containsExactly(0, 1, 2).inOrder()
    assertThat(sequence.items[1].spotlight).isNull() // "No labelled change here: listen to the rhythm."
    assertThat(sequence.items[0].spotlight?.word).isEqualTo(WordRef(id, 0, 1))
    assertThat(sequence.startIndex).isEqualTo(0)
    // The Clip page still lists the shorts of a clip not on the phone; the feed never streams.
    assertThat(h.shorts.sequence(FeedMode.InOrder(away)).items).isEmpty()
    assertThat(h.shorts.shorts(away).first()).hasSize(3)
  }

  @Test
  fun aChangeModeSpotlightsAReliableInstanceOfThatChangeInEveryShort() = runBlocking<Unit> {
    val id = index("a", TestClips.flappingCorpus())

    val items = h.shorts.sequence(FeedMode.Change(FLAPPING)).items

    assertThat(items).hasSize(9)
    for (item in items) {
      // F-06-AC5: a flapping instance that is not low confidence (nor too short, nor unheard).
      val spot = item.spotlight!!
      assertThat(spot.change).isEqualTo(FLAPPING)
      val row = h.db.occurrenceDao().rowsOfShort(id.value, item.id.firstSegment)
        .single { it.segment == spot.word.segment && it.word == spot.word.word && it.change == FLAPPING.value }
      assertThat(row.eligible).isTrue()
      assertThat(row.lowConfidence).isFalse()
    }
    // Short 4 holds a clearer but low-confidence "butter": its spotlight is "little".
    assertThat(items.map { spottedWord(id, it.spotlight!!.word) }).containsExactly(
      "waters", "betters", "citys", "little", "matter", "getting", "party", "pretty", "butter",
    ).inOrder()
  }

  @Test
  fun aChangeModeOrdersClearestFirstWithOneShortPerDistinctWordFirst() = runBlocking<Unit> {
    val id = index(
      "order",
      TestClips.analysis(
        phrase(0.0, 6.0, word("water", 0.5, "flapping", diffCost = 0.1)),
        phrase(10.0, 16.0, word("Water", 10.5, "flapping", diffCost = 0.2)),
        phrase(20.0, 26.0, word("city", 20.5, "flapping", diffCost = 0.3)),
        phrase(30.0, 36.0, plain("the", 30.5)),
      ),
    )

    val items = h.shorts.sequence(FeedMode.Change(FLAPPING)).items

    assertThat(items.map { it.id }).containsExactly(ShortId(id, 0), ShortId(id, 2), ShortId(id, 1)).inOrder()
    assertThat(items.map { it.ofTotal }.toSet()).containsExactly(4)
  }

  @Test
  fun aChangeModeCanStayInOneClipAndAChangeWithNoInstanceIsEmpty() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())
    val s = index("s", THREE_SHORTS)

    val inOneClip = h.shorts.sequence(FeedMode.Change(FLAPPING, onlyClip = s))

    assertThat(inOneClip.items.map { it.id }).containsExactly(ShortId(s, 0))
    assertThat(h.shorts.sequence(FeedMode.Change(ChangeLabel("h_dropping"))).items).isEmpty()
  }

  @Test
  fun savedPlaysTheSavedItemsMostRecentFirstOnTheSavedWord() = runBlocking<Unit> {
    val id = index("s", THREE_SHORTS)
    val away = index("away", THREE_SHORTS, audio = emptyMap())
    h.saved.items.value = listOf(
      saved(1, SavedKind.SHORT, ShortId(id, 0), WordRef(id, 0, 1), FLAPPING, at = 100),
      saved(2, SavedKind.WORD, ShortId(id, 2), WordRef(id, 2, 1), ChangeLabel("t_deletion"), at = 300),
      saved(3, SavedKind.SHORT, ShortId(id, 1), word = null, change = null, at = 200),
      saved(4, SavedKind.WORD, ShortId(id, 0), WordRef(id, 0, 1), FLAPPING, at = 50), // its short is already listed
      saved(5, SavedKind.SHORT, ShortId(away, 0), WordRef(away, 0, 1), FLAPPING, at = 400), // not on the phone
    )

    val items = h.shorts.sequence(FeedMode.Saved).items

    assertThat(items.map { it.id }).containsExactly(ShortId(id, 2), ShortId(id, 1), ShortId(id, 0)).inOrder()
    assertThat(items[0].spotlight?.word).isEqualTo(WordRef(id, 2, 1))
    assertThat(items[0].spotlight?.change).isEqualTo(ChangeLabel("t_deletion"))
    assertThat(items[1].spotlight).isNull()
    assertThat(items[2].spotlight?.word).isEqualTo(WordRef(id, 0, 1))
    assertThat(items[2].spotlight?.change).isEqualTo(FLAPPING)
  }

  @Test
  fun theShortHoldingAWordIsFoundByItsPhrase() = runBlocking<Unit> {
    val id = index("s", THREE_SHORTS)

    assertThat(h.shorts.shortContaining(WordRef(id, 1, 0))?.id).isEqualTo(ShortId(id, 1))
    assertThat(h.shorts.shortContaining(WordRef(id, 2, 1))?.spotlight?.word).isEqualTo(WordRef(id, 2, 1))
    assertThat(h.shorts.shortContaining(WordRef(id, 9, 0))).isNull()
    assertThat(h.shorts.shortContaining(WordRef(JobId("unknown"), 0, 0))).isNull()
  }

  @Test
  fun theMixComesFromTheStudySets() = runBlocking<Unit> {
    index("a", TestClips.flappingCorpus())

    assertThat(h.shorts.sequence(FeedMode.Mix(setId = 7))).isEqualTo(FeedSequence(FeedMode.Mix(7), emptyList(), 0))
  }

  private suspend fun index(
    id: String,
    analysis: JsonObject,
    audio: Map<String, String> = mapOf(CorpusHarness.AUDIO_MIX to CorpusHarness.READY),
  ): JobId {
    val job = h.addClip(id, CorpusHarness.bytes(analysis), audio = audio)
    h.indexer.index(job)
    return job
  }

  private suspend fun spottedWord(id: JobId, word: WordRef): String =
    h.content.clipData(id).phrases[word.segment].words[word.word].text

  private fun saved(
    itemId: Long,
    kind: SavedKind,
    short: ShortId,
    word: WordRef?,
    change: ChangeLabel?,
    at: Long,
  ) = SavedItem(
    id = itemId, kind = kind, word = word, short = short, change = change, savedAt = Instant.ofEpochSecond(at),
    lastWatchedAt = null, clipTitle = "Clip ${short.jobId.value}", available = true,
  )

  private companion object {
    val FLAPPING = ChangeLabel("flapping")

    /** Three shorts: "water" (flapping), a phrase with no change, "last" (t/d deletion). */
    val THREE_SHORTS: JsonObject = TestClips.analysis(
      phrase(0.0, 6.0, plain("the", 0.1), word("water", 0.5, "flapping")),
      phrase(10.0, 16.0, plain("the", 10.1), plain("end", 10.5)),
      phrase(20.0, 26.0, plain("the", 20.1), word("last", 20.5, "t_deletion", canonical = "l æ s t", realized = "l æ s")),
    )
  }
}
