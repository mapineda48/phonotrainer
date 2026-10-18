package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-16-AC1 on the bundled IPA layout: at the largest system font size on a 360 dp wide screen, every symbol is
 * reachable without sideways page scrolling and none is clipped. Native graphics, so the symbols are measured with the
 * real Charis metrics (the default Robolectric graphics measure text as nearly zero wide).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class IpaChartLargeTextTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val content = BundledContent.didactic()
  private val layout = content.layout
  private val names = LearnLogic.names(layout, content)

  private fun chart() =
    IpaChartUiState(layout = layout, weak = layout.reducedVowels.toSet(), names = names, selected = null, detail = null)

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(LearnTags.IPA_LIST).performScrollToNode(hasTestTag(tag))
  }

  @Test
  fun everySymbolFitsTheNarrowScreenAtTheLargestTextWithoutSidewaysScrolling() {
    // F-16-AC1.
    screen.setContent(ScreenVariant(fontScale = 2f)) { IpaChartScreen(chart(), IpaChartActions()) }
    val width = compose.onRoot().fetchSemanticsNode().size.width
    assertThat(width / compose.density.density).isWithin(1f).of(360f)
    val sideways = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
    assertThat(sideways.fetchSemanticsNodes()).isEmpty()
    val problems = mutableListOf<String>()
    for (symbol in layout.placed + layout.unplaced) {
      scrollTo(LearnTags.symbol(symbol))
      val button = compose.onNodeWithTag(LearnTags.symbol(symbol)).fetchSemanticsNode()
      val bounds = button.boundsInRoot
      if (bounds.left < 0f || bounds.right > width + 1f) problems += "[$symbol] is outside the screen: $bounds"
      val glyph = compose.onNode(hasText(symbol) and hasAnyAncestor(hasTestTag(LearnTags.symbol(symbol))), useUnmergedTree = true)
        .fetchSemanticsNode()
      val layouts = mutableListOf<TextLayoutResult>()
      glyph.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
      // The layout the semantics hand out is laid out at the width offered, so its own overflow flag is not the
      // measure: the glyphs' natural width and height must fit the size the text was given, on one line.
      val text = layouts.single()
      val needed = kotlin.math.ceil(text.multiParagraph.maxIntrinsicWidth)
      if (needed > text.size.width + 1 || text.multiParagraph.height > text.size.height + 1 || text.lineCount != 1) {
        problems += "[$symbol] is clipped: needs ${needed}x${text.multiParagraph.height}, has ${text.size}, ${text.lineCount} lines"
      }
      if (glyph.boundsInRoot.right > bounds.right + 1f || glyph.boundsInRoot.left < bounds.left - 1f) problems += "[$symbol] overflows its button"
    }
    assertThat(problems).isEmpty()
  }
}
