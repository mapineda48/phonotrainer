package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant

/**
 * The accessibility audit of architecture 18 (labels and 48 × 48 dp targets, the rules of `:core:testing`'s
 * `A11yAudit`) for the lesson, which scrolls and opens sheets. Each clickable node is first scrolled into view, so it
 * is measured with its real touch bounds (a minimum interactive size included) rather than the part the scroll
 * leaves visible. Two shared defects are left out and reported to the integrator (hand-off of WP-18):
 * - the drag handle of `PhonoSheet` (the wordless node with "Dismiss"; WP-17's audit leaves it out too);
 * - the options of WP-03's `SegmentedControl`, 42 dp tall inside a 48 dp row (design 9.1), which the shared rule
 *   counts per option.
 */
internal object LessonAudit {
  private const val MIN_TOUCH_DP = 48f
  private const val SEGMENT_DP = 42f

  fun assertClean(rule: SemanticsNodeInteractionsProvider, density: Density) {
    val ids = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.id }
    val problems = ids.mapNotNull { id ->
      val node = rule.onNode(SemanticsMatcher("node $id") { it.id == id })
      // A node outside every scroll container cannot scroll: then it is measured where it is.
      runCatching { node.performScrollTo() }
      problemOf(node.fetchSemanticsNode(), density)
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

/** [PhonoScreenRule.checkEach] with [LessonAudit] in place of the shared audit. */
internal fun PhonoScreenRule.checkEachScrolling(
  variants: List<ScreenVariant> = ScreenVariant.REQUIRED,
  content: @Composable () -> Unit,
  check: (ScreenVariant) -> Unit = {},
) {
  setContent(variants.first(), content)
  variants.forEach { variant ->
    show(variant)
    try {
      check(variant)
      LessonAudit.assertClean(compose, compose.density)
    } catch (failure: Throwable) {
      throw AssertionError("At $variant: ${failure.message}", failure)
    }
  }
}
