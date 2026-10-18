package com.mapineda48.phonotrainer.core.study.quiz

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A session the way the web's Practice page builds it (spec F-17 item 2): kinds rotating from the seed, no occurrence
 * twice, no "Which change?" with a focus (F-17-AC2), the same questions for the same seed, and an early end when the
 * pool runs out ("There were not enough clips to build questions this time.").
 */
class QuizGeneratorTest {
  private val reference = SampleReference.reference
  private val job = JobId("20261010-091503-4be1")

  /** Ten playable words per change, each in its own five-word phrase, with two other ways each word was said. */
  private val changes = listOf("flapping", "linking", "h_dropping", "vowel_reduction").map(::ChangeLabel)
  private val pool: List<PoolEntry> = changes.flatMapIndexed { c, change ->
    (0 until 10).map { w -> PoolEntry(change, occurrence(segment = c * 10 + w, change = change)) }
  }

  private fun occurrence(segment: Int, change: ChangeLabel) = Occurrence(
    word = WordRef(job, segment, 2), shortFirstSegment = segment, wordText = "word$segment", dictIpa = "wˈɝd",
    realizedIpa = "wɝ", startMs = segment * 3_000L + 1_000, endMs = segment * 3_000L + 1_300, change = change,
    diffCost = 0.2, advice = Advice.PRODUCE, family = reference.familyOf[change] ?: Family.LEXICAL,
    crossesBoundary = false, nextWordText = null, clipTitle = "clip",
  )

  private val source = object : QuizSource {
    val asked = mutableListOf<String>()

    override suspend fun variants(word: String): List<WordVariant> {
      asked += word
      return listOf(WordVariant("wɝd", 4, emptyList()), WordVariant("wə", 2, emptyList()))
    }

    override suspend fun phrase(word: WordRef): Phrase = AnalysisFixtures.phrase(
      index = word.segment, startS = word.segment * 3.0, endS = word.segment * 3.0 + 2.5,
      words = List(5) { AnalysisFixtures.word(it, "w$it", word.segment * 3.0 + it * 0.5, "w", "w") },
    )
  }

  @Test
  fun aSessionHasTenQuestionsKindsRotatingAndNoOccurrenceTwice() = runTest {
    val questions = QuizGenerator(reference, source).questions(pool, Quiz.SESSION_LENGTH, seed = 4)

    assertThat(questions).hasSize(10)
    // seed 4: kindForIndex(index + 4 mod 3) starts at "change".
    assertThat(questions.map { it.kind }.take(4)).containsExactly(
      QuestionKind.CHANGE, QuestionKind.COUNT, QuestionKind.FORM, QuestionKind.CHANGE,
    ).inOrder()
    assertThat(questions.map { it.occurrence.word }.toSet()).hasSize(10)
    assertThat(questions.all { q -> q.options.any { it.id == q.answer } }).isTrue()
    assertThat(questions.filter { it.kind == QuestionKind.COUNT }.all { it.answer == "5" }).isTrue()
  }

  @Test
  fun theSameSeedGivesTheSameQuestionsAndAnotherSeedOthers() = runTest {
    val first = QuizGenerator(reference, source).questions(pool, 10, seed = 77)
    val again = QuizGenerator(reference, source).questions(pool, 10, seed = 77)
    val other = QuizGenerator(reference, source).questions(pool, 10, seed = 78)

    assertThat(again).isEqualTo(first)
    assertThat(other.map { it.key }).isNotEqualTo(first.map { it.key })
  }

  @Test
  fun aFocusAsksNoWhichChangeQuestionAndStaysOnItsChange() = runTest {
    for (seed in 0L until 6L) {
      val questions = QuizGenerator(reference, source).questions(pool, 10, seed, focus = ChangeLabel("linking"))
      assertThat(questions).hasSize(10)
      assertThat(questions.map { it.kind }).doesNotContain(QuestionKind.CHANGE)
      assertThat(questions.map { it.change }.toSet()).containsExactly(ChangeLabel("linking"))
    }
  }

  @Test
  fun occurrencesRepeatOnlyOnceEveryOneWasUsed() = runTest {
    val small = pool.filter { it.change == ChangeLabel("flapping") }.take(3)
    val questions = QuizGenerator(reference, source).questions(small, 10, seed = 1, focus = ChangeLabel("flapping"))
    assertThat(questions).hasSize(10)
    assertThat(questions.take(3).map { it.occurrence.word }.toSet()).hasSize(3)
  }

  @Test
  fun theSessionEndsEarlyWhenNothingMoreCanBeBuilt() = runTest {
    // A focus (no "Which change?"), words whose dictionary form is what was said and no phrase data: nothing to ask.
    val bare = object : QuizSource {
      override suspend fun variants(word: String): List<WordVariant> = emptyList()

      override suspend fun phrase(word: WordRef): Phrase? = null
    }
    val flat = pool.filter { it.change == ChangeLabel("flapping") }
      .map { it.copy(occurrence = it.occurrence.copy(dictIpa = "wɝ")) }
    assertThat(QuizGenerator(reference, bare).questions(flat, 10, seed = 1, focus = ChangeLabel("flapping"))).isEmpty()
    assertThat(QuizGenerator(reference, source).questions(emptyList(), 10, seed = 1)).isEmpty()
    // Two buildable words among the bare ones: the session stops at the first position nothing can be built for.
    val mixed = flat.take(8) + pool.filter { it.change == ChangeLabel("flapping") }.drop(8)
    val some = QuizGenerator(reference, bare).questions(mixed, 10, seed = 1, focus = ChangeLabel("flapping"))
    assertThat(some.size).isLessThan(10)
    assertThat(some.all { it.kind == QuestionKind.FORM }).isTrue()
  }

  @Test
  fun anUnavailableClipIsSkippedAndAnotherOccurrenceDrawn() = runTest {
    val failing = object : QuizSource {
      override suspend fun variants(word: String): List<WordVariant> = error("no variants")

      override suspend fun phrase(word: WordRef): Phrase? = null
    }
    val questions = QuizGenerator(reference, failing).questions(pool, 6, seed = 3)
    // Neither "Which pronunciation?" nor "How many words?" can be built: every question falls back to "Which change?".
    assertThat(questions).hasSize(6)
    assertThat(questions.map { it.kind }.toSet()).containsExactly(QuestionKind.CHANGE)
  }

  @Test
  fun variantsAreAskedOncePerWord() = runTest {
    source.asked.clear()
    QuizGenerator(reference, source).questions(pool, 10, seed = 2)
    assertThat(source.asked).containsNoDuplicates()
  }
}
