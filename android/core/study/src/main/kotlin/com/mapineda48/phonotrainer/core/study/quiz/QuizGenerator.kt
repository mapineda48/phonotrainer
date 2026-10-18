package com.mapineda48.phonotrainer.core.study.quiz

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import kotlin.coroutines.cancellation.CancellationException

/** What a quiz needs beyond the pool: a word's other pronunciations and the phrase around an occurrence. */
interface QuizSource {
  /** How [word] was heard across the clips (web `/api/corpus/variants`). */
  suspend fun variants(word: String): List<WordVariant>

  /** The phrase holding [word], or null when its clip data is not available. */
  suspend fun phrase(word: WordRef): Phrase?
}

/**
 * Builds a run of questions the way the web's Practice page builds a session (`PracticePage.buildItem`), item by item:
 * a seeded generator per position (`seed × 7919 + index × 104729`), the kinds rotating from `kindForIndex(index + seed
 * mod 3)`, up to five draws per kind, never the same occurrence twice, and a fallback kind ("Which change?", or the
 * other listening kind when that one is skipped). The run ends at the first position nothing can be built for
 * (spec F-17 "pool exhausted early"). Questions are built up front and stored, so a session or a set never changes and
 * survives process death; the web builds them one at a time, with the accuracy of the answers given so far (a weak-spot
 * session here weighs by the accuracy at its start).
 */
class QuizGenerator(private val reference: Reference, private val source: QuizSource) {
  /**
   * Up to [count] questions from [pool]. [skipChangeKind]: every "Which change?" answer would be the same (a focus,
   * or a pool of one change), so that kind is replaced (spec F-17 item 2, F-17-AC2).
   */
  suspend fun questions(
    pool: List<PoolEntry>,
    count: Int,
    seed: Long,
    focus: ChangeLabel? = null,
    weakSpots: Boolean = false,
    stats: Map<ChangeLabel, Tally> = emptyMap(),
    skipChangeKind: Boolean = focus != null,
  ): List<Question> {
    val used = HashSet<String>()
    val out = ArrayList<Question>(count)
    for (index in 0 until count) {
      out += buildItem(index, seed, pool, focus, weakSpots, stats, skipChangeKind, used) ?: break
    }
    return out
  }

  private suspend fun buildItem(
    index: Int,
    seed: Long,
    pool: List<PoolEntry>,
    focus: ChangeLabel?,
    weakSpots: Boolean,
    stats: Map<ChangeLabel, Tally>,
    skipChangeKind: Boolean,
    used: MutableSet<String>,
  ): Question? {
    val rng = seededRng(seed * 7919 + index * 104729L)
    val preferred = Quiz.kindForIndex(index + Math.floorMod(seed, 3L).toInt())
    val kinds = when {
      !skipChangeKind -> listOf(preferred, QuestionKind.CHANGE)
      preferred == QuestionKind.CHANGE -> listOf(QuestionKind.FORM, QuestionKind.COUNT)
      else -> listOf(preferred, if (preferred == QuestionKind.FORM) QuestionKind.COUNT else QuestionKind.FORM)
    }
    for (kind in kinds) {
      repeat(ATTEMPTS) {
        val entry = Quiz.drawEntry(pool, rng, stats, weakSpots, focus, used) ?: return null
        val built = try {
          when (kind) {
            QuestionKind.CHANGE -> Quiz.changeItem(entry, reference, rng)
            QuestionKind.FORM -> Quiz.formItem(entry, variants(entry.occurrence.wordText), reference, rng)
            QuestionKind.COUNT -> source.phrase(entry.occurrence.word)?.let { Quiz.countItem(entry, it, rng) }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (unavailable: Exception) {
          null // this clip's data is unavailable: draw another
        }
        if (built != null) {
          used += Quiz.entryKey(entry)
          return built
        }
      }
    }
    return null
  }

  private val variantsByWord = HashMap<String, List<WordVariant>>()

  private suspend fun variants(word: String): List<WordVariant> =
    variantsByWord[word] ?: source.variants(word).also { variantsByWord[word] = it }

  private companion object {
    const val ATTEMPTS = 5
  }
}
