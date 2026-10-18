package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connection details (spec 7.5 "Why?", F-22 "Connection details", F-22-AC4, F-02-AC12 UI; design 10.2 P-07): the status
 * card, the last probe in words (D-42's words for phone access off), the checklist, this phone's address on screen, the
 * facts and the decoders, "Copy diagnostics" without any address, id or credential, and the audit at font scale 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class ConnectionDetailsScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()
  private val copied = mutableListOf<String>()
  private val actions = DetailsActions(
    onBack = { calls += "back" },
    onCard = { calls += "card:$it" },
    onCheckNow = { calls += "check" },
    onCopyDiagnostics = { copied += it },
  )

  private fun has(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  private fun check(state: DetailsUiState, assertions: () -> Unit) =
    screen.checkEach(ScreenVariant.REQUIRED, { ConnectionDetailsScreen(state, ConnectSamples.platform, actions) }) { assertions() }

  @Test
  fun aConnectedComputerShowsItsFactsAndThisPhonesAddress() = check(ConnectSamples.detailsConnected) {
    assertThat(compose.textsOf(ConnectionTags.STATUS)).contains("Connected to Study desk")
    // F-22-AC4: "This phone on this Wi-Fi:" with the phone's current address on that network.
    assertThat(compose.spokenOf(ConnectTags.DETAILS_PHONE_ADDRESS)).isEqualTo("This phone on this Wi-Fi: 192.0.2.20")
    assertThat(compose.textsOf(ConnectTags.DETAILS_ADDRESS)).contains("192.0.2.10:8000")
    assertThat(compose.textsOf(ConnectTags.DETAILS_SERVER_ID)).contains("a1b2c3d4")
    assertThat(compose.textsOf(ConnectTags.DETAILS_API)).contains("6")
    val counts = compose.textsOf(ConnectTags.DETAILS_COUNTS)
    assertThat(counts).contains("Waiting on this phone 2")
    assertThat(counts).contains("Requests on your computer 1")
    assertThat(counts).contains("Clips to bring to this phone 3")
    assertThat(compose.textsOf(ConnectTags.DETAILS_BACKGROUND)).contains("Not limited")
    val decoders = compose.textsOf(ConnectTags.DETAILS_DECODERS)
    assertThat(decoders).contains("AV1 up to 3840 × 2160 · hardware")
    assertThat(decoders).contains("H.264 up to 4096 × 2176 · hardware")
    assertThat(decoders).contains("VP9 up to 1920 × 1080 · software")
    assertThat(compose.textsOf(ConnectTags.DETAILS_LAST_PROBE)).contains("10 s ago")
    assertThat(has(ConnectTags.DETAILS_CHECK_NOW)).isTrue()
    assertThat(has(ConnectTags.DETAILS_ADDRESS_CHANGED)).isFalse()
  }

  @Test
  fun phoneAccessOffIsSaidInTheLastProbesWords() = check(ConnectSamples.detailsPhoneAccessOff) {
    // F-02-AC12 (UI): the status reads "Your computer isn't reachable" and the last probe gives D-42's words.
    assertThat(compose.textsOf(ConnectionTags.STATUS)).contains("Your computer isn't reachable")
    assertThat(compose.textsOf(ConnectTags.DETAILS_LAST_PROBE))
      .contains("Your computer answered, but phone access is off there or not approved for this network.")
  }

  @Test
  fun whyListsWhatToCheckNumbered() = check(ConnectSamples.detailsNoAnswer) {
    val why = compose.textsOf(ConnectTags.DETAILS_WHY)
    assertThat(why).contains("Is your computer awake?")
    assertThat(why).contains(
      "Is PhonoTrainer running with phone access on? If your computer joined a new network, run phonotrainer phone on there.",
    )
    assertThat(why).contains("Is this phone on the same Wi-Fi?")
    assertThat(why).contains("Is a VPN on? Turn it off at home: it can hide your computer.")
    assertThat(why).contains("Some routers keep devices apart (guest networks).")
    assertThat(why).contains("5")
    assertThat(compose.textsOf(ConnectTags.DETAILS_LAST_PROBE)).contains("No answer after 3 s")
    assertThat(compose.textsOf(ConnectTags.DETAILS_ADDRESS_CHANGED)).contains("Your computer's address changed 1 day ago.")
    assertThat(compose.textsOf(ConnectTags.DETAILS_BACKGROUND))
      .contains("Android is limiting PhonoTrainer in the background: clips arrive when you open the app.")
  }

  @Test
  fun aPhoneThatNeverPairedSaysWhatItKnows() = check(ConnectSamples.detailsNotConnected) {
    assertThat(compose.textsOf(ConnectionTags.STATUS)).contains("Not connected to a computer")
    assertThat(compose.spokenOf(ConnectTags.DETAILS_PHONE_ADDRESS)).isEqualTo("This phone on this Wi-Fi: Not on Wi-Fi")
    assertThat(compose.textsOf(ConnectTags.DETAILS_LAST_PROBE)).contains("This phone hasn't checked a computer yet.")
    assertThat(compose.textsOf(ConnectTags.DETAILS_ADDRESS)).contains("Not known yet")
    assertThat(has(ConnectTags.DETAILS_CHECK_NOW)).isFalse()
    assertThat(compose.textsOf(ConnectTags.DETAILS_DECODERS)).contains("AV1 up to 3840 × 2160 · hardware")
  }

  @Test
  fun copyDiagnosticsCopiesWordsWithoutAddressIdOrName() {
    screen.setContent { ConnectionDetailsScreen(ConnectSamples.detailsConnected, ConnectSamples.platform, actions) }
    compose.onNodeWithTag(ConnectTags.DETAILS_COPY).performClick()
    val text = copied.single()
    assertThat(text).contains("PhonoTrainer connection diagnostics")
    assertThat(text).contains("Status: connected")
    // F-22-AC4: no address of the phone or the computer, no server or device id, no credential.
    listOf("192.0.2.", "a1b2c3d4", "Study desk", "d1", "8000").forEach { assertThat(text).doesNotContain(it) }
  }

  @Test
  fun afterCopyingThePageSaysWhatTheTextHolds() = check(ConnectSamples.detailsConnected.copy(copied = true)) {
    assertThat(compose.textsOf(ConnectTags.DETAILS_COPIED)).contains("Diagnostics copied. They have no address, no id and no key.")
  }

  @Test
  fun theCardsControlsAndCheckNowCallTheirActions() {
    screen.setContent { ConnectionDetailsScreen(ConnectSamples.detailsNotConnected, ConnectSamples.platform, actions) }
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.CONNECT)).performClick()
    assertThat(calls).containsExactly("card:CONNECT")
  }

  @Test
  fun checkNowAsksTheComputer() {
    screen.setContent { ConnectionDetailsScreen(ConnectSamples.detailsNoAnswer, ConnectSamples.platform, actions) }
    compose.onNodeWithTag(ConnectTags.DETAILS_CHECK_NOW).performClick()
    // "Why?" on the card scrolls this page to the checklist; it never leaves the page.
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.WHY)).performClick()
    compose.waitForIdle()
    assertThat(calls).containsExactly("check")
  }
}
