package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.domain.api.DidacticContent
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.MouthDemo
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import java.util.Locale

/** The pure rules of :feature:learn, tested on the JVM. */
internal object LearnLogic {
  /** The glossary prefix of a symbol ("ipa:ɾ") and of a measure ("metric:deviate"). */
  const val IPA_PREFIX = "ipa:"
  const val METRIC_PREFIX = "metric:"

  private val PLAIN_FIRST_WORD = Regex("^[a-z][a-z-]*$")
  private val MARKS = Regex("[ˈˌː]")

  /**
   * The name Learn shows for [label]: the reference's name with its first letter capitalized when its first word is a
   * plain word ("flapping" → "Flapping", "h-dropping" → "H-dropping"), as it stands otherwise ("t/d deletion",
   * "nt → n": a capital would change what the symbols say). A label the reference does not name reads as its key.
   */
  fun changeName(reference: Reference, label: ChangeLabel): String {
    val name = reference.labels[label] ?: label.value.replace('_', ' ')
    val first = name.substringBefore(' ')
    val plain = PLAIN_FIRST_WORD.matches(first) && (first.length >= 3 || '-' in first)
    return if (plain) name.replaceFirstChar { it.titlecase(Locale.ROOT) } else name
  }

  fun card(reference: Reference, label: ChangeLabel): ChangeCardUi = ChangeCardUi(
    label = label,
    name = changeName(reference, label),
    family = reference.familyFor(label),
    description = reference.descriptions[label]?.takeIf { it.isNotBlank() },
    advice = reference.practice[label],
  )

  /**
   * Learn's sections (spec F-15 item 1; web `LearnHome`): one per family in the reference's order, its members in
   * order, then "Whole-word changes": the labels the reference names that belong to no family (the lexical contraction
   * and the elided word today), in the reference's order. A label listed in a family but not named by the reference
   * is still shown (under its key), so a newer computer's change never disappears.
   */
  fun sections(reference: Reference): List<FamilySectionUi> {
    val families = reference.families.map { family ->
      FamilySectionUi(family.family, family.label, family.members.map { card(reference, it) })
    }
    val inFamilies = reference.families.flatMap { it.members }.toSet()
    val others = reference.labels.keys.filter { it !in inFamilies && it !in reference.familyOf }
    val whole = if (others.isEmpty()) emptyList() else listOf(FamilySectionUi(null, null, others.map { card(reference, it) }))
    return families.filter { it.cards.isNotEmpty() } + whole
  }

  /** True when [label] is a change Learn has a page for. */
  fun isKnown(reference: Reference, label: ChangeLabel): Boolean =
    label in reference.labels || reference.families.any { label in it.members }

  /** The family's own name for the phenomenon page's chip; null for a change outside the four families. */
  fun familyName(reference: Reference, label: ChangeLabel): String? =
    reference.familyOf[label]?.let { family -> reference.families.firstOrNull { it.family == family }?.label }
      ?: reference.families.firstOrNull { label in it.members }?.label

  /** The mouth demo with each changing sound's name and what the mouth does (the v1-core fallback, work-plan 4.4). */
  fun demo(demo: MouthDemo?, content: DidacticContent): MouthDemoUi? {
    demo ?: return null
    return MouthDemoUi(
      word = demo.word,
      dictionary = demo.dictionary,
      said = demo.said,
      from = sound(demo.from, content),
      to = demo.to?.let { sound(it, content) },
    )
  }

  fun sound(symbol: String, content: DidacticContent): SoundUi {
    val phone = content.phone(symbol)
    return SoundUi(symbol, phone?.name?.let(::capitalize), phone?.cue)
  }

  fun capitalize(text: String): String = text.replaceFirstChar { it.titlecase(Locale.ROOT) }

  /** The symbol without its stress and length marks (the articulation table's key, web `bareSymbol`). */
  fun bare(symbol: String): String = symbol.replace(MARKS, "")

  /**
   * The symbol the chart opens on (P-09): [requested] when the articulation table knows it (as written, or without its
   * marks), else nothing (web: an unknown `?symbol=` selects nothing).
   */
  fun selectable(requested: String?, content: DidacticContent): String? {
    val symbol = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return symbol.takeIf { content.phone(it) != null }
  }

  /** The glossary entry of [symbol]: "ipa:" + the symbol as written, else without its marks. */
  fun symbolEntry(symbol: String, content: DidacticContent): GlossaryEntry? =
    content.glossary(IPA_PREFIX + symbol) ?: content.glossary(IPA_PREFIX + bare(symbol))

  /** The symbol sheet's text (spec F-16): name, what the mouth does (unless the glossary says it), body, "As in", the
   *  change it signals (the glossary's "Learn more" when it is a change's page). */
  fun symbolDetail(symbol: String, content: DidacticContent, reference: Reference, examples: ExamplesUi): SymbolDetailUi {
    val phone = content.phone(symbol)
    val entry = symbolEntry(symbol, content)
    val body = entry?.body?.takeIf { it.isNotBlank() }
    val cue = phone?.cue?.takeIf { it.isNotBlank() && (body == null || !body.contains(it.trim())) }
    val change = (entry?.learnMore as? LinkTarget.Phenomenon)?.label?.let { ChangeLinkUi(it, reference.labels[it] ?: it.value) }
    return SymbolDetailUi(
      symbol = symbol,
      name = phone?.name?.let(::capitalize),
      cue = cue,
      body = body,
      asIn = phone?.example?.takeIf { it.isNotBlank() },
      change = change,
      examples = examples,
    )
  }

  /** Each symbol the chart places (and the ones it does not) with its phonetic name, for TalkBack and large text. */
  fun names(layout: IpaLayout, content: DidacticContent): Map<String, String> =
    (layout.placed + layout.unplaced).distinct().mapNotNull { symbol -> content.phone(symbol)?.name?.let { symbol to it } }.toMap()

  /** "Words we use" split into its two groups (spec F-14, D-47): the terms, then the measures. */
  fun glossaryGroups(hits: List<GlossaryHit>): Pair<List<GlossaryHit>, List<GlossaryHit>> =
    hits.filterNot { it.key.startsWith(METRIC_PREFIX) } to hits.filter { it.key.startsWith(METRIC_PREFIX) }
}
