package com.mapineda48.phonotrainer.core.testing.fake.content

import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation
import com.mapineda48.phonotrainer.core.domain.model.Reference
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Settable fakes of the :core:content interfaces (work-plan 4.6; WP-04 owns this folder).

/**
 * In-memory didactic content, [SampleDidactic] by default; change [entries], [lessons], [layout] or [phones] to
 * arrange a test. [search] follows the real content's rules: the Terms (keys without a prefix) then the Measures
 * (`metric:`), each by title, matching the trimmed query in a title or an explanation, ignoring case, everything for a
 * blank query; a hit's snippet is the first sentence. [searches] records every query.
 */
class FakeDidacticContent(
  entries: Map<String, GlossaryEntry> = SampleDidactic.glossary,
  lessons: Map<ChangeLabel, LessonContent> = SampleDidactic.lessons,
  var layout: IpaLayout = SampleDidactic.ipaLayout,
  phones: Map<String, PhoneArticulation> = SampleDidactic.phones,
) : DidacticContent {
  val entries: MutableMap<String, GlossaryEntry> = entries.toMutableMap()
  val lessons: MutableMap<ChangeLabel, LessonContent> = lessons.toMutableMap()
  val phones: MutableMap<String, PhoneArticulation> = phones.toMutableMap()
  val searches = mutableListOf<String>()

  override fun glossary(key: String): GlossaryEntry? = entries[key]

  override fun lesson(change: ChangeLabel): LessonContent? = lessons[change]

  override fun ipaLayout(): IpaLayout = layout

  /** Without the stress and length marks, as the real lookup. */
  override fun phone(symbol: String): PhoneArticulation? = phones[symbol.replace(MARKS, "")]

  override fun search(query: String): List<GlossaryHit> {
    searches += query
    val needle = query.trim().lowercase(Locale.ROOT)
    return entries.values
      .filter { group(it.key) != null }
      .filter { "${it.title}\n${it.body}".lowercase(Locale.ROOT).contains(needle) }
      .sortedWith(compareBy<GlossaryEntry>({ group(it.key) }, { it.title.lowercase(Locale.ROOT) }, { it.key }))
      .map { GlossaryHit(it.key, it.title, firstSentence(it.body)) }
  }

  private companion object {
    val MARKS = Regex("[ˈˌː]")
    val SENTENCE = Regex("""[.!?](?=\s|$)""")

    fun firstSentence(body: String): String = SENTENCE.find(body)?.let { body.take(it.range.last + 1) } ?: body

    fun group(key: String): Int? = when {
      key.startsWith("metric:") -> 1
      ':' in key -> null
      else -> 0
    }
  }
}

/**
 * An in-memory reference, [SampleReference] by default; set [state] to arrange a test. [replace] records each call in
 * [replaced] and applies [onReplace]: by default it keeps the current reference with the new API version, as a body
 * that decodes would; return null from it to model a refused body (the current reference stays, as in the real one).
 */
class FakeReferenceRepository(initial: Reference = SampleReference.reference) : ReferenceRepository {
  val state = MutableStateFlow(initial)
  val replaced = mutableListOf<Replacement>()
  var onReplace: (json: String, apiVersion: Int) -> Reference? = { _, apiVersion ->
    state.value.copy(apiVersion = apiVersion)
  }

  override val reference: StateFlow<Reference> = state

  override suspend fun replace(json: String, apiVersion: Int) {
    replaced += Replacement(json, apiVersion)
    onReplace(json, apiVersion)?.let { state.value = it }
  }

  data class Replacement(val json: String, val apiVersion: Int)
}
