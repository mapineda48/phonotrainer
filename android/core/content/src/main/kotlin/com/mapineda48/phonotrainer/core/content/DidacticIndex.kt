package com.mapineda48.phonotrainer.core.content

import com.mapineda48.phonotrainer.core.content.json.DidacticJson
import com.mapineda48.phonotrainer.core.content.json.decodeDidactic
import com.mapineda48.phonotrainer.core.content.json.toDomain
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation
import java.util.Locale

/** didactic.json decoded once into lookup tables and the "Words we use" index. */
internal class DidacticIndex private constructor(json: DidacticJson) {
  val glossary: Map<String, GlossaryEntry> = json.glossary.mapValues { (key, entry) -> entry.toDomain(key) }
  val lessons: Map<ChangeLabel, LessonContent> =
    json.lessons.entries.associate { (change, lesson) -> ChangeLabel(change) to lesson.toDomain(change) }
  val ipaLayout: IpaLayout = json.ipaLayout.toDomain()
  val phones: Map<String, PhoneArticulation> = json.phones.associate { it.symbol to it.toDomain() }

  /** "Words we use" (spec F-14): the Terms (keys without a prefix), then the Measures (`metric:`), each by title. The
   *  `ipa:` entries are not listed: the IPA chart is their index. */
  private val wordsWeUse: List<Row> = glossary.values
    .filter { group(it.key) != null }
    .sortedWith(compareBy<GlossaryEntry>({ group(it.key) }, { it.title.lowercase(Locale.ROOT) }, { it.key }))
    .map { entry ->
      val hit = GlossaryHit(entry.key, entry.title, firstSentence(entry.body))
      Row(hit, "${entry.title}\n${entry.body}".lowercase(Locale.ROOT))
    }

  /** The phone of [symbol] without its stress and length marks (the web's `bareSymbol`; the extractor fails if the web
   *  changes that rule). */
  fun phone(symbol: String): PhoneArticulation? = phones[symbol.replace(MARKS, "")]

  /**
   * The rows of "Words we use" whose title or explanation contains [query], trimmed and ignoring case, as the Library's
   * search matches (spec F-14); every row, in order, when [query] is blank, so the page's "{n} of {total} terms" needs
   * nothing else.
   */
  fun search(query: String): List<GlossaryHit> {
    val needle = query.trim().lowercase(Locale.ROOT)
    return wordsWeUse.filter { needle in it.haystack }.map { it.hit }
  }

  private class Row(val hit: GlossaryHit, val haystack: String)

  companion object {
    /** Strict decoding (architecture 15.2); throws on any shape this app does not read. */
    fun decode(text: String): DidacticIndex = DidacticIndex(decodeDidactic(text))

    /** The glossary prefix of a measure ("Measures" in "Words we use"); a symbol's is `ipa:`. */
    private const val METRIC_PREFIX = "metric:"

    private val MARKS = Regex("[ˈˌː]")
    private val SENTENCE_END = Regex("""[.!?](?=\s|$)""")

    /** 0 for a term, 1 for a measure, null for an entry "Words we use" does not list. */
    private fun group(key: String): Int? = when {
      key.startsWith(METRIC_PREFIX) -> 1
      ':' in key -> null
      else -> 0
    }

    /** The first sentence of [body], its end mark included: the row's second line. */
    fun firstSentence(body: String): String =
      SENTENCE_END.find(body)?.let { body.substring(0, it.range.last + 1) } ?: body
  }
}
