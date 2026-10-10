package com.mapineda48.phonotrainer.shell

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeUserMessages
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The Back order, tab rules and internal targets of architecture 16.2 and 16.4, on the navigator alone. */
class AppNavigatorTest {
  private val clips = FakeClipRepository()
  private val requests = FakeRequestRepository()
  private val messages = FakeUserMessages()
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val stacks = Tab.entries.associateWith { NavBackStack<NavKey>(it.rootKey) }
  private val navigator = AppNavigator(stacks, mutableStateOf(Tab.HOME), scope, TargetResolver(clips, requests, messages))

  private fun shown(): List<NavKey> =
    if (navigator.currentTab == Tab.HOME) stacks.getValue(Tab.HOME) else stacks.getValue(Tab.HOME) + stacks.getValue(navigator.currentTab)

  @Test
  fun backPopsThenGoesHomeThenLeavesTheAppToTheSystem() {
    navigator.navigate(LibraryKey)
    navigator.switchTab(Tab.LEARN)
    navigator.navigate(IpaChartKey())
    assertThat(shown()).containsExactly(HomeKey, LibraryKey, LearnKey, IpaChartKey()).inOrder()

    navigator.back()
    assertThat(shown()).containsExactly(HomeKey, LibraryKey, LearnKey).inOrder()
    navigator.back()
    assertThat(shown()).containsExactly(HomeKey, LibraryKey).inOrder()
    assertThat(navigator.handlesBack).isTrue()
    navigator.back()
    assertThat(shown()).containsExactly(HomeKey)
    assertThat(navigator.handlesBack).isFalse()
  }

  @Test
  fun eachTabKeepsItsOwnStack() {
    navigator.switchTab(Tab.PRACTICE)
    navigator.navigate(SavedKey)
    navigator.switchTab(Tab.LEARN)
    navigator.switchTab(Tab.PRACTICE)
    assertThat(shown().last()).isEqualTo(SavedKey)
  }

  @Test
  fun reselectingATabPopsToItsRootExceptWatch() {
    navigator.switchTab(Tab.LEARN)
    navigator.navigate(IpaChartKey("ɾ"))
    navigator.switchTab(Tab.LEARN)
    assertThat(stacks.getValue(Tab.LEARN)).containsExactly(LearnKey)

    navigator.switchTab(Tab.WATCH)
    navigator.navigate(SavedKey)
    navigator.switchTab(Tab.WATCH)
    assertThat(stacks.getValue(Tab.WATCH)).containsExactly(WatchKey(), SavedKey).inOrder()
  }

  @Test
  fun tabRootsSwitchTabsAndAStartedFeedBecomesTheWatchRoot() {
    navigator.navigate(LearnKey)
    assertThat(navigator.currentTab).isEqualTo(Tab.LEARN)

    val start = WatchKey(FeedStart.TodaySet)
    navigator.navigate(start)
    assertThat(navigator.currentTab).isEqualTo(Tab.WATCH)
    assertThat(stacks.getValue(Tab.WATCH)).containsExactly(start)

    navigator.navigate(PracticeKey(focus = "flapping"))
    assertThat(stacks.getValue(Tab.PRACTICE)).containsExactly(PracticeKey(focus = "flapping"))
    navigator.back()
    assertThat(navigator.currentTab).isEqualTo(Tab.HOME)
  }

  @Test
  fun aMissingRequestOpensRequestsWithTheNotice() = runTest(scope.testScheduler) {
    navigator.open(NavTarget.RequestDetail(RequestId("gone")))
    assertThat(stacks.getValue(Tab.HOME)).containsExactly(HomeKey, RequestsKey).inOrder()
    assertThat(messages.posted.map { it.kind }).containsExactly(UserMessageKind.REQUEST_REMOVED)
  }

  @Test
  fun aClipStillHereOpensItsFeedAndAMissingOneGoesHomeWithTheNotice() = runTest(scope.testScheduler) {
    val here = JobId("here")
    clips.details.value = mapOf(here to detail(here))

    navigator.open(NavTarget.ClipFeed(here))
    assertThat(stacks.getValue(Tab.WATCH)).containsExactly(WatchKey(FeedStart.InOrder(here, null, fromStart = false)))
    assertThat(navigator.currentTab).isEqualTo(Tab.WATCH)

    navigator.open(NavTarget.WordLesson(JobId("gone"), segment = 1, word = 2))
    assertThat(navigator.currentTab).isEqualTo(Tab.HOME)
    assertThat(stacks.getValue(Tab.HOME)).containsExactly(HomeKey, LibraryKey).inOrder()
    assertThat(messages.posted.map { it.kind }).containsExactly(UserMessageKind.CLIP_GONE)
  }

  private fun detail(id: JobId) = ClipDetail(
    summary = ClipSummary(
      id, "A clip", null, null, null, ClipSource(SourceKind.LINK, null, null, null), ClipAvailability.OnThisPhone,
      VideoAvailability.AudioOnlyClip, kept = false, origin = ClipOrigin.ACTIVE, olderRules = false, shortsSeen = 0,
      shortsTotal = 0, addedAt = Instant.EPOCH,
    ),
    durationS = null, segments = null, changeCounts = emptyMap(), rulesVersion = null, phoneEngine = null,
    dialogueSeparation = null, hasDialogueTrack = false, studyPackBytes = null, videoBytes = null, lastWatched = null,
    resume = null, computerError = null,
  )
}
