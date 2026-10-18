package com.mapineda48.phonotrainer.e2e.connect

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.di.FakeDiscovery
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.support.TestTime
import com.mapineda48.phonotrainer.feature.connect.ConnectTags
import com.mapineda48.phonotrainer.feature.home.HomeTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The Welcome, Connect and Connection details on the managed device (work-plan WP-13 Proof, section 6 "GMD area
 * tests"), on the global test set with the real repositories of this baseline (WP-07's connectivity, WP-05b's
 * network client) and the global set's fake computer: the first launch from the launcher shows the Welcome with no
 * permission dialog, "Not now" lands on Home's first run and the Welcome never returns (F-01-AC1, AC2); the computer
 * on this Wi-Fi is found without typing (F-02-AC1) and pairs with its code (F-02-AC2); a wrong code stores nothing
 * (F-02-AC3); a typed address pairs when discovery finds nothing (F-02-AC4); and Connection details shows this
 * phone's address, copies diagnostics without any address or id (F-22-AC4) and says phone access is off in D-42's
 * words (F-02-AC12).
 */
@HiltAndroidTest
class ConnectAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var status: ComputerStatusRepository

  @Inject lateinit var computer: FakeComputer

  @Inject lateinit var discovery: FakeDiscovery

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var time: TestTime

  private val phono = PhonoRobot(compose)
  private val robot = ConnectRobot(compose, phono)

  private fun launch(welcomeDone: Boolean) {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = welcomeDone) } }
    phono.launch()
  }

  /** Home's status card says [words] (design 9.17: the card's one sentence). */
  private fun waitForStatus(words: String) {
    compose.waitUntil(ConnectRobot.PAIR_TIMEOUT_MS) { runCatching { robot.text(ConnectionTags.STATUS).contains(words) }.getOrDefault(false) }
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun theFirstLaunchWelcomesThenNotNowLandsHomeAndTheWelcomeNeverReturns() {
    launch(welcomeDone = false)
    // F-01-AC1: Welcome page 1, "Skip" reachable, no runtime permission dialog before Connect.
    phono.assertShown(WelcomeKey())
    robot.waitForWelcome()
    assertThat(robot.welcomeTitle()).contains("1 OF 3")
    assertThat(robot.welcomeTitle()).contains("Hear English as it's really spoken")
    phono.waitForTag(com.mapineda48.phonotrainer.feature.onboarding.OnboardingTags.SKIP)
    assertThat(robot.permissionDialogShown()).isFalse()

    robot.next()
    assertThat(robot.welcomeTitle()).contains("Your clips become shorts")
    robot.next()
    assertThat(robot.welcomeTitle()).contains("A few minutes a day")
    assertThat(robot.permissionDialogShown()).isFalse()
    robot.getStarted()

    phono.assertShown(ConnectKey(ConnectMode.FIRST_RUN))
    phono.assertNotShown(WelcomeKey())
    robot.waitForConnect()
    assertThat(robot.permissionDialogShown()).isFalse()

    // F-01-AC2: "Not now" shows Home's first run, and the next launch does not show the Welcome.
    robot.notNow()
    phono.assertShown(HomeKey)
    waitForStatus("Not connected to a computer")
    assertThat(settings.settings.value.welcomeDone).isTrue()

    phono.scenario.close()
    phono.launch()
    phono.assertShown(HomeKey)
    phono.assertNotShown(WelcomeKey())
  }

  @Test
  fun skipOnTheFirstPageLeadsToConnectAndBackFromThereIsHome() {
    launch(welcomeDone = false)
    phono.assertShown(WelcomeKey())
    robot.skip()
    phono.assertShown(ConnectKey(ConnectMode.FIRST_RUN))
    assertThat(settings.settings.value.welcomeDone).isTrue()
    phono.back()
    phono.assertShown(HomeKey)
    phono.assertNotShown(WelcomeKey())
  }

  @Test
  fun theComputerOnThisWifiIsFoundWithoutTypingAndPairsWithItsCode() {
    launch(welcomeDone = true)
    phono.assertShown(HomeKey)
    phono.waitForTag(HomeTags.TEACH_CONNECT)
    compose.onNodeWithTag(HomeTags.TEACH_CONNECT).performScrollTo().performClick()
    phono.assertShown(ConnectKey(ConnectMode.FROM_HOME))

    // F-02-AC1: listed without typing an address, and preselected as the only one.
    val code = computer.newPairingCode()
    robot.waitForRow(computer.serverId, computer.address)
    val row = robot.rowTag(computer.serverId, computer.address)
    assertThat(robot.text(row)).contains(computer.name)
    assertThat(robot.isSelected(row)).isTrue()

    // F-02-AC2: the code shown on the computer connects, and a sync starts (the status machine reports Connected).
    robot.enterCodeAndConnect(code)
    robot.waitForSuccess()
    assertThat(robot.text(ConnectTags.SUCCESS)).contains("Connected to ${computer.name}")
    assertThat(status.paired.value?.serverId).isEqualTo(computer.serverId)
    assertThat(computer.pairedDevices).hasSize(1)

    robot.tapIn(ConnectTags.SUCCESS_ACTION)
    phono.assertShown(HomeKey)
    phono.assertNotShown(ConnectKey())
    waitForStatus("Connected to ${computer.name}")
  }

  @Test
  fun aWrongCodeSaysSoAndStoresNothing() {
    launch(welcomeDone = true)
    phono.open(ConnectKey(ConnectMode.FROM_HOME))
    val code = computer.newPairingCode()
    val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
    robot.waitForRow(computer.serverId, computer.address)

    robot.enterCodeAndConnect(wrong)
    robot.waitForWords(ConnectTags.CODE_ERROR, "That code didn't match. Check the code on your computer.")
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { robot.codeFocused() }
    // F-02-AC3: no credential stored, nothing paired on the computer.
    assertThat(status.paired.value).isNull()
    assertThat(computer.pairedDevices).isEmpty()
  }

  @Test
  fun aTypedAddressPairsWhenDiscoveryFindsNothing() {
    launch(welcomeDone = true)
    // F-02-AC4: the computer does not answer probes; its address and port are typed as it prints them.
    discovery.computerAnswers = false
    phono.open(ConnectKey(ConnectMode.FROM_HOME))
    robot.waitForWords(ConnectTags.NOTHING_FOUND, "No computer answered.", timeoutMs = ConnectRobot.SEARCH_TIMEOUT_MS)

    robot.checkAddress(computer.address)
    robot.waitForWords(ConnectTags.ADDRESS_RESULT, "Found ${computer.name}")
    robot.enterCodeAndConnect(computer.newPairingCode())
    robot.waitForSuccess()
    assertThat(status.paired.value?.address).isEqualTo(computer.address)
  }

  @Test
  fun connectionDetailsShowsThisPhonesAddressAndCopiesNoneOfIt() {
    launch(welcomeDone = true)
    runBlocking { seeder.pair() }
    phono.open(ConnectionDetailsKey)
    phono.assertShown(ConnectionDetailsKey)

    // F-22-AC4: this phone's address on this Wi-Fi (the global set's scripted Wi-Fi), on screen.
    robot.scrollTo(ConnectTags.DETAILS_PHONE_ADDRESS, ConnectTags.DETAILS)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { robot.spoken(ConnectTags.DETAILS_PHONE_ADDRESS) == "This phone on this Wi-Fi: $PHONE_ADDRESS" }

    // "Copy diagnostics": no address of the phone or the computer, no server or device id, no name.
    robot.tapIn(ConnectTags.DETAILS_COPY, ConnectTags.DETAILS)
    val copied = robot.clipboardText()
    assertThat(copied).contains("PhonoTrainer connection diagnostics")
    assertThat(copied).contains("Computer's address: loopback")
    listOf(PHONE_ADDRESS, computer.address.host, ":${computer.address.port}", computer.serverId.value.take(8), computer.name)
      .forEach { assertThat(copied).doesNotContain(it) }
    status.paired.value?.deviceId?.let { assertThat(copied).doesNotContain(it.value) }
  }

  @Test
  fun phoneAccessOffOnThisNetworkIsSaidInTheLastProbesWords() {
    launch(welcomeDone = true)
    runBlocking { seeder.pair() }
    phono.open(ConnectionDetailsKey)
    phono.assertShown(ConnectionDetailsKey)

    // F-02-AC12: phone access on but this network not approved; two failed probes, 5 s apart, give "not reachable".
    computer.lanForbidden()
    robot.tapIn(ConnectTags.DETAILS_CHECK_NOW, ConnectTags.DETAILS)
    time.advanceBy(Duration.ofSeconds(6))
    robot.tapIn(ConnectTags.DETAILS_CHECK_NOW, ConnectTags.DETAILS)
    robot.waitForWords(ConnectTags.DETAILS_LAST_PROBE, D42, ConnectTags.DETAILS)
    robot.waitForWords(ConnectionTags.STATUS, "Your computer isn't reachable", ConnectTags.DETAILS)
  }

  private companion object {
    /** The address of the global set's scripted Wi-Fi (`FakeNetworkMonitor.wifi()`, a documentation address). */
    const val PHONE_ADDRESS = "192.0.2.20"

    const val D42 = "Your computer answered, but phone access is off there or not approved for this network."
  }
}
