package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connect to your computer (spec F-02 States, every row; F-01 behaviour 2 for the first run; design 10.2 P-06): each
 * state's words, what can be picked, the D-39 note with no preselection (F-02-AC10), the D-42 copy for a typed address
 * without phone access, the code's errors with the focus kept (F-02-AC3), "Retry-After", success, "Connect again" for
 * the same computer only, the new-address mode, the code's keyboard (L-8), and the accessibility audit at font scale
 * 2.0, light and dark, on each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class ConnectScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()
  private val actions = ConnectActions(
    onBack = { calls += "back" },
    onSearchAgain = { calls += "searchAgain" },
    onOpenWifi = { calls += "openWifi" },
    onEnterAddress = { calls += "enterAddress" },
    onAddressChange = { calls += "address:$it" },
    onCheck = { calls += "check" },
    onSelect = { calls += "select:$it" },
    onCodeChange = { calls += "code:$it" },
    onNameChange = { calls += "name:$it" },
    onConnect = { calls += "connect" },
    onNotNow = { calls += "notNow" },
    onGoHome = { calls += "home" },
    onDone = { calls += "done" },
    onCopy = { calls += "copy:$it" },
  )

  private fun check(state: ConnectUiState, assertions: () -> Unit) =
    screen.checkEach(ScreenVariant.REQUIRED, { ConnectScreen(state, actions) }) { assertions() }

  private fun has(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun theFirstRunSearchesSaysWhatToDoOnTheComputerAndOffersNotNow() = check(ConnectSamples.firstRun) {
    assertThat(compose.textsOf(ConnectTags.PAGE)).contains("Your computer analyzes the clips; this phone keeps them and teaches with them.")
    assertThat(compose.textsOf(ConnectTags.LOOKING)).contains("Looking for your computer…")
    val steps = compose.textsOf(ConnectTags.STEPS)
    assertThat(steps).contains("On your computer, start PhonoTrainer with phone access on.")
    assertThat(steps).contains("phonotrainer ui --phone-access")
    assertThat(steps).contains("It shows a 6-digit code and its address.")
    assertThat(steps).contains("phonotrainer phone pair")
    assertThat(steps).contains("Pick your computer below and enter the code.")
    assertThat(compose.textsOf(ConnectTags.NOT_NOW)).contains("Not now")
    assertThat(compose.textsOf(ConnectTags.PAGE)).contains("You can connect later from Home.")
    assertThat(has(ConnectTags.CODE_SECTION)).isFalse()
  }

  @Test
  fun fromHomeThereIsNoNotNow() = check(ConnectSamples.searching) {
    assertThat(has(ConnectTags.NOT_NOW)).isFalse()
    assertThat(has(ConnectTags.ENTER_ADDRESS)).isTrue()
  }

  @Test
  fun aFoundComputerIsListedWithoutItsAddressAndPreselected() = check(ConnectSamples.found) {
    val desk = ComputerRow.keyOf(ConnectSamples.desk)
    compose.onNodeWithTag(ConnectTags.row(desk)).assertIsSelected()
    val row = compose.textsOf(ConnectTags.row(desk))
    assertThat(row).contains("Study desk")
    assertThat(row).contains("PhonoTrainer, phone API 6")
    assertThat(row).contains("Ready to connect")
    // Spec F-02 behaviour 3: no address in the list.
    assertThat(compose.textsOf(ConnectTags.PAGE)).doesNotContain("192.0.2.")
    // A too-old computer says so and cannot be picked.
    val old = ComputerRow.keyOf(ConnectSamples.old)
    assertThat(compose.textsOf(ConnectTags.row(old))).contains("Needs a newer PhonoTrainer on your computer")
    assertThat(compose.onNodeWithTag(ConnectTags.row(old)).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)).isNull()
    // The code section for the picked computer.
    assertThat(compose.textsOf(ConnectTags.CODE_SECTION)).contains("Enter the code shown on your computer")
    assertThat(compose.textsOf(ConnectTags.CODE_SECTION)).contains("Connecting to Study desk")
    assertThat(compose.textsOf(ConnectTags.CODE_SECTION)).contains("Name of this phone on your computer")
    compose.onNodeWithTag(ConnectTags.CONNECT).assertIsNotEnabled()
  }

  @Test
  fun twoComputersWithOneNameAreBothListedNonePickedUnderTheNote() = check(ConnectSamples.conflict) {
    // F-02-AC10: the note above the list, every answer a row, none preselected, no code yet.
    assertThat(compose.textsOf(ConnectTags.CONFLICT)).contains(
      "Two computers answered with the same name. If you aren't sure which one is yours, enter the address shown on " +
        "your computer instead.",
    )
    assertThat(compose.textsOf(ConnectTags.CONFLICT)).contains("Enter the address instead")
    assertThat(compose.onAllNodes(hasTestTag(ConnectTags.ROW), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2)
    compose.onNodeWithTag(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.desk))).assertIsNotSelected()
    compose.onNodeWithTag(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.deskTwin))).assertIsNotSelected()
    assertThat(has(ConnectTags.CODE_SECTION)).isFalse()
    val noteTop = compose.onNodeWithTag(ConnectTags.CONFLICT).fetchSemanticsNode().boundsInRoot.top
    val rowTop = compose.onNodeWithTag(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.desk))).fetchSemanticsNode().boundsInRoot.top
    assertThat(noteTop).isLessThan(rowTop)
  }

  @Test
  fun oneIdentityFromTwoAddressesIsTwoRowsToo() = check(ConnectSamples.sameIdTwice) {
    assertThat(has(ConnectTags.CONFLICT)).isTrue()
    assertThat(compose.onAllNodes(hasTestTag(ConnectTags.ROW), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2)
    assertThat(compose.textsOf(ConnectTags.LOOKING)).contains("Looking for your computer…")
  }

  @Test
  fun nothingFoundShowsTheChecklistAndTwoWaysOn() = check(ConnectSamples.nothingFound) {
    val block = compose.textsOf(ConnectTags.NOTHING_FOUND)
    assertThat(block).contains("No computer answered.")
    assertThat(block).contains("This phone looked on your Wi-Fi for 10 seconds and heard nothing back.")
    val list = compose.textsOf(ConnectTags.CHECKLIST)
    // D-42: the first item names `phonotrainer phone on`.
    assertThat(list).contains(
      "Is PhonoTrainer running with phone access on? If your computer joined a new network, run phonotrainer phone on there.",
    )
    assertThat(list).contains("Are this phone and your computer on the same Wi-Fi?")
    assertThat(list).contains("Some routers keep devices apart (guest networks).")
    assertThat(list).contains("Is a VPN on? Turn it off at home: it can hide your computer.")
    assertThat(block).contains("Search again")
    assertThat(block).contains("Enter the address instead")
  }

  @Test
  fun offWifiSaysConnectToWifiFirst() = check(ConnectSamples.notOnWifi) {
    val notice = compose.textsOf(ConnectTags.NOT_ON_WIFI)
    assertThat(notice).contains("Connect to your home Wi-Fi first. Your computer is reachable only there.")
    assertThat(notice).contains("Open Wi-Fi settings")
  }

  @Test
  fun aTypedAddressWithoutPhoneAccessSaysHowToTurnItOn() = check(ConnectSamples.phoneAccessOff) {
    assertThat(compose.textsOf(ConnectTags.ADDRESS_RESULT)).contains(
      "That address answered, but phone access isn't on there for this network. On your computer, run phonotrainer " +
        "phone on, then try again.",
    )
    assertThat(has(ConnectTags.CODE_SECTION)).isFalse()
  }

  @Test
  fun aTypedAddressOfAnOlderComputerSaysItNeedsANewerOne() = check(ConnectSamples.tooOld) {
    assertThat(compose.textsOf(ConnectTags.ADDRESS_RESULT)).contains("Needs a newer PhonoTrainer on your computer")
    assertThat(has(ConnectTags.CODE_SECTION)).isFalse()
  }

  @Test
  fun aTypedAddressThatAnsweredIsListedPickedAndReadyForTheCode() = check(ConnectSamples.typed) {
    assertThat(compose.textsOf(ConnectTags.ADDRESS_RESULT)).contains("Found Study desk. Enter the code shown on your computer.")
    compose.onNodeWithTag(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.desk))).assertIsSelected()
    assertThat(compose.textsOf(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.desk)))).contains("At the address you entered")
    assertThat(has(ConnectTags.CODE_SECTION)).isTrue()
  }

  @Test
  fun theAddressFieldNeverSuggestsAnExampleAddress() {
    screen.setContent { ConnectScreen(ConnectSamples.newAddress, actions) }
    assertThat(compose.textsOf(ConnectTags.ADDRESS)).contains("As shown on your computer")
    assertThat(compose.textsOf(ConnectTags.PAGE)).doesNotContain("192.")
  }

  @Test
  fun aWrongCodeSaysSoAndKeepsTheFocusInTheCells() {
    var state by mutableStateOf(ConnectSamples.found.copy(code = "111111"))
    screen.setContent { ConnectScreen(state, actions) }
    state = ConnectSamples.codeError(CodeError.Invalid)
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.CODE).assertIsFocused()
    assertThat(compose.textsOf(ConnectTags.CODE_ERROR)).contains("That code didn't match. Check the code on your computer.")
    // The next error puts the focus back again, even after it moved away.
    state = ConnectSamples.codeError(CodeError.Expired).copy(codeErrorSerial = 2)
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.CODE).assertIsFocused()
    assertThat(compose.textsOf(ConnectTags.CODE_ERROR)).contains("That code has expired. Your computer can show a new one.")
    state = ConnectSamples.codeError(CodeError.Locked).copy(codeErrorSerial = 3)
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.CODE).assertIsFocused()
    assertThat(compose.textsOf(ConnectTags.CODE_ERROR)).contains("Too many tries. Ask your computer for a new code.")
  }

  @Test
  fun everyCodeErrorReadsAtEveryVariant() {
    var state by mutableStateOf(ConnectSamples.codeError(CodeError.Invalid))
    screen.checkEach(ScreenVariant.REQUIRED, { ConnectScreen(state, actions) }) {
      val expected = mapOf(
        CodeError.Invalid to "That code didn't match. Check the code on your computer.",
        CodeError.Expired to "That code has expired. Your computer can show a new one.",
        CodeError.Locked to "Too many tries. Ask your computer for a new code.",
        CodeError.RateLimited(30) to "Too many tries right now. Try again in 30 seconds.",
        CodeError.RateLimited(1) to "Too many tries right now. Try again in 1 second.",
        CodeError.RateLimited(null) to "Too many tries right now. Wait a moment, then try again.",
        CodeError.AppTooOld to "Your computer needs a newer version of this app. Update this app, then connect again.",
        CodeError.PhoneAccessOff to "Your computer answered, but phone access isn't on there for this network. On your " +
          "computer, run phonotrainer phone on, then try again.",
        CodeError.OtherComputer("Study desk") to "This phone is connected to Study desk. Forget it first in Settings to " +
          "connect to another computer.",
        CodeError.DifferentComputer to "A different computer answered at this address.",
        CodeError.NotReachable to "Your computer didn't answer.",
      )
      expected.forEach { (error, words) ->
        state = ConnectSamples.codeError(error)
        compose.waitForIdle()
        assertThat(compose.textsOf(ConnectTags.CODE_ERROR)).contains(words)
        screen.audit()
      }
    }
  }

  @Test
  fun successSaysConnectedAndOffersHome() = check(ConnectSamples.paired) {
    val panel = compose.textsOf(ConnectTags.SUCCESS)
    assertThat(panel).contains("Connected to Study desk")
    assertThat(panel).contains("Clips on your computer start coming to this phone now. Requests waiting here are being sent.")
    assertThat(compose.textsOf(ConnectTags.SUCCESS_ACTION)).contains("Go to Home")
    assertThat(has(ConnectTags.STEPS)).isFalse()
  }

  @Test
  fun connectAgainOffersTheSameComputerOnly() = check(ConnectSamples.connectAgain) {
    assertThat(compose.textsOf(ConnectTags.PAGE)).contains("Your computer no longer recognizes this phone.")
    val desk = ComputerRow.keyOf(ConnectSamples.desk)
    compose.onNodeWithTag(ConnectTags.row(desk)).assertIsSelected()
    assertThat(compose.textsOf(ConnectTags.row(desk))).contains("The computer this phone is connected to")
    val other = ComputerRow.keyOf(ConnectSamples.other)
    assertThat(compose.textsOf(ConnectTags.row(other))).contains(
      "This phone is connected to Study desk. Forget it first in Settings to connect to another computer.",
    )
    assertThat(compose.onNodeWithTag(ConnectTags.row(other)).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)).isNull()
  }

  @Test
  fun theNewAddressModeOpensOnTheFieldAndEndsWithDone() {
    var state by mutableStateOf(ConnectSamples.newAddress)
    screen.checkEach(ScreenVariant.REQUIRED, { ConnectScreen(state, actions) }) {
      state = ConnectSamples.newAddress
      compose.waitForIdle()
      assertThat(compose.onAllNodes(hasText("Enter the new address")).fetchSemanticsNodes()).isNotEmpty()
      assertThat(compose.textsOf(ConnectTags.PAGE)).contains("proves it is Study desk")
      assertThat(has(ConnectTags.ADDRESS)).isTrue()
      assertThat(has(ConnectTags.STEPS)).isFalse()
      assertThat(has(ConnectTags.LOOKING)).isFalse()

      state = ConnectSamples.newAddress.copy(addressCheck = AddressCheck.Answered(com.mapineda48.phonotrainer.core.domain.model.CheckResult.DifferentComputer))
      compose.waitForIdle()
      assertThat(compose.textsOf(ConnectTags.ADDRESS_RESULT)).contains("A different computer answered at this address.")
      screen.audit()

      state = ConnectSamples.newAddressDone
      compose.waitForIdle()
      assertThat(compose.textsOf(ConnectTags.SUCCESS)).contains("This phone uses the new address from now on.")
      assertThat(compose.textsOf(ConnectTags.SUCCESS_ACTION)).contains("Done")
    }
  }

  @Test
  fun everyControlCallsItsAction() {
    var state by mutableStateOf(ConnectSamples.conflict)
    screen.setContent { ConnectScreen(state, actions) }
    compose.onNodeWithTag(ConnectTags.CONFLICT_ADDRESS).performClick()
    compose.onNodeWithTag(ConnectTags.row(ComputerRow.keyOf(ConnectSamples.deskTwin))).performClick()
    compose.onNodeWithTag(ConnectTags.COPY_PAIR).performClick()
    compose.onNodeWithTag(ConnectTags.SEARCH_AGAIN).performClick()
    state = ConnectSamples.found.copy(code = "48291")
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.CODE).performTextReplacement("482913")
    state = ConnectSamples.found.copy(code = "482913")
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.CONNECT).performClick()
    state = ConnectSamples.notOnWifi
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.OPEN_WIFI).performClick()
    state = ConnectSamples.firstRun
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.ENTER_ADDRESS).performClick()
    compose.onNodeWithTag(ConnectTags.NOT_NOW).performClick()
    state = ConnectSamples.paired
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectTags.SUCCESS_ACTION).performClick()
    assertThat(calls).containsExactly(
      "enterAddress",
      "select:${ComputerRow.keyOf(ConnectSamples.deskTwin)}",
      "copy:phonotrainer phone pair",
      "searchAgain",
      "code:482913",
      "connect",
      "openWifi",
      "enterAddress",
      "notNow",
      "home",
    ).inOrder()
  }

  @Test
  fun theCodeFieldKeepsTheCodeOutOfKeyboardHistory() {
    // Security review L-8: a NumberPassword keyboard without autocorrect; and no autofill content type on the field.
    assertThat(CODE_KEYBOARD.keyboardType).isEqualTo(KeyboardType.NumberPassword)
    assertThat(CODE_KEYBOARD.autoCorrectEnabled).isFalse()
    screen.setContent { ConnectScreen(ConnectSamples.found, actions) }
    val field = compose.onNodeWithTag(ConnectTags.CODE).fetchSemanticsNode()
    // Autofill services see only nodes whose data type is not None (Compose's AndroidAutofillManager).
    assertThat(field.config.getOrNull(SemanticsProperties.ContentDataType)).isEqualTo(ContentDataType.None)
    assertThat(field.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly("Pairing code, 6 digits")
    compose.onNodeWithTag(ConnectTags.CODE).assertIsDisplayed()
  }
}
