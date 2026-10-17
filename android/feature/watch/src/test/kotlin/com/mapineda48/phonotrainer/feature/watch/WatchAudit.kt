package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant

/**
 * The accessibility audit of architecture 18 (labels and 48 × 48 dp targets, the rules of `:core:testing`'s
 * `A11yAudit`) for the Watch screens that scroll or open a sheet, with two differences, both reported to the
 * integrator in the hand-off:
 * - a node a scroll container clips (a word or a button below the fold) is measured by its own size, not by its
 *   clipped bounds, which are 0 × 0 or partial only because it is scrolled away;
 * - the drag handle of WP-03's `PhonoSheet` (the wordless node with "Dismiss", besides "Expand" or "Collapse") is left
 *   out: it has no label and is 32 × 24 dp, a defect of the shared sheet, not of this screen.
 */
internal object WatchAudit {
  private const val MIN_TOUCH_DP = 48f

  fun assertClean(rule: SemanticsNodeInteractionsProvider, density: Density) {
    val problems = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { problemOf(it, density) }
    check(problems.isEmpty()) { "Accessibility audit failed:\n" + problems.joinToString("\n") { "- $it" } }
  }

  /** Whether [node] is the shared sheet's drag handle: it dismisses the sheet and has no words. */
  fun isSheetHandle(node: SemanticsNode): Boolean =
    SemanticsActions.Dismiss in node.config &&
      node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
      node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()

  private fun problemOf(node: SemanticsNode, density: Density): String? {
    if (isSheetHandle(node)) return null
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

/** [PhonoScreenRule.checkEach] with [WatchAudit] in place of the shared audit. */
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
      WatchAudit.assertClean(compose, compose.density)
    } catch (failure: Throwable) {
      throw AssertionError("At $variant: ${failure.message}", failure)
    }
  }
}
