package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag

/** The design's 412 dp width and a height that holds a whole page at font scale 2.0 (the audit measures what shows). */
internal const val TALL_WINDOW = "w412dp-h6000dp-xxhdpi"

/** Every text and content description of the node tagged [tag] and of the nodes inside it, joined by spaces. */
internal fun SemanticsNodeInteractionsProvider.textsOf(tag: String): String =
  onAllNodes(hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
    .flatMap(::wordsOf)
    .distinct()
    .joinToString(" ")

/** What TalkBack reads of the node tagged [tag]: its merged text and content description. */
internal fun SemanticsNodeInteractionsProvider.spokenOf(tag: String): String =
  onNode(hasTestTag(tag)).fetchSemanticsNode().let(::wordsOf).joinToString(" ")

private fun wordsOf(node: SemanticsNode): List<String> =
  node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
