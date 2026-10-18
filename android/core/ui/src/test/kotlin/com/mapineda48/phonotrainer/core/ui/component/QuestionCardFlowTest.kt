package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [IntrinsicFlowRow]: its intrinsic height is the height it lays out, so a card that takes its content's intrinsic
 * height and clips to it (WP-03's `PaperCard`) keeps every line and what follows them. Compose's `FlowRow` breaks
 * lines on the items' minimum intrinsic widths for its intrinsic height and so claimed fewer lines than it drew on the
 * managed device (the results card lost "Back to the start"); Robolectric's text measurement does not reproduce that
 * case, so the evidence is the device run of WP-21's hand-off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class QuestionCardFlowTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val labels = listOf("Play", "Slowly", "With context", "Again at half speed", "Open the lesson")

  /** A card as the design system draws it: the content's intrinsic height, clipped; then a last line below the flow. */
  @Composable
  private fun Card(flow: @Composable () -> Unit) {
    Box(Modifier.width(CARD).height(IntrinsicSize.Min).clipToBounds()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        flow()
        Box(Modifier.size(48.dp).testTag(LAST))
      }
    }
  }

  @Composable
  private fun Buttons() {
    labels.forEach { PhonoButton(it, onClick = {}, kind = PhonoButtonKind.SECONDARY) }
  }

  @Test
  fun theLineAfterAnIntrinsicFlowRowStaysInsideTheCard() {
    screen.setContent(ScreenVariant()) {
      Card { IntrinsicFlowRow(Modifier.testTag(FLOW), horizontalSpacing = 8.dp, verticalSpacing = 8.dp) { Buttons() } }
    }
    val flow = compose.onNodeWithTag(FLOW).getUnclippedBoundsInRoot()
    val flowHeight = (flow.bottom - flow.top).value
    val last = compose.onNodeWithTag(LAST).fetchSemanticsNode()
    // Several lines, and the box after them is whole: not squeezed and not cut by the card.
    assertThat(flowHeight).isGreaterThan(2 * 48f)
    assertThat(last.size.height).isEqualTo(with(compose.density) { 48.dp.roundToPx() })
    assertThat(last.boundsInRoot.height).isEqualTo(last.size.height.toFloat())
  }

  private companion object {
    const val FLOW = "test.flow"
    const val LAST = "test.last"
    val CARD = 300.dp
  }
}
