package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The computer status card drawn (design 9.17): its one-sentence TalkBack summary, every control and what it asks
 * for, and every state through the accessibility audit at font scale 2.0 in light and dark (architecture 18, 21.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2000dp")
class ConnectionStatusCardTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val now = Instant.parse("2026-10-04T10:30:00Z")
  private val asked = mutableListOf<ConnectionAction>()

  private fun summary(): String =
    compose.onNodeWithTag(ConnectionTags.STATUS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

  @Test
  fun theSummaryReadsTheWholeCardAsOneSentence() {
    screen.setContent {
      ConnectionStatusCard(
        status = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 0, lowOnSpace = false),
        sync = SyncState.Idle,
        counts = RequestCounts(waiting = 2, onComputer = 1, needsAttention = 1, readyNew = 0, analyzing = 1, inLine = 0),
        transfers = null,
        onAction = { asked += it },
        extras = ConnectionCardExtras(
          now = now,
          checkedAt = now.minusSeconds(10),
          analyzing = RequestProgress(Stage.PHONES, 40, null, null, null, null),
          attentionAction = RequestAction.TRY_AGAIN,
        ),
      )
    }
    assertThat(summary()).isEqualTo(
      "Connected to Desk PC; Checked 10 s ago, analyzing 1; 2 waiting on this phone; 1 analyzing, 40 %, Hear the phones; " +
        "1 needs your attention.",
    )
    // The rows' words are drawn but read only once, in the summary.
    compose.onNodeWithText("2 waiting on this phone", useUnmergedTree = true).assertIsDisplayed()
    assertThat(compose.onAllNodes(hasText("2 waiting on this phone")).fetchSemanticsNodes()).isEmpty()

    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.SYNC_NOW)).performClick()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.SEE_REQUESTS)).performClick()
    // The inline action is the failed request's own words.
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.ATTENTION)).assertIsDisplayed()
    compose.onNodeWithText("Try again").performClick()
    assertThat(asked).containsExactly(ConnectionAction.SYNC_NOW, ConnectionAction.SEE_REQUESTS, ConnectionAction.ATTENTION).inOrder()
  }

  @Test
  fun theStorageLineAndTheBackgroundLineLeadToTheirScreens() {
    screen.setContent {
      ConnectionStatusCard(
        status = ComputerStatus.Offline,
        sync = SyncState.Idle,
        counts = RequestCounts(0, 0, 0, 0),
        transfers = null,
        onAction = { asked += it },
        extras = ConnectionCardExtras(storageNearlyFull = true, backgroundRestricted = true),
      )
    }
    compose.onNodeWithTag(ConnectionTags.row(ConnectionRowKind.STORAGE)).performClick()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.BATTERY_SETTINGS)).performClick()
    assertThat(asked).containsExactly(ConnectionAction.STORAGE, ConnectionAction.BATTERY_SETTINGS).inOrder()
    // No request counts: nothing opens Requests.
    assertThat(compose.onAllNodesWithTag(ConnectionTags.action(ConnectionAction.SEE_REQUESTS)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun eachStatusShowsItsAction() {
    var status: ComputerStatus by mutableStateOf(ComputerStatus.NotConnected)
    screen.setContent {
      ConnectionStatusCard(status, SyncState.Idle, RequestCounts(0, 0, 0, 0), null, onAction = { asked += it }, extras = ConnectionCardExtras(now = now))
    }
    val cases = listOf(
      ComputerStatus.NotConnected to ConnectionAction.CONNECT,
      ComputerStatus.ConnectAgain to ConnectionAction.CONNECT_AGAIN,
      ComputerStatus.DifferentComputer("Other PC") to ConnectionAction.DETAILS,
      ComputerStatus.ComputerTooOld to ConnectionAction.WHY,
      ComputerStatus.NotReachable("Desk PC", now, addressMayHaveChanged = true, ProbeOutcome.NoRoute) to ConnectionAction.NEW_ADDRESS,
    )
    cases.forEach { (next, action) ->
      status = next
      compose.waitForIdle()
      compose.onNodeWithTag(ConnectionTags.action(action)).performClick()
    }
    assertThat(asked).containsExactly(
      ConnectionAction.CONNECT,
      ConnectionAction.CONNECT_AGAIN,
      ConnectionAction.DETAILS,
      ConnectionAction.WHY,
      ConnectionAction.NEW_ADDRESS,
    ).inOrder()
  }

  @Test
  fun everyStatePassesTheAudit() {
    val connected = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 2, lowOnSpace = true)
    screen.checkEach(
      variants = ScreenVariant.REQUIRED + ScreenVariant(fontScale = 1.3f, talkBackOn = true, reducedMotion = true),
      content = {
        Column {
          ConnectionStatusCard(
            connected,
            SyncState.Idle,
            RequestCounts(waiting = 2, onComputer = 3, needsAttention = 1, readyNew = 0, analyzing = 1, inLine = 2),
            null,
            onAction = {},
            extras = ConnectionCardExtras(now = now, checkedAt = now, attentionAction = RequestAction.TRY_AGAIN, storageNearlyFull = true, backgroundRestricted = true),
          )
          ConnectionStatusCard(
            connected,
            SyncState.Active(bringing = 2, percent = null, sending = 0, doneBytes = 1_000_000, totalBytes = null),
            RequestCounts(0, 0, 0, 0),
            null,
            onAction = {},
            extras = ConnectionCardExtras(now = now, clipsOnPhone = 1, clipsTotal = 3),
          )
          ConnectionStatusCard(
            ComputerStatus.NotReachable("Desk PC", now.minusSeconds(60), true, ProbeOutcome.PhoneAccessOff),
            SyncState.Idle,
            RequestCounts(waiting = 1, onComputer = 0, needsAttention = 0, readyNew = 0),
            null,
            onAction = {},
          )
        }
      },
    ) {
      compose.onAllNodesWithTag(ConnectionTags.STATUS).fetchSemanticsNodes().let { assertThat(it).hasSize(3) }
    }
  }
}
