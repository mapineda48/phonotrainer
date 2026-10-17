package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.ExamplePicker
import com.mapineda48.phonotrainer.core.analysis.OccurrenceRecord
import com.mapineda48.phonotrainer.core.analysis.WordKey
import com.mapineda48.phonotrainer.core.common.ipa.IpaTokenizer
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceDao
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceRow
import com.mapineda48.phonotrainer.core.domain.api.OccurrenceRepository
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.CorpusTotals
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.WordVariant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The corpus views computed on the phone (spec D-16; architecture 9.4): counts, examples, symbol examples, quiz pools
 * and word variants, over the eligible occurrences of playable clips only, with `:core:analysis`'s rules
 * ([ExamplePicker]) deciding which rows are examples.
 */
internal class RoomOccurrenceRepository @Inject constructor(
  private val dao: OccurrenceDao,
  private val reference: ReferenceRepository,
) : OccurrenceRepository {
  /**
   * Spec D-48's two units per change: `shorts` = what its change mode plays (Home chips, the mode picker, Learn's
   * change cards), `occurrences` = its eligible occurrences (the phenomenon page). Changes with none are absent.
   */
  override fun changeCounts(): Flow<Map<ChangeLabel, ChangeCount>> = dao.changeCounts()
    .map { rows -> rows.associate { ChangeLabel(it.change) to ChangeCount(shorts = it.shorts, occurrences = it.occurrences) } }
    .distinctUntilChanged()

  /** The phenomenon page's "Examples from your clips" (spec F-15 item 2): [ExamplePicker.pick] over the change's rows. */
  override suspend fun examples(change: ChangeLabel, limit: Int, onlyClip: JobId?): List<Occurrence> =
    dao.eligibleOf(change.value, onlyClip?.value).picked { ExamplePicker.pick(it, limit) }

  /**
   * The IPA symbol sheet's "In your clips" (spec F-16): [ExamplePicker.withSymbol], the heard OR the dictionary form
   * holding [symbol] as one of the reference's symbols (architecture 9.4). The query narrows the rows to those whose
   * forms contain the symbol's first character; the tokenizer decides.
   */
  override suspend fun examplesWithSymbol(symbol: String, limit: Int): List<Occurrence> {
    val bare = IpaTokenizer.bareSymbol(symbol)
    if (bare.isEmpty() || limit <= 0) return emptyList()
    val tokenizer = IpaTokenizer(reference.reference.value.ipaTokens)
    return dao.eligibleLike(likeContaining(bare.substring(0, 1)))
      .picked { ExamplePicker.withSymbol(it, symbol, tokenizer, limit) }
  }

  /**
   * Practice's pool (spec F-17; web `PracticePage` loads 80 per change): per change, its first [perChange] eligible
   * occurrences, most divergent first, the order the server's corpus answers and the web's quiz reads.
   */
  override suspend fun quizPool(perChange: Int): Map<ChangeLabel, List<Occurrence>> {
    if (perChange <= 0) return emptyMap()
    val out = LinkedHashMap<ChangeLabel, MutableList<Occurrence>>()
    for (row in dao.eligibleByChange()) {
      val list = out.getOrPut(ChangeLabel(row.occurrence.change)) { ArrayList() }
      if (list.size < perChange) list += row.toOccurrence()
    }
    return out
  }

  /**
   * How [word] was heard across the phone's clips (web `/api/corpus/variants`, read by the quiz's "Which
   * pronunciation?"): its eligible occurrences grouped by heard form, each counted once per word of a clip, most
   * frequent first (ties by form); each variant brings up to [VARIANT_EXAMPLES] examples, clearest first. Only words
   * with a labelled change are indexed, so the forms are the changed ones (architecture 9.4).
   */
  override suspend fun variants(word: String): List<WordVariant> {
    val key = WordKey.of(word)
    if (key.isEmpty()) return emptyList()
    val rows = dao.eligibleOfWord(key)
    val byForm = LinkedHashMap<String, LinkedHashMap<WordRef, OccurrenceRow>>()
    for (row in rows) {
      val form = row.occurrence.realizedIpa ?: continue
      val words = byForm.getOrPut(form) { LinkedHashMap() }
      // Rows come clearest first, so a word's first row is its clearest label.
      words.putIfAbsent(row.wordRef, row)
    }
    return byForm.map { (form, words) ->
      WordVariant(
        realizedIpa = form,
        count = words.size,
        examples = words.values.take(VARIANT_EXAMPLES).map { it.toOccurrence() },
      )
    }.sortedWith(compareByDescending<WordVariant> { it.count }.thenBy { it.realizedIpa })
  }

  /** What the playable clips hold: clips with shorts, their shorts, their words and their eligible occurrences. */
  override fun totals(): Flow<CorpusTotals> = dao.totals()
    .map { CorpusTotals(clips = it.clips, shorts = it.shorts, words = it.words, occurrences = it.occurrences) }
    .distinctUntilChanged()

  /** The rows [pick] keeps among these (by word and change), as domain occurrences in [pick]'s order. */
  private inline fun List<OccurrenceRow>.picked(pick: (List<OccurrenceRecord>) -> List<OccurrenceRecord>): List<Occurrence> {
    val byKey = HashMap<Pair<WordRef, ChangeLabel>, OccurrenceRow>(size)
    val records = map { row -> row.occurrence.toRecord().also { byKey[it.word to it.change] = row } }
    return pick(records).mapNotNull { byKey[it.word to it.change]?.toOccurrence() }
  }

  private val OccurrenceRow.wordRef: WordRef
    get() = WordRef(JobId(occurrence.jobId), occurrence.segment, occurrence.word)

  companion object {
    /** Examples a word variant carries (the quiz plays one; a few let a screen offer another). */
    const val VARIANT_EXAMPLES: Int = 3

    /** A `LIKE` pattern (escape `\`) matching any text that contains [fragment] literally. */
    fun likeContaining(fragment: String): String {
      val escaped = buildString {
        for (char in fragment) {
          if (char == '%' || char == '_' || char == '\\') append('\\')
          append(char)
        }
      }
      return "%$escaped%"
    }
  }
}
