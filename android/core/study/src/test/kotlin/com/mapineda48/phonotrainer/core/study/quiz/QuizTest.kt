package com.mapineda48.phonotrainer.core.study.quiz

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.IntonationUnit
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import org.junit.Test

/**
 * The vitest cases of `web/src/features/practice/quiz.test.ts`, ported with the same inputs and expected outputs
 * (work-plan WP-14 Proof). Where the web asserts a property, the exact values the web's own code gives for the same
 * inputs are asserted too: they were computed by running `quiz.ts` itself under Node (`golden.json` in WP-14's notes),
 * so the port is proven bit for bit, generator included. The web's localStorage cases have their Room counterparts in
 * `PracticeRepositoryTest` (round trip) and `StudyJsonTest` (damaged data).
 */
class QuizTest {
  private val reference = SampleReference.reference

  private fun occurrence(
    segment: Int = 0,
    word: Int = 2,
    text: String = "got",
    startMs: Long = 1_000,
    endMs: Long = 1_200,
    dictIpa: String? = "ɡˈɑt",
    realizedIpa: String? = "ɡɑɾ",
    change: String = "flapping",
    others: List<String> = emptyList(),
  ) = Occurrence(
    word = WordRef(JOB, segment, word),
    shortFirstSegment = 0,
    wordText = text,
    dictIpa = dictIpa,
    realizedIpa = realizedIpa,
    startMs = startMs,
    endMs = endMs,
    change = ChangeLabel(change),
    diffCost = 0.3,
    advice = Advice.PRODUCE,
    family = Family.TD,
    crossesBoundary = false,
    nextWordText = null,
    clipTitle = "clip",
    otherChanges = others.map(::ChangeLabel),
  )

  private fun entry(change: String, word: Int = 2, others: List<String> = emptyList()) =
    PoolEntry(ChangeLabel(change), occurrence(word = word, change = change, others = others))

  private fun take(rng: Rng, n: Int) = List(n) { rng.next() }

  // describe("seededRng")

  @Test
  fun seededRngRepeatsExactlyForTheSameSeed() {
    val a = seededRng(42)
    val b = seededRng(42)
    val first = take(a, 3)
    assertThat(take(b, 3)).isEqualTo(first)
    assertThat(first.all { it >= 0 && it < 1 }).isTrue()
    // The web's own numbers for the same seeds (golden.json): rng42, rng0, and a session seed past 2^32.
    assertThat(first).containsExactly(0.6011037519201636, 0.44829055899754167, 0.8524657934904099).inOrder()
    assertThat(take(seededRng(0), 3)).containsExactly(0.26642920868471265, 0.0003297457005828619, 0.2232720274478197).inOrder()
    assertThat(take(seededRng(123_456_789L * 7919 + 3 * 104_729), 3))
      .containsExactly(0.6994342876132578, 0.0769948661327362, 0.7684679934754968).inOrder()
  }

  @Test
  fun shuffleDrawsLikeTheWeb() {
    assertThat(shuffle((0..9).toList(), seededRng(1))).containsExactly(7, 8, 3, 2, 1, 5, 9, 4, 0, 6).inOrder()
  }

  // describe("buildPool")

  @Test
  fun buildPoolKeepsOnlyOccurrencesThatCanBePlayedAndTrusted() {
    // The web's other two cases (no job id, low confidence) cannot reach the phone's pool: WP-10 serves only eligible
    // rows of playable clips, so a too-short word and a word with nothing heard are what is left to filter.
    val pool = Quiz.buildPool(
      mapOf(
        ChangeLabel("flapping") to listOf(
          occurrence(),
          occurrence(endMs = 1_040),
          occurrence(realizedIpa = null),
          occurrence(realizedIpa = ""),
        ),
      ),
    )
    assertThat(pool).hasSize(1)
    assertThat(pool[0].change).isEqualTo(ChangeLabel("flapping"))
  }

  @Test
  fun quizChangesLeaveOutTheElidedWordWhichHasNothingToHear() {
    assertThat(Quiz.quizChanges(reference)).doesNotContain(ChangeLabel("word_elision"))
    assertThat(Quiz.quizChanges(reference)).contains(ChangeLabel("flapping"))
    assertThat(Quiz.quizChanges(reference)).hasSize(15)
  }

  // describe("changeOptions")

  @Test
  fun changeOptionsOfferTheRightChangeAndThreeItDoesNotHaveFromOtherFamilies() {
    val target = entry("flapping", others = listOf("linking"))
    val options = Quiz.changeOptions(target, reference, seededRng(1))
    val ids = options.map { it.id }
    assertThat(ids).hasSize(4)
    assertThat(ids).contains("flapping")
    assertThat(ids).doesNotContain("linking")
    val families = ids.map { reference.familyOf[ChangeLabel(it)] ?: Family.LEXICAL }
    assertThat(families.toSet()).hasSize(4)
    // The web's draw (golden.json changeOptions1), labels from the reference.
    assertThat(ids).containsExactly("monophthongization", "contraction_lex", "place_assimilation", "flapping").inOrder()
    assertThat(options.map { it.label }).containsExactly("monophthongization", "lexical contraction", "place assimilation", "flapping").inOrder()
    assertThat(options.map { it.change?.value }).isEqualTo(ids)
  }

