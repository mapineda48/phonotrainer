package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant

/**
 * The accessibility audit of architecture 18 (labels and 48 × 48 dp targets, the rules of `:core:testing`'s
 * `A11yAudit`) for Learn's screens, which are lazy lists: the audit walks the list item by item, so every clickable
 * node is composed and measured once, with its full size when the list edge clips it. Two shared defects are left out,
 * as WP-18's `LessonAudit` leaves them out (both reported to the integrator by WP-18):
 * - the drag handle of `PhonoSheet` (a wordless node with "Dismiss");
 * - the options of WP-03's `SegmentedControl`, 42 dp tall inside a 48 dp row (design 9.1).
 */
internal object LearnAudit {
  private const val MIN_TOUCH_DP = 48f
  private const val SEGMENT_DP = 42f

  fun assertClean(rule: SemanticsNodeInteractionsProvider, density: Density, list: String? = null) {
    val problems = linkedSetOf<String>()
    fun pass() {
      rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node -> problemOf(node, density)?.let(problems::add) }
    }
    if (list == null) {
      pass()
    } else {
      val listNode = rule.onNodeWithTag(list)
      val rows = listNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: 0
      for (index in 0 until rows.coerceAtLeast(1)) {
        if (rows > 0) listNode.performScrollToIndex(index)
        pass()
      }
      if (rows > 0) listNode.performScrollToIndex(0)
    }
    check(problems.isEmpty()) { "Accessibility audit failed:\n" + problems.joinToString("\n") { "- $it" } }
  }

  private fun isSegment(node: SemanticsNode, density: Density): Boolean =
    node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
      SemanticsProperties.Selected in node.config &&
      node.size.height >= SEGMENT_DP * density.density - 1f

  private fun isSheetHandle(node: SemanticsNode): Boolean =
    SemanticsActions.Dismiss in node.config &&
      node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
      node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()

  private fun problemOf(node: SemanticsNode, density: Density): String? {
    if (isSheetHandle(node) || isSegment(node, density)) return null
    val config = node.config
    val label = listOfNotNull(
      config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
      config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
    ).firstOrNull { it.isNotBlank() }
    val clipped = node.touchBoundsInRoot
    val full = Rect(node.positionInRoot, node.size.toSize())
    // Clipped by the list's edge: measure the node itself. A node scrolled fully away has empty bounds: skip it.
    if (clipped.width <= 0f || clipped.height <= 0f) return null
    val scrolledAway = clipped.width < full.width - 1f || clipped.height < full.height - 1f
    val width = if (scrolledAway) full.width else clipped.width
    val height = if (scrolledAway) full.height else clipped.height
    val min = MIN_TOUCH_DP * density.density - 1f
    return when {
      label.isNullOrBlank() -> "clickable node ${node.id} has no label"
      width < min || height < min ->
        "\"$label\" is ${"%.0f".format(width / density.density)} × ${"%.0f".format(height / density.density)} dp, under 48 × 48 dp"
      else -> null
    }
  }
}

/** [PhonoScreenRule.checkEach] at [variants] with [LearnAudit] over [list] in place of the shared audit. */
internal fun PhonoScreenRule.checkEachInList(
  list: String?,
  variants: List<ScreenVariant> = ScreenVariant.REQUIRED,
  content: @Composable () -> Unit,
  check: (ScreenVariant) -> Unit = {},
) {
  setContent(variants.first(), content)
  variants.forEach { variant ->
    show(variant)
    try {
      check(variant)
      LearnAudit.assertClean(compose, compose.density, list)
    } catch (failure: Throwable) {
      throw AssertionError("At $variant: ${failure.message}", failure)
    }
  }
}
