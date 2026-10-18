package com.mapineda48.phonotrainer.e2e.smoke

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PhonoNavSerialization
import com.mapineda48.phonotrainer.core.navigation.PhraseDetailsKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey
import com.mapineda48.phonotrainer.core.ui.extension.ClipReductionMeters
import com.mapineda48.phonotrainer.core.ui.extension.ClipTranscriptTab
import com.mapineda48.phonotrainer.core.ui.extension.InsightsAvailability
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer
import com.mapineda48.phonotrainer.core.ui.extension.PhraseDetailsEntry
import com.mapineda48.phonotrainer.core.ui.extension.RecordSection
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.home.HomeTags
import com.mapineda48.phonotrainer.feature.learn.LearnTags
import com.mapineda48.phonotrainer.feature.onboarding.OnboardingTags
import com.mapineda48.phonotrainer.feature.watch.WatchTags
import com.mapineda48.phonotrainer.shell.ShellTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The navigation smoke of WP-02 M2 (work-plan WP-02 Proof): every key opens its destination through the real shell,
 * Back follows architecture 16.2, tab reselection, the lesson overlay over a composed feed, the Welcome on first
 * launch, internal targets only through the alias, and no extension point bound in v1-core (work-plan 4.4).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class NavigationSmokeTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var mouth: Optional<MouthRenderer>

  @Inject lateinit var record: Optional<RecordSection>

  @Inject lateinit var phrase: Optional<PhraseDetailsEntry>

  @Inject lateinit var transcript: Optional<ClipTranscriptTab>

  @Inject lateinit var insights: Optional<InsightsAvailability>

  @Inject lateinit var meters: Optional<ClipReductionMeters>

  private val robot = PhonoRobot(compose)
  private val job = JobId("stub-1")

  private fun start(welcomeDone: Boolean = true): PhonoRobot {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = welcomeDone) } }
    return robot.launch()
  }

  @After
  fun tearDown() {
    if (robot.isLaunched()) robot.scenario.close()
  }

  @Test
  fun everyDestinationOpens() {
    start()
    val keys: List<NavKey> = listOf(
      HomeKey,
      WatchKey(),
      WatchKey(FeedStart.ChangeMode(ChangeLabel("flapping"), onlyClip = null)),
      LearnKey,
      PracticeKey(),
      PracticeKey(focus = "flapping"),
      LibraryKey,
      ClipKey(job),
      RequestsKey,
      RequestDetailKey(RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")),
      AddClipKey(),
      ConnectKey(),
      ConnectionDetailsKey,
      PhenomenonKey(ChangeLabel("flapping")),
      IpaChartKey(),
      InsightsKey,
      SavedKey,
      SettingsKey,
      StorageKey,
      YourComputerKey,
      NotificationSettingsKey,
      AboutKey,
      LicencesKey,
      HelpKey,
      WelcomeKey(restart = true),
      GlossaryIndexKey,
      LessonKey(job, 0, 0, LessonOrigin.CLIP_PAGE, LessonPresentation.FULL),
      LessonKey(job, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET),
      PhraseDetailsKey(job, 0),
    )
    keys.forEach { key ->
      robot.open(key)
      robot.assertShown(key)
      robot.resetStacks()
    }
  }

  @Test
  fun backClosesPopsThenGoesHomeThenLeaves() {
    start()
    // Home is one scrolling page (WP-15): "All clips" is below the fold on a phone.
    robot.waitForTag(HomeTags.LINK_LIBRARY)
    compose.onNodeWithTag(HomeTags.LINK_LIBRARY).performScrollTo()
    robot.tap(HomeTags.LINK_LIBRARY)
    robot.assertShown(LibraryKey)
    robot.tab(Tab.LEARN)
    robot.tap(LearnTags.LINK_IPA)
    robot.assertShown(IpaChartKey())

    robot.back()
    robot.assertShown(LearnKey)
    robot.assertNotShown(IpaChartKey())
    robot.back()
    robot.assertShown(LibraryKey)
    robot.back()
    robot.assertShown(HomeKey)
    robot.assertNotShown(LibraryKey)
    robot.back()
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !robot.isResumed() }
  }

  @Test
  fun reselectingATabPopsToItsRootExceptWatch() {
    start()
    robot.tab(Tab.LEARN)
    robot.tap(LearnTags.LINK_IPA)
    robot.assertShown(IpaChartKey())
    robot.tab(Tab.LEARN)
    robot.assertShown(LearnKey)
    robot.assertNotShown(IpaChartKey())

    robot.tab(Tab.WATCH)
    val fullLesson = LessonKey(job, 0, 0, LessonOrigin.CLIP_PAGE, LessonPresentation.FULL)
    robot.open(fullLesson)
    robot.assertShown(fullLesson)
    robot.tab(Tab.WATCH)
    robot.assertShown(fullLesson)
  }

  @Test
  fun theLessonSheetOpensOverAComposedFeed() {
    start()
    robot.tab(Tab.WATCH)
    // The real feed (WP-17) opens the lesson from a word or the spotlight; with no clip on the phone, open it as
    // the feed does.
    robot.waitForTag(WatchTags.START)
    robot.open(LessonKey(job, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET))
    robot.assertShown(LessonKey(job, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET))
    // The feed stays composed beneath the overlay scene.
    assertThat(compose.onAllNodes(hasTestTag(WatchTags.START), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()

    robot.back()
    robot.assertNotShown(LessonKey(job, 0, 0, LessonOrigin.FEED, LessonPresentation.SHEET))
    robot.assertShown(WatchKey())
  }

  @Test
  fun theWelcomeShowsOnTheFirstLaunchUntilGetStarted() {
    start(welcomeDone = false)
    robot.assertShown(WelcomeKey())
    robot.tap(OnboardingTags.GET_STARTED)
    robot.assertNotShown(WelcomeKey())
    robot.assertShown(HomeKey)
    assertThat(settings.settings.value.welcomeDone).isTrue()
  }

  @Test
  fun internalTargetsArriveOnlyThroughTheAliasAndFallBackWithANotice() {
    start()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Not through the alias: the extra is ignored.
    robot.deliver(
      context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .putExtra(InternalEntry.EXTRA_TARGET, PhonoNavSerialization.encode(NavTarget.Requests)),
    )
    robot.assertShown(HomeKey)
    robot.assertNotShown(RequestsKey)

    // Through the alias: a request the phone no longer has opens Requests with the spec's notice.
    robot.deliver(InternalEntry.intent(context, NavTarget.RequestDetail(RequestId("gone"))))
    robot.assertShown(RequestsKey)
    compose.waitUntilAtLeastOneExists(hasText("This request was removed."), PhonoRobot.TIMEOUT_MS)

    // A recreate never applies the target again.
    robot.back()
    robot.assertShown(HomeKey)
    robot.scenario.recreate()
    robot.waitForTag(ShellTags.tab(Tab.HOME))
    robot.assertShown(HomeKey)
    robot.assertNotShown(RequestsKey)
  }

  @Test
  fun noExtensionPointIsBoundInV1Core() {
    hilt.inject()
    listOf(mouth, record, phrase, transcript, insights, meters).forEach { assertThat(it.isPresent).isFalse() }
  }
}
