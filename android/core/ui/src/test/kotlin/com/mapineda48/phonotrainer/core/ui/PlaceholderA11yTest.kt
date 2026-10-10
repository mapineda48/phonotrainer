package com.mapineda48.phonotrainer.core.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.testing.a11y.A11yAudit
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCard
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The placeholders other work packages build on pass the accessibility audit of architecture 18 (labels and 48 dp
 * targets on every clickable node), and A11yAudit itself runs on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceholderA11yTest {
  @get:Rule
  val compose = createComposeRule()

  private lateinit var density: Density

  @Test
  fun aDestinationStubWithActionsIsClean() {
    compose.setContent {
      density = LocalDensity.current
      PhonoTheme {
        DestinationStub(title = "Learn", lede = "How native speakers change words when they talk.") {
          PhonoButton(text = "IPA chart", onClick = {})
          ExplainButton(term = "schwa", onClick = {})
        }
      }
    }
    compose.onNodeWithTag(UiTags.STUB_TITLE).assertIsDisplayed()
    A11yAudit.assertClean(compose, density)
  }

  @Test
  fun theStatusCardOffersConnectWhenNeverPaired() {
    compose.setContent {
      density = LocalDensity.current
      PhonoTheme {
        ConnectionStatusCard(
          status = ComputerStatus.NotConnected,
          sync = SyncState.Idle,
          counts = RequestCounts(waiting = 2, onComputer = 0, needsAttention = 0, readyNew = 0),
          transfers = null,
          onAction = {},
        )
      }
    }
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.CONNECT)).assertIsDisplayed()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.SEE_REQUESTS)).assertIsDisplayed()
    A11yAudit.assertClean(compose, density)
  }
}