  // describe("formOptions")

  @Test
  fun formOptionsOfferWhatWasSaidTheDictionaryFormWithoutStressAndOtherWaysTheWordWasSaid() {
    val options = Quiz.formOptions(
      occurrence(),
      listOf(
        WordVariant("ɡɑʔ", 3, emptyList()),
        WordVariant("ɡɑɾ", 9, emptyList()), // same as said
        WordVariant("ɡɑt̚", 2, emptyList()),
        WordVariant("ɡɑ", 1, emptyList()),
      ),
      seededRng(3),
    )
    assertThat(options).isNotNull()
    val byId = options!!.associate { it.id to it.ipa }
    assertThat(byId["said"]).isEqualTo("ɡɑɾ")
    assertThat(byId["dictionary"]).isEqualTo("ɡɑt")
    assertThat(options).hasSize(4)
    assertThat(options.map { it.ipa }.toSet()).hasSize(4)
    // The web's order (golden.json formOptions3).
    assertThat(options.map { "${it.id}=${it.ipa}" })
      .containsExactly("dictionary=ɡɑt", "variant:3=ɡɑt̚", "said=ɡɑɾ", "variant:2=ɡɑʔ").inOrder()
    assertThat(options.first { it.id == "said" }.label).isEqualTo("[ɡɑɾ]")
  }

  @Test
  fun formOptionsGiveUpWhenThereIsNothingToChooseBetween() {
    assertThat(Quiz.formOptions(occurrence(dictIpa = "ɡˈɑɾ"), emptyList(), seededRng(1))).isNull()
  }

  // describe("count items")

  private fun word(text: String, start: Double) = AnalysisFixtures.word(0, text, start, "", "").copy(endS = start + 0.2)

  @Test
  fun countItemsUseTheIntonationUnitAroundTheWordWhenItIsShort() {
    val phrase = AnalysisFixtures.phrase(
      index = 0, startS = 0.0, endS = 7.2,
      words = listOf("I", "got", "to", "go", "now", "OK", "then", "bye").mapIndexed { i, t -> word(t, i.toDouble()) },
      intonationUnits = listOf(
        IntonationUnit(0.0, 4.2, 0, 4, "statement", null, null, null),
        IntonationUnit(5.0, 7.2, 5, 7, "statement", null, null, null),
      ),
    )
    assertThat(Quiz.phraseAround(phrase, 1)?.map { it.text }).containsExactly("I", "got", "to", "go", "now").inOrder()
  }

  @Test
  fun countItemsFallBackToAWindowOfWordsInALongSegment() {
    val phrase = AnalysisFixtures.phrase(index = 0, startS = 0.0, endS = 20.0, words = List(20) { word("w$it", it.toDouble()) })
    val words = Quiz.phraseAround(phrase, 10)!!
    assertThat(words).hasSize(6)
    assertThat(words.map { it.text }).contains("w10")
    assertThat(words.map { it.text }).containsExactly("w8", "w9", "w10", "w11", "w12", "w13").inOrder()
  }

  @Test
  fun countOptionsOfferFourPlausibleCountsIncludingTheRightOne() {
    val options = Quiz.countOptions(5, seededRng(9))
    assertThat(options.map { it.id.toInt() }.sorted()).containsExactly(4, 5, 6, 7).inOrder()
    assertThat(Quiz.countOptions(1, seededRng(9)).all { it.id.toInt() >= 1 }).isTrue()
    // The web's order and labels (golden.json count5, count1).
    assertThat(options.map { it.id }).containsExactly("4", "7", "6", "5").inOrder()
    assertThat(Quiz.countOptions(1, seededRng(9)).map { it.id }).containsExactly("2", "4", "3", "1").inOrder()
    assertThat(Quiz.countOptions(1, seededRng(9)).first { it.id == "1" }.label).isEqualTo("1 word")
    assertThat(options.first().label).isEqualTo("4 words")
  }

  // describe("drawEntry")

  private val pool = listOf(entry("flapping"), entry("linking", word = 5), entry("h_dropping", word = 6))

  @Test
  fun drawEntryRespectsAFocus() {
    for (seed in 0L until 20L) {
      assertThat(Quiz.drawEntry(pool, seededRng(seed), focus = ChangeLabel("linking"))?.change).isEqualTo(ChangeLabel("linking"))
    }
    assertThat(Quiz.drawEntry(pool, seededRng(1), focus = ChangeLabel("glottalization"))).isNull()
  }

  @Test
  fun drawEntryDrawsWeakSpotsMoreOften() {
    val stats = mapOf(
      ChangeLabel("flapping") to Tally(20, 20),
      ChangeLabel("linking") to Tally(20, 2),
      ChangeLabel("h_dropping") to Tally(20, 20),
    )
    val rng = seededRng(7)
    val counts = HashMap<ChangeLabel, Int>()
    repeat(600) {
      val drawn = Quiz.drawEntry(pool, rng, stats, weakSpots = true)!!
      counts[drawn.change] = (counts[drawn.change] ?: 0) + 1
    }
    assertThat(counts.getValue(ChangeLabel("linking"))).isGreaterThan(counts.getValue(ChangeLabel("flapping")) * 2)
    // The web's exact tallies (golden.json weak600).
    assertThat(counts).containsExactly(ChangeLabel("flapping"), 130, ChangeLabel("h_dropping"), 110, ChangeLabel("linking"), 360)
  }

