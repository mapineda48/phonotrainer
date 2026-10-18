package com.mapineda48.phonotrainer.e2e.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.CoachMarkRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey
import com.mapineda48.phonotrainer.core.testing.computer.FixtureClips
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.settings.R
import com.mapineda48.phonotrainer.feature.settings.SettingsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The Settings area on the managed device (work-plan WP-24, 2.4 and section 6 "GMD area tests"), on the global test set
 * with the real repositories: Settings opens from Home and every sub-page from it, Back keeps the spec's order, a choice
 * applies at once and survives a recreate, the states an unpaired phone produces render, and on a computer and a clip
 * brought through the real stack ([TestSeeder]): "Restart the tour" re-arms the Watch coach marks and keeps the computer
 * (F-01-AC5), "Forget this computer" asks with D-60's words and leaves the clips (F-22-AC3), and Storage's "Remove from
 * this phone" asks with F-05's words and sends nothing to the computer (F-05-AC11).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class SettingsAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  /** A stuck test gives the one managed device of this computer back (work-plan 1.2). */
  @get:Rule(order = 2)
  val timeout: Timeout = Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var status: ComputerStatusRepository

  @Inject lateinit var clips: ClipRepository

  @Inject lateinit var coachMarks: CoachMarkRepository

  private val phono = PhonoRobot(compose)
  private val robot = SettingsRobot(compose, phono)

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  /** One clip on this phone through the real stack (pairing, a sync pass, the indexer). */
  private fun seedAClip(title: String): JobId = runBlocking {
    seeder.computer.clips = FixtureClips.synthetic(words = WORDS, phrases = PHRASES)
    seeder.seedLibrary(title, video = false).single()
  }

  private fun launchSettings() {
    phono.launch()
    phono.assertShown(HomeKey)
    robot.openFromHome()
  }

  @Test
  fun everyPageOpensFromSettingsAndBackKeepsTheOrder() {
    launchSettings()
    listOf(
      SettingsTags.LINK_COMPUTER to YourComputerKey,
      SettingsTags.LINK_STORAGE to StorageKey,
      SettingsTags.LINK_NOTIFICATIONS to NotificationSettingsKey,
      SettingsTags.LINK_HELP to HelpKey,
    ).forEach { (row, key) ->
      robot.tapIn(SettingsTags.PAGE, row)
      phono.assertShown(key)
      phono.back()
      phono.assertNotShown(key)
      phono.assertShown(SettingsKey)
    }

    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_ABOUT)
    phono.assertShown(AboutKey)
    robot.tapIn(SettingsTags.ABOUT_LIST, SettingsTags.ABOUT_LICENCES)
    phono.assertShown(LicencesKey)
    phono.back()
    phono.assertShown(AboutKey)
    phono.back()
    phono.assertShown(SettingsKey)
    phono.back()
    phono.assertShown(HomeKey)
    phono.assertNotShown(SettingsKey)
  }

  @Test
  fun aChoiceAppliesAtOnceAndIsRememberedAfterARecreate() {
    launchSettings()
    robot.choose(SettingsTags.PAGE, SettingsTags.APPEARANCE, robot.words(R.string.settings_appearance_dark))
    robot.choose(SettingsTags.PAGE, SettingsTags.CAPTIONS, robot.words(R.string.settings_captions_ipa))
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      settings.settings.value.let { it.appearance == Appearance.DARK && it.captions == CaptionMode.IPA }
    }

    phono.scenario.recreate()
    phono.assertShown(SettingsKey)
    robot.scrollTo(SettingsTags.PAGE, SettingsTags.APPEARANCE)
    assertThat(robot.isSelected(SettingsTags.APPEARANCE, robot.words(R.string.settings_appearance_dark))).isTrue()
    robot.scrollTo(SettingsTags.PAGE, SettingsTags.CAPTIONS)
    assertThat(robot.isSelected(SettingsTags.CAPTIONS, robot.words(R.string.settings_captions_ipa))).isTrue()
  }

  @Test
  fun anUnpairedPhoneShowsTheFirstRunOfEachPage() {
    launchSettings()
    robot.scrollTo(SettingsTags.PAGE, SettingsTags.LINK_COMPUTER)
    assertThat(robot.text(SettingsTags.LINK_COMPUTER)).contains("Not connected to a computer")

    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_COMPUTER)
    robot.waitForTag(SettingsTags.COMPUTER_NONE)
    assertThat(robot.text(SettingsTags.COMPUTER_NONE)).contains(robot.words(R.string.computer_none_title))
    phono.tap("${UiTags.EMPTY_ACTION}.0")
    phono.assertShown(ConnectKey(ConnectMode.FROM_HOME))
    phono.back()
    phono.assertShown(YourComputerKey)
    phono.back()

    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_STORAGE)
    robot.waitForTag(SettingsTags.STORAGE_TOTAL)
    assertThat(robot.text(SettingsTags.STORAGE_TOTAL)).contains("of 2 GB")
    robot.scrollTo(SettingsTags.STORAGE_LIST, SettingsTags.STORAGE_EMPTY)
    assertThat(robot.text(SettingsTags.STORAGE_EMPTY)).contains(robot.words(R.string.storage_empty_title))
    phono.back()

    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_NOTIFICATIONS)
    robot.waitForTag(SettingsTags.NOTIFY_READY)
    assertThat(robot.text(SettingsTags.NOTIFY_READY)).contains(robot.words(R.string.notifications_ready))
    phono.back()

    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_HELP)
    robot.scrollTo(SettingsTags.HELP_LIST, SettingsTags.HELP_HOW)
    assertThat(robot.text(SettingsTags.HELP_HOW)).contains(robot.words(R.string.help_engines))
    robot.tapIn(SettingsTags.HELP_LIST, SettingsTags.HELP_MARKS)
    robot.waitForTag(SettingsTags.HELP_LEGEND)
    phono.back()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !robot.has(SettingsTags.HELP_LEGEND) }
    phono.assertShown(HelpKey)
  }

  @Test
  fun restartTheTourReArmsTheWatchCoachMarksAndKeepsTheComputer() {
    val id = seedAClip("Ordering coffee")
    runBlocking { WATCH_MARKS.forEach { coachMarks.dismiss(it) } }
    assertThat(runBlocking { coachMarks.shouldShow(WATCH_MARKS.first()).first() }).isFalse()

    launchSettings()
    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_HELP)
    robot.tapIn(SettingsTags.HELP_LIST, SettingsTags.HELP_TOUR)
    phono.assertShown(WelcomeKey(restart = true))
    WATCH_MARKS.forEach { assertThat(runBlocking { coachMarks.shouldShow(it).first() }).isTrue() }
    // It never re-pairs: the computer is still this phone's.
    assertThat(status.paired.value?.serverId).isEqualTo(seeder.computer.serverId)

    // F-01-AC5: Watch shows its coach marks again.
    phono.open(WatchKey(FeedStart.InOrder(id, firstSegment = null, fromStart = true)))
    compose.waitUntil(WATCH_TIMEOUT_MS) {
      compose.onAllNodes(hasTestTag(UiTags.COACH_GOT_IT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun forgetThisComputerAsksFirstThenKeepsTheClipsAndHomeSaysNotConnected() {
    val id = seedAClip("At the station")
    val name = seeder.computer.name
    launchSettings()
    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_COMPUTER)
    phono.assertShown(YourComputerKey)
    robot.waitForTag(SettingsTags.COMPUTER_FACTS)
    assertThat(robot.text(SettingsTags.COMPUTER_FACTS)).contains(name)

    // D-60: the title, F-22's body with the computer's name, and "Cancel" focused for a keyboard user; Cancel forgets
    // nothing.
    robot.leaveTouchMode()
    robot.openWithoutTouch(SettingsTags.COMPUTER_LIST, SettingsTags.COMPUTER_FORGET)
    robot.assertDialog(
      SettingsTags.FORGET_TITLE,
      robot.words(R.string.computer_forget_title),
      SettingsTags.FORGET_BODY,
      robot.words(R.string.computer_forget_body, name),
      SettingsTags.FORGET_CANCEL,
    )
    robot.assertCancelFocused(SettingsTags.FORGET_CANCEL)
    assertThat(robot.text(SettingsTags.FORGET_CONFIRM)).isEqualTo(robot.words(R.string.computer_forget_confirm))
    robot.answerDialog(SettingsTags.FORGET_CANCEL)
    assertThat(status.paired.value).isNotNull()

    robot.forgetComputer()
    assertThat(robot.text(SettingsTags.COMPUTER_FORGOTTEN)).isEqualTo(robot.words(R.string.computer_forgotten, name))
    assertThat(status.paired.value).isNull()

    // F-22-AC3: Home says "Not connected to a computer", and the clip stays, from a computer this phone forgot.
    phono.back()
    phono.back()
    phono.assertShown(HomeKey)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { status.status.value == ComputerStatus.NotConnected }
    robot.waitForTag(ConnectionTags.STATUS)
    assertThat(robot.text(ConnectionTags.STATUS)).contains("Not connected to a computer")
    val clip = runBlocking { clips.clips(ClipQuery()).first() }.single { it.id == id }
    assertThat(clip.origin).isEqualTo(ClipOrigin.FORGOTTEN_COMPUTER)
  }

  @Test
  fun storageListsTheClipAndRemoveFromThisPhoneAsksFirstAndSendsNothing() {
    val title = "A walk in the park"
    val id = seedAClip(title)
    launchSettings()
    robot.tapIn(SettingsTags.PAGE, SettingsTags.LINK_STORAGE)
    robot.waitForTag(SettingsTags.STORAGE_TOTAL)
    robot.scrollTo(SettingsTags.STORAGE_LIST, SettingsTags.storageClip(id))
    assertThat(robot.text(SettingsTags.storageClip(id))).contains(title)

    robot.leaveTouchMode()
    robot.openWithoutTouch(SettingsTags.STORAGE_LIST, SettingsTags.storageRemove(id))
    robot.assertDialog(
      SettingsTags.REMOVE_TITLE,
      robot.words(R.string.storage_remove_title, title),
      SettingsTags.REMOVE_BODY,
      robot.words(R.string.storage_remove_body),
      SettingsTags.REMOVE_CANCEL,
    )
    robot.assertCancelFocused(SettingsTags.REMOVE_CANCEL)
    val sent = seeder.computer.requests.size
    robot.answerDialog(SettingsTags.REMOVE_CONFIRM)

    compose.waitUntil(PhonoRobot.TIMEOUT_MS) {
      runBlocking { clips.clips(ClipQuery()).first() }.single { it.id == id }.availability == ClipAvailability.NotOnThisPhone
    }
    // F-05-AC11: nothing about the removal reaches the computer.
    assertThat(seeder.computer.requests.drop(sent).filter { it.method == "DELETE" }).isEmpty()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !robot.has(SettingsTags.storageClip(id)) }
  }

  private companion object {
    const val LIMIT_S = 180L
    const val WORDS = 300
    const val PHRASES = 60
    const val WATCH_TIMEOUT_MS = 20_000L

    /** The Watch coach marks (WP-17's catalogue, spec F-01 item 4). */
    val WATCH_MARKS = listOf("swipe", "tap_word", "hold_slow", "marks").map(::CoachMarkId)
  }
}
