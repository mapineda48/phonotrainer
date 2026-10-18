package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel

/** Test tags of :feature:learn ("learn.<screen>.<element>", work-plan 2.6). */
object LearnTags {
  // Learn (T3)
  const val LIST = "learn.learn.list"
  const val HOW = "learn.learn.how"
  const val LINK_IPA = "learn.learn.ipa"
  const val LINK_GLOSSARY = "learn.learn.glossary"
  const val LINK_INSIGHTS = "learn.learn.insights"
  const val COUNTED_HERE = "learn.learn.countedHere"
  const val COUNTS_FAILED = "learn.counts.failed"

  /** Kept from the contract freeze: the Flapping card (the stub's link to the Flapping page). */
  const val LINK_FLAPPING = "learn.learn.card.flapping"

  /** A family's head ("learn.learn.family.TD"), or the whole-word changes' ("learn.learn.family.WHOLE"). */
  fun family(name: String): String = "learn.learn.family.$name"

  /** A change card. */
  fun card(label: ChangeLabel): String = "learn.learn.card.${label.value}"

  /** A change card's count line. */
  fun cardCount(label: ChangeLabel): String = "learn.learn.card.${label.value}.count"

  // Phenomenon page (P-08)
  const val BACK = "learn.page.back"
  const val PHENOMENON_LIST = "learn.phenomenon.list"
  const val PHENOMENON_EXPLAIN = "learn.phenomenon.explain"
  const val PHENOMENON_CHIPS = "learn.phenomenon.chips"
  const val PHENOMENON_DESCRIPTION = "learn.phenomenon.description"
  const val PHENOMENON_HOW = "learn.phenomenon.how"
  const val PHENOMENON_TIP = "learn.phenomenon.tip"
  const val PHENOMENON_NOTE = "learn.phenomenon.note"
  const val PHENOMENON_COPY = "learn.phenomenon.copy"
  const val PHENOMENON_REGISTER_EXPLAIN = "learn.phenomenon.registerExplain"
  const val PHENOMENON_MOUTH = "learn.phenomenon.mouth"
  const val PHENOMENON_MOUTH_PLAY = "learn.phenomenon.mouth.play"
  const val PHENOMENON_MOUTH_DRAWING = "learn.phenomenon.mouth.drawing"
  const val PHENOMENON_MOUTH_CHART = "learn.phenomenon.mouth.chart"
  const val PHENOMENON_EXAMPLES = "learn.phenomenon.examples"
  const val PHENOMENON_PRACTICE = "learn.phenomenon.practice"
  const val PHENOMENON_TIMES = "learn.phenomenon.times"
  const val PHENOMENON_COUNTED_HERE = "learn.phenomenon.countedHere"
  const val PHENOMENON_EXAMPLE_LIST = "learn.phenomenon.exampleList"
  const val PHENOMENON_NO_EXAMPLES = "learn.phenomenon.noExamples"
  const val PHENOMENON_UNKNOWN = "learn.phenomenon.unknown"
  const val CHANGE_SHEET = "learn.phenomenon.changeSheet"
  const val CHANGE_SHEET_WATCH = "learn.phenomenon.changeSheet.watch"

  /** The [index]-th example row (0-based) of a list of examples. */
  fun example(index: Int): String = "learn.example.$index"

  // IPA chart (P-09)
  const val IPA_LIST = "learn.ipa.list"
  const val IPA_CHOOSE = "learn.ipa.choose"
  const val IPA_SHEET = "learn.ipa.sheet"
  const val IPA_SHEET_SYMBOL = "learn.ipa.sheet.symbol"
  const val IPA_SHEET_NAME = "learn.ipa.sheet.name"
  const val IPA_SHEET_CUE = "learn.ipa.sheet.cue"
  const val IPA_SHEET_BODY = "learn.ipa.sheet.body"
  const val IPA_SHEET_AS_IN = "learn.ipa.sheet.asIn"
  const val IPA_SHEET_CHANGE = "learn.ipa.sheet.change"
  const val IPA_SHEET_MOUTH = "learn.ipa.sheet.mouth"
  const val IPA_SHEET_EXAMPLES = "learn.ipa.sheet.examples"
  const val IPA_SHEET_NO_EXAMPLES = "learn.ipa.sheet.noExamples"
  const val IPA_SHEET_CLOSE = "learn.ipa.sheet.close"

  /** A symbol button of the chart. */
  fun symbol(symbol: String): String = "learn.ipa.symbol.$symbol"

  /** A section of the chart ("consonants", "allophones", "syllabics", "vowels", "glides.0", "other"). */
  fun ipaSection(name: String): String = "learn.ipa.section.$name"

  // Words we use (F-14)
  const val GLOSSARY_LIST = "learn.glossary.list"
  const val GLOSSARY_SEARCH = "learn.glossary.search"
  const val GLOSSARY_COUNT = "learn.glossary.count"
  const val GLOSSARY_TERMS = "learn.glossary.terms"
  const val GLOSSARY_MEASURES = "learn.glossary.measures"
  const val GLOSSARY_NO_MATCH = "learn.glossary.noMatch"
  const val GLOSSARY_CLEAR = "learn.glossary.clear"

  /** A row of "Words we use" ("learn.glossary.row.schwa"). */
  fun glossaryRow(key: String): String = "learn.glossary.row.$key"
}
