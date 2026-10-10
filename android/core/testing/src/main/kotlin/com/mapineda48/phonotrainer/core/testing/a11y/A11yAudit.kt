package com.mapineda48.phonotrainer.core.testing.a11y

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.Density

/**
 * The automated accessibility audit of architecture 18 (SC-12): every clickable node in the merged semantics tree
 * has an accessible label (text or content description) and touch bounds of at least 48 × 48 dp. Every screen test
 * calls it after setting its content.
 */
object A11yAudit {
  private const val MIN_TOUCH_DP = 48f

  fun assertClean(rule: SemanticsNodeInteractionsProvider, density: Density) {
    val problems = rule.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes()
      .mapNotNull { node -> problemOf(node, density) }
    check(problems.isEmpty()) { "Accessibility audit failed:\n" + problems.joinToString("\n") { "- $it" } }
  }

  private fun problemOf(node: SemanticsNode, density: Density): String? {
    val label = labelOf(node)
    val bounds = node.touchBoundsInRoot
    val minPx = with(density) { MIN_TOUCH_DP * this.density }
    // A small tolerance for sub-pixel rounding of the 48 dp minimum.
    val tooSmall = bounds.width < minPx - 1f || bounds.height < minPx - 1f
    return when {
      label.isNullOrBlank() -> "clickable node ${node.id} has no label"
      tooSmall -> {
        val w = bounds.width / density.density
        val h = bounds.height / density.density
        "\"$label\" is ${"%.0f".format(w)} × ${"%.0f".format(h)} dp, under 48 × 48 dp"
      }
      else -> null
    }
  }

  private fun labelOf(node: SemanticsNode): String? {
    val config = node.config
    val described = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    return listOfNotNull(described, text).firstOrNull { it.isNotBlank() }
  }
}
