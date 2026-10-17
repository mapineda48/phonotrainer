package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bridge and the connection line of the share sheet and "Add a clip" (spec 8.4 item 3, design 9.22). */
@RunWith(RobolectricTestRunner::class)
class ShareBridgeTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
  private val unreachable = ComputerStatus.NotReachable("Studio PC", null, false, ProbeOutcome.NoAnswer(3))

  /** Every `ComputerStatus` of spec 7.5, one per row. */
  private val every: List<ComputerStatus> = listOf(
    ComputerStatus.NotConnected,
    ComputerStatus.Offline,
    ComputerStatus.NotOnWifi,
    ComputerStatus.Looking,
    ComputerStatus.Checking,
    ComputerStatus.Connected("Studio PC", analyzing = 0, inLine = 0, lowOnSpace = false),
    unreachable,
    ComputerStatus.DifferentComputer("Other PC"),
    ComputerStatus.ConnectAgain,
    ComputerStatus.ComputerTooOld,
    ComputerStatus.AppTooOld,
  )

  @Test
  fun everyStatusHasItsLookWordsAndIcon() {
    val table = every.map { status ->
      Triple(ShareBridgeState.lookOf(status), ConnectionLineCopy.of(status, checking = false, resources).spoken, ConnectionLineCopy.iconOf(status, false))
    }

    val unreachableWords = "Your computer isn't reachable right now. This waits on your phone and is sent when you're back " +
      "on your home Wi-Fi."
    assertThat(table).containsExactly(
      Triple(BridgeLook.NOT_PAIRED, "This phone isn't connected to your computer yet. It waits on your phone until you connect.", PhonoIcons.ConnNotConnected),
      Triple(BridgeLook.UNREACHABLE, unreachableWords, PhonoIcons.StatusWaiting),
      Triple(BridgeLook.UNREACHABLE, unreachableWords, PhonoIcons.StatusWaiting),
      Triple(BridgeLook.CHECKING, "Checking your computer…", PhonoIcons.ConnChecking),
      Triple(BridgeLook.CHECKING, "Checking your computer…", PhonoIcons.ConnChecking),
      Triple(BridgeLook.CONNECTED, "Your computer is connected: the analysis starts now.", PhonoIcons.ConnConnected),
      Triple(BridgeLook.UNREACHABLE, unreachableWords, PhonoIcons.StatusWaiting),
      Triple(
        BridgeLook.ATTENTION,
        "Your computer needs attention (a different computer answered). This waits on your phone.",
        PhonoIcons.StatusAttention,
      ),
      Triple(
        BridgeLook.ATTENTION,
        "Your computer needs attention (it no longer recognizes this phone). This waits on your phone.",
        PhonoIcons.StatusAttention,
      ),
      Triple(
        BridgeLook.ATTENTION,
        "Your computer needs attention (update PhonoTrainer on your computer). This waits on your phone.",
        PhonoIcons.StatusAttention,
      ),
      Triple(BridgeLook.ATTENTION, "Your computer needs attention (update this app). This waits on your phone.", PhonoIcons.StatusAttention),
    ).inOrder()
  }

  @Test
  fun aBusyComputerSaysHowManyClipsGoFirst() {
    fun busy(analyzing: Int, inLine: Int) =
      ConnectionLineCopy.of(ComputerStatus.Connected("Studio PC", analyzing, inLine, false), false, resources).body

    assertThat(busy(1, 1)).isEqualTo("It goes in line after 2 other clips.")
    assertThat(busy(1, 0)).isEqualTo("It goes in line after 1 other clip.")
    assertThat(busy(0, 0)).isNull()
  }

  @Test
  fun theSheetsOwnProbeReadsCheckingWhateverTheLastState() {
    every.forEach { status ->
      assertThat(ShareBridgeState(status, checking = true).look).isEqualTo(BridgeLook.CHECKING)
      assertThat(ConnectionLineCopy.of(status, checking = true, resources).spoken).isEqualTo("Checking your computer…")
    }
  }

  @Test
  fun theBridgeIsAPictureAndTheLineOneNodeAtEveryVariant() {
    var state by mutableStateOf(ShareBridgeState(ComputerStatus.Checking, checking = true))
    val states = every.map { ShareBridgeState(it) } + listOf(
      ShareBridgeState(ComputerStatus.Checking, checking = true),
      ShareBridgeState(ComputerStatus.Offline, phone = BridgePhone.Preparing(0.4f)),
      ShareBridgeState(ComputerStatus.Offline, phone = BridgePhone.Preparing(null)),
      ShareBridgeState(unreachable, phone = BridgePhone.Alert),
    )
    screen.setContent(ScreenVariant.REQUIRED.first()) {
      Column {
        ShareBridge(state)
        ConnectionLine(state.status, checking = state.checking)
      }
    }
    states.forEach { next ->
      state = next
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        val bridge = screen.compose.onNodeWithTag(ShareBridgeTags.BRIDGE).fetchSemanticsNode()
        assertThat(bridge.children).isEmpty()
        assertThat(bridge.config.getOrNull(SemanticsProperties.ContentDescription)).isNull()
        val line = screen.compose.onNodeWithTag(ShareBridgeTags.LINE).fetchSemanticsNode()
        assertThat(line.config[SemanticsProperties.ContentDescription].single())
          .isEqualTo(ConnectionLineCopy.of(next.status, next.checking, resources).spoken)
        assertThat(line.config.getOrNull(SemanticsProperties.LiveRegion)).isNotNull()
        screen.audit()
      }
    }
  }
}
