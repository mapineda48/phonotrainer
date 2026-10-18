package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > Your computer (spec F-22 "Your computer", D-60, F-22-AC3; design 9.17, 9.23, render `31` specimen 4):
 * the status card and the facts, the four ways on, "Forget this computer…" and its confirmation (title, F-22's body,
 * "Cancel" first and focused, "Forget this computer"), and the not-connected, forgotten and refused states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class YourComputerScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = YourComputerActions(
    onBack = { calls += "back" },
    onCard = { calls += "card:$it" },
    onCheckNow = { calls += "check" },
    onDetails = { calls += "details" },
    onNewAddress = { calls += "newAddress" },
    onConnectAgain = { calls += "connectAgain" },
    onForget = { calls += "forget" },
    onConnect = { calls += "connect" },
  )

  @Test
  fun theConnectedComputerShowsItsStatusItsFactsAndTheWaysOn() {
    screen.checkEach(ScreenVariant.REQUIRED, { YourComputerScreen(SettingsSamples.connected, actions) }) {
      assertThat(compose.textsOf(ConnectionTags.STATUS)).contains("Study desk")
      val facts = compose.textsOf(SettingsTags.COMPUTER_FACTS)
      assertThat(facts).contains("Computer Study desk")
      assertThat(facts).contains("Last seen 2 min ago")
      assertThat(facts).contains("Last synced 10 min ago")
      assertThat(facts).contains("This phone's name Galaxy phone")
      assertThat(compose.textsOf(SettingsTags.COMPUTER_CHECK)).contains("Ask your computer again whether it is there.")
      assertThat(compose.textsOf(SettingsTags.COMPUTER_DETAILS)).contains("Connection details")
      assertThat(compose.textsOf(SettingsTags.COMPUTER_NEW_ADDRESS)).contains("Enter the new address")
      assertThat(compose.textsOf(SettingsTags.COMPUTER_CONNECT_AGAIN)).contains("Connect again")
      assertThat(compose.textsOf(SettingsTags.COMPUTER_FORGET)).contains("Forget this computer…")
    }
  }

  @Test
  fun eachWayOnCallsItsAction() {
    screen.setContent { YourComputerScreen(SettingsSamples.connected, actions) }
    compose.onNodeWithTag(SettingsTags.COMPUTER_CHECK).performClick()
    compose.onNodeWithTag(SettingsTags.COMPUTER_DETAILS).performClick()
    compose.onNodeWithTag(SettingsTags.COMPUTER_NEW_ADDRESS).performClick()
    compose.onNodeWithTag(SettingsTags.COMPUTER_CONNECT_AGAIN).performClick()
    assertThat(calls).containsExactly("check", "details", "newAddress", "connectAgain").inOrder()
  }

  @Test
  fun forgetAsksFirstWithTheWordsOfD60() {
    screen.setContent { YourComputerScreen(SettingsSamples.connected, actions) }
    compose.onNodeWithTag(SettingsTags.COMPUTER_FORGET).performClick()

    compose.onNode(isDialog()).assertIsDisplayed()
    assertThat(compose.textsOf(SettingsTags.FORGET_TITLE)).isEqualTo("Forget this computer?")
    assertThat(compose.textsOf(SettingsTags.FORGET_BODY)).isEqualTo(
      "This phone stops talking to Study desk. Your clips stay on this phone; requests waiting here stay too. " +
        "Requests Study desk already accepted will no longer be followed here.",
    )
    compose.onNode(hasTestTag(SettingsTags.FORGET_CANCEL) and hasText("Cancel")).assertIsFocused()
    compose.onNode(hasTestTag(SettingsTags.FORGET_CONFIRM) and hasText("Forget this computer")).assertIsNotFocused()
    val pane = compose.onNodeWithTag(SettingsTags.FORGET_DIALOG).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
    assertThat(pane).isEqualTo("Forget this computer?")
    // v1-core has no consequence line (design 9.23 rule (3) is v1-complete).
    assertThat(compose.textsOf(SettingsTags.FORGET_DIALOG)).doesNotContain("won't be deleted")
    ScreenVariant.REQUIRED.forEach {
      screen.show(it)
      screen.audit()
    }

    // "Cancel" forgets nothing.
    compose.onNodeWithTag(SettingsTags.FORGET_CANCEL).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(calls).isEmpty()

    // Confirmed: the dialog closes and the page forgets.
    compose.onNodeWithTag(SettingsTags.COMPUTER_FORGET).performClick()
    compose.onNodeWithTag(SettingsTags.FORGET_CONFIRM).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(calls).containsExactly("forget")
  }

  @Test
  fun theStatusCardsOwnControlsAreReported() {
    screen.setContent { YourComputerScreen(SettingsSamples.unreachable, actions) }
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.WHY)).performClick()
    assertThat(calls).containsExactly("card:${ConnectionAction.WHY}")
  }

  @Test
  fun aCheckInProgressAndARefusedForgetAreSaidInWords() {
    screen.checkEach(ScreenVariant.REQUIRED, { YourComputerScreen(SettingsSamples.unreachable, actions) }) {
      assertThat(compose.textsOf(SettingsTags.COMPUTER_CHECK)).contains("Checking your computer…")
      compose.onNodeWithTag(SettingsTags.COMPUTER_CHECK).assertIsNotEnabled()
      assertThat(compose.textsOf(SettingsTags.COMPUTER_FORGET_FAILED)).isEqualTo("This phone couldn't forget Study desk. Try again.")
    }
  }

  @Test
  fun withoutAComputerThePageOffersToConnect() {
    screen.checkEach(ScreenVariant.REQUIRED, { YourComputerScreen(SettingsSamples.notConnected, actions) }) {
      val none = compose.textsOf(SettingsTags.COMPUTER_NONE)
      assertThat(none).contains("No computer connected")
      assertThat(none).contains("Connect this phone to the computer that runs PhonoTrainer, and your clips come here on their own.")
      compose.onNodeWithTag(SettingsTags.COMPUTER_FORGET).assertDoesNotExist()
      compose.onNodeWithTag(SettingsTags.COMPUTER_FORGOTTEN).assertDoesNotExist()
    }
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.0").performClick()
    assertThat(calls).containsExactly("connect")
  }

  @Test
  fun afterForgettingThePageSaysWhatStays() {
    screen.checkEach(ScreenVariant.REQUIRED, { YourComputerScreen(SettingsSamples.forgotten, actions) }) {
      assertThat(compose.textsOf(SettingsTags.COMPUTER_FORGOTTEN))
        .isEqualTo("This phone forgot Study desk. Your clips and waiting requests stay here.")
      compose.onNodeWithTag(SettingsTags.COMPUTER_NONE).assertExists()
    }
  }
}