  @Test
  fun drawEntryPicksWhatTheWebPicksAndAvoidsUsedOccurrences() {
    fun key(seed: Long, avoid: Set<String> = emptySet()) = Quiz.entryKey(Quiz.drawEntry(pool, seededRng(seed), avoid = avoid)!!)
    assertThat((0L..7L).map { key(it) }.map { it.substringAfter(':') })
      .containsExactly("0:2", "0:5", "0:6", "0:6", "0:6", "0:6", "0:5", "0:2").inOrder()
    val used = setOf("${JOB.value}:0:2", "${JOB.value}:0:5")
    assertThat((0L..3L).map { key(it, used) }.map { it.substringAfter(':') }).containsExactly("0:6", "0:6", "0:6", "0:6")
    // A Practice item's generator (seed × 7919 + index × 104729) with a session seed of 123456789.
    assertThat((0..4).map { i -> Quiz.entryKey(Quiz.drawEntry(pool, seededRng(123_456_789L * 7919 + i * 104_729L))!!) }
      .map { it.substringAfter(':') }).containsExactly("0:2", "0:2", "0:6", "0:6", "0:2").inOrder()
  }

  @Test
  fun theKindsRotateFormChangeCount() {
    assertThat((0..4).map(Quiz::kindForIndex)).containsExactly(
      QuestionKind.FORM, QuestionKind.CHANGE, QuestionKind.COUNT, QuestionKind.FORM, QuestionKind.CHANGE,
    ).inOrder()
  }

  // describe("stats")

  @Test
  fun statsRecordAnswers() {
    var stats = Quiz.recordAnswer(emptyMap(), ChangeLabel("flapping"), true)
    stats = Quiz.recordAnswer(stats, ChangeLabel("flapping"), false)
    assertThat(Quiz.accuracy(stats[ChangeLabel("flapping")])).isEqualTo(0.5)
    assertThat(Quiz.accuracy(null)).isNull()
    assertThat(Quiz.accuracy(Tally(0, 0))).isNull()
  }

  // Beyond the vitest file: the items quiz.ts builds from those options.

  @Test
  fun itemsCarryTheWebsPromptsClipsAndAnswers() {
    val change = Quiz.changeItem(entry("flapping"), reference, seededRng(1))
    assertThat(change.prompt).isEqualTo("Listen to “got”. Which change did you hear?")
    assertThat(change.answer).isEqualTo("flapping")
    assertThat(change.key).isEqualTo("change:${JOB.value}:0:2")
    assertThat(change.clip.startMs to change.clip.endMs).isEqualTo(900L to 1_320L)
    assertThat(change.context!!.startMs to change.context!!.endMs).isEqualTo(0L to 2_400L)

    val linking = Quiz.changeItem(entry("linking"), reference, seededRng(1))
    assertThat(linking.clip.endMs).isEqualTo(1_650L) // a boundary change keeps 0.45 s of the next word

    val form = Quiz.formItem(PoolEntry(ChangeLabel("flapping"), occurrence(text = "\"got,")), listOf(WordVariant("ɡɑʔ", 1, emptyList())), reference, seededRng(2))!!
    assertThat(form.prompt).isEqualTo("Listen to “got”. Which pronunciation did you hear?")
    assertThat(form.answer).isEqualTo("said")
    assertThat(form.kind).isEqualTo(QuestionKind.FORM)

    val phrase = AnalysisFixtures.phrase(
      index = 0, startS = 0.5, endS = 2.0,
      words = listOf(word("I", 0.5), word("got", 1.0).copy(changes = listOf(ChangeLabel("flapping"))), word("it", 1.5)),
    )
    val count = Quiz.countItem(entry("flapping", word = 1), phrase, seededRng(4))!!
    assertThat(count.answer).isEqualTo("3")
    assertThat(count.clip.startMs to count.clip.endMs).isEqualTo(420L to 1_850L)
    assertThat(count.phrase!!.map { it.changed }).containsExactly(false, true, false).inOrder()
    val unsure = AnalysisFixtures.phrase(index = 0, startS = 0.5, endS = 2.0, words = phrase.words.map { it.copy(lowConfidence = true) })
    assertThat(Quiz.countItem(entry("flapping", word = 1), unsure, seededRng(4))).isNull()
  }

  @Test
  fun displayWordStripsThePunctuationWhisperAttaches() {
    assertThat(Quiz.displayWord("order.")).isEqualTo("order")
    assertThat(Quiz.displayWord("¿don't?")).isEqualTo("don't")
    assertThat(Quiz.displayWord("...")).isEqualTo("...")
  }

  private companion object {
    val JOB = JobId("job-1")
  }
}
