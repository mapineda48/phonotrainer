package com.mapineda48.phonotrainer.feature.library

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeProgressRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.ui.component.ClipCardTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A navigator that records where the screens send the learner. */
class RecordingNavigator : Navigator {
  val keys = mutableListOf<NavKey>()
  var backs = 0

  override fun navigate(key: NavKey) {
    keys += key
  }

  override fun back() {
    backs++
  }

  override fun switchTab(tab: Tab) = Unit

  override fun open(target: NavTarget) = Unit
}

/** Where each control of the two routes leads (architecture 16; spec F-04-AC5, F-05-AC1, F-05-AC5). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h4000dp")
class LibraryRoutesTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val navigator = RecordingNavigator()

  @Test
  fun theLibrarysControlsLeadToTheirDestinations() {
    val clips = CountingClips().apply {
      fake.all.value = LibrarySamples.clips
      fake.browser.value = emptyList()
    }
    val requests = FakeRequestRepository().apply { all.value = listOf(LibrarySamples.failed) }
    val vm = LibraryViewModel(clips, requests, FakeClipContentRepository(), FakeComputerStatusRepository(),
      TestClock(LibrarySamples.now), SavedStateHandle())
    screen.setContent { LibraryRoute(navigator, vm) }
    val coffee = LibrarySamples.coffee.id

    compose.onNode(hasTestTag(ClipCardTags.OPEN) and hasAnyAncestor(hasTestTag(LibraryTags.row(coffee)))).performClick()
    compose.onNode(hasTestTag(ClipCardTags.MORE) and hasAnyAncestor(hasTestTag(LibraryTags.row(coffee)))).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.WATCH)).performClick()
    compose.onNodeWithTag(LibraryTags.ADD).performClick()
    compose.onNode(hasTestTag("ui.request.open") and hasAnyAncestor(hasTestTag(LibraryTags.request(LibrarySamples.failed.id))))
      .performClick()
    compose.onNodeWithContentDescription("Back").performClick()

    // F-04-AC5: "Watch" opens the feed In order on that clip at its resume short (the feed resolves it).
    assertThat(navigator.keys).containsExactly(
      ClipKey(coffee),
      WatchKey(FeedStart.InOrder(coffee, firstSegment = null, fromStart = false)),
      AddClipKey(),
      RequestDetailKey(LibrarySamples.failed.id),
    ).inOrder()
    assertThat(navigator.backs).isEqualTo(1)
  }

  @Test
  fun aRequestsActionIsDoneHereOrWhereItsConfirmationLives() {
    val performed = mutableListOf<RequestAction>()
    RequestAction.entries.forEach { action ->
      routeRequestAction(navigator, LibrarySamples.failed, action) { performed += action }
    }
    assertThat(performed).containsExactly(
      RequestAction.SEND_NOW, RequestAction.STOP_SENDING, RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY,
      RequestAction.UNDO_CANCEL, RequestAction.CANCEL_PREPARING,
    )
    assertThat(navigator.keys.filterIsInstance<RequestDetailKey>()).hasSize(5)
    assertThat(navigator.keys.filterIsInstance<ConnectKey>()).containsExactly(ConnectKey(ConnectMode.CONNECT_AGAIN))
  }

  @Test
  fun theClipPagesControlsLeadToTheFeedAtTheRightShort() {
    val id = LibrarySamples.coffee.id
    val clips = FakeClipRepository().apply { details.value = mapOf(id to LibrarySamples.detail()) }
    val shorts = FakeShortRepository().apply { shortsById.value = mapOf(id to LibrarySamples.shorts) }
    val progress = FakeProgressRepository().apply { resumeById.value = mapOf(id to LibrarySamples.shorts[3].id) }
    val key = ClipKey(id)
    val vm = ClipViewModel(key, clips, shorts, FakeClipContentRepository(), progress, FakeRequestRepository(),
      FakeReferenceRepository(), FakeComputerStatusRepository(ComputerStatus.Connected("Desk PC", 0, 0, false)),
      TestClock(LibrarySamples.now), SavedStateHandle())
    screen.setContent { ClipRoute(key, navigator, ClipSlots(), vm) }

    compose.onNodeWithTag(LibraryTags.CLIP_WATCH).performClick()
    compose.onNodeWithTag(LibraryTags.CLIP_FROM_START).performClick()
    compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[1].id)).performClick()
    compose.onNodeWithTag(LibraryTags.tab(ClipTab.SUMMARY)).performClick()
    compose.onNodeWithTag(LibraryTags.change(ChangeLabel("flapping"))).performClick()
    compose.onNodeWithTag(LibraryTags.CLIP_BACK).performClick()

    assertThat(navigator.keys).containsExactly(
      WatchKey(FeedStart.InOrder(id, firstSegment = 6, fromStart = false)),
      WatchKey(FeedStart.InOrder(id, firstSegment = null, fromStart = true)),
      WatchKey(FeedStart.InOrder(id, firstSegment = 2, fromStart = false)),
      WatchKey(FeedStart.ChangeMode(ChangeLabel("flapping"), onlyClip = id)),
    ).inOrder()
    assertThat(navigator.backs).isEqualTo(1)
    assertThat(vm.state.value.tab).isEqualTo(ClipTab.SUMMARY)
  }
}
