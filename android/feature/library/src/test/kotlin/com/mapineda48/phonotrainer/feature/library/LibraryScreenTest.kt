package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ClipCardTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of the Library (spec F-04, design 10.2 P-01) at font scale 2.0 in light and dark with the accessibility
 * audit, and what its controls do: search with the live count, the filters, a stable order, "In progress" and the
 * "started on your computer" line, every row tag, the row menu and the "Remove from this phone?" confirmation.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window: the whole list is composed and on screen, so the audit measures every control (a phone scrolls).
@Config(qualifiers = "w412dp-h4000dp")
class LibraryScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()
  private val actions = LibraryActions(
    onBack = { asked += "back" },
    onAdd = { asked += "add" },
    onSearch = { asked += "search:$it" },
    onFilter = { asked += "filter:$it" },
    onShowAll = { asked += "showAll" },
    onSort = { asked += "sort:$it" },
    onRetry = { asked += "retry" },
    onOpenClip = { asked += "open:${it.value}" },
    onWatch = { asked += "watch:${it.value}" },
    onKeep = { id, kept -> asked += "keep:${id.value}:$kept" },
    onDownload = { asked += "download:${it.value}" },
    onRemoveFromPhone = { asked += "remove:${it.value}" },
    onRemoveVideo = { asked += "removeVideo:${it.value}" },
    onRequestAction = { request, action -> asked += "request:${request.id.value}:$action" },
    onOpenRequest = { asked += "openRequest:${it.value}" },
    onStartedOnComputer = { asked += "startedOnComputer" },
    onConnect = { asked += "connect" },
    onLearn = { asked += "learn" },
  )

  private fun text(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
    .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

  /** [words] are shown inside the node tagged [tag]. */
  private fun assertInside(tag: String, words: String) {
    compose.onNode(hasText(words) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).assertIsDisplayed()
  }

  private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

  private fun SemanticsNodeInteraction.description(): String =
    fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

  private fun row(id: JobId) = compose.onNode(hasTestTag(ClipCardTags.OPEN) and hasAnyAncestor(hasTestTag(LibraryTags.row(id))))

  private fun more(id: JobId) = compose.onNode(hasTestTag(ClipCardTags.MORE) and hasAnyAncestor(hasTestTag(LibraryTags.row(id))))

  /** The tags of the shown rows, top to bottom. */
  private fun rowOrder(): List<String> = compose.onAllNodes(hasTestTag(ClipCardTags.OPEN)).fetchSemanticsNodes()
    .sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.ContentDescription].single().substringBefore(",") }

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    val states = listOf(
      "ready" to LibrarySamples.ready,
      "loading" to LibraryUiState(),
      "error" to LibraryUiState(catalogue = Catalogue.Failed),
      "first run" to LibraryUiState(catalogue = Catalogue.Ready(emptyList())),
      "empty" to LibraryUiState(catalogue = Catalogue.Ready(emptyList()), paired = true),
      "offline" to LibrarySamples.ready.copy(offline = true, lastSync = LibrarySamples.now.minusSeconds(5_400)),
      "no match" to LibrarySamples.ready.copy(search = "kitchen", clips = emptyList()),
      "nothing on this phone" to LibrarySamples.ready.copy(filter = ClipFilter.ON_THIS_PHONE, clips = emptyList()),
    )
    val expected = mapOf(
      "ready" to LibraryTags.COUNT,
      "loading" to LibraryTags.LOADING,
      "error" to LibraryTags.ERROR,
      "first run" to LibraryTags.EMPTY,
      "empty" to LibraryTags.EMPTY,
      "offline" to LibraryTags.OFFLINE,
      "no match" to LibraryTags.NO_MATCH,
      "nothing on this phone" to LibraryTags.NO_MATCH,
    )
    var current by mutableStateOf(states.first().second)
    screen.setContent(ScreenVariant.REQUIRED.first()) { LibraryScreen(current, actions) }
    states.forEach { (name, state) ->
      current = state
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          assertThat(exists(expected.getValue(name))).isTrue()
          assertThat(exists(LibraryTags.LEDE)).isTrue()
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun theWordsOfEachStateAreTheSpecs() {
    var current by mutableStateOf(LibrarySamples.ready.copy(offline = true, lastSync = LibrarySamples.now.minusSeconds(5_400)))
    screen.setContent { LibraryScreen(current, actions) }
    assertInside(LibraryTags.OFFLINE, "Showing what this phone knows. Last synced today at 09:00.")
    current = current.copy(lastSync = null)
    compose.waitForIdle()
    assertInside(LibraryTags.OFFLINE, "Showing what this phone knows.")

    current = LibrarySamples.ready.copy(search = "kitchen", clips = emptyList())
    compose.waitForIdle()
    compose.onNode(hasText("No clip matches “kitchen”.")).assertIsDisplayed()
    compose.onNodeWithTag(LibraryTags.CLEAR_SEARCH).performClick()

    current = LibrarySamples.ready.copy(filter = ClipFilter.ON_THIS_PHONE, clips = emptyList())
    compose.waitForIdle()
    compose.onNode(hasText("No clip is on this phone yet.")).assertIsDisplayed()
    compose.onNodeWithTag(LibraryTags.SHOW_ALL).performClick()

    current = LibraryUiState(catalogue = Catalogue.Failed)
    compose.waitForIdle()
    compose.onNode(hasText("Your clips could not be loaded.")).assertIsDisplayed()
    compose.onNodeWithTag(LibraryTags.RETRY).performClick()

    current = LibraryUiState(catalogue = Catalogue.Ready(emptyList()))
    compose.waitForIdle()
    compose.onNode(hasText("1. Share a clip from YouTube or X, or pick one on this phone. 2. Your computer analyzes it. " +
      "3. Swipe through it short by short.")).assertIsDisplayed()
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.0").performClick()
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.1").performClick()

    current = LibraryUiState(catalogue = Catalogue.Ready(emptyList()), paired = true)
    compose.waitForIdle()
    compose.onNode(hasText("Your computer has no clips yet.")).assertIsDisplayed()
    compose.onNode(hasText("Start with a short clip (30–60 seconds) of natural dialogue.")).assertIsDisplayed()
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.0").performClick()

    assertThat(asked).containsExactly("search:", "showAll", "retry", "connect", "learn", "add").inOrder()
  }

  /** A Library over the real ViewModel and settable fakes, so typing and tapping change what shows. */
  private fun live(clips: CountingClips, requests: FakeRequestRepository = FakeRequestRepository(),
    status: FakeComputerStatusRepository = FakeComputerStatusRepository()): LibraryViewModel {
    val vm = LibraryViewModel(clips, requests, FakeClipContentRepository(), status, TestClock(LibrarySamples.now), SavedStateHandle())
    screen.setContent(ScreenVariant.REQUIRED.first()) {
      val state by vm.state.collectAsState()
      LibraryScreen(
        state,
        LibraryActions(onSearch = vm::setSearch, onFilter = vm::toggleFilter, onSort = vm::setSort, onShowAll = vm::showAll),
      )
    }
    return vm
  }

  @Test
  fun searchingUpdatesTheLiveCount() {
    val clips = CountingClips().apply { fake.all.value = LibrarySamples.clips }
    live(clips)
    assertThat(text(LibraryTags.COUNT)).isEqualTo("5 clips")
    compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(LibraryTags.SEARCH))).performTextInput("the")
    compose.waitForIdle()
    assertThat(text(LibraryTags.COUNT)).isEqualTo("3 of 5 clips")
    assertThat(rowOrder()).containsExactly("At the station", "Ordering at the market", "Two friends at the lake").inOrder()
    val count = compose.onNodeWithTag(LibraryTags.COUNT, useUnmergedTree = true).fetchSemanticsNode().config
    assertThat(count[SemanticsProperties.LiveRegion]).isEqualTo(LiveRegionMode.Polite)
    screen.audit()
  }

  @Test
  fun onThisPhoneShowsThreeOfTwelve() {
    // F-04-AC1.
    val twelve = (1..12).map { n ->
      LibrarySamples.clip(
        "20261001-0000%02d-aaaa".format(n),
        "Clip $n",
        addedAt = LibrarySamples.now.minusSeconds(n * 60L),
        availability = if (n <= 3) ClipAvailability.OnThisPhone else ClipAvailability.NotOnThisPhone,
      )
    }
    live(CountingClips().apply { fake.all.value = twelve })
    compose.onNodeWithTag(LibraryTags.filter(ClipFilter.ON_THIS_PHONE)).performClick()
    compose.waitForIdle()
    assertThat(text(LibraryTags.COUNT)).isEqualTo("3 of 12 clips")
    assertThat(rowOrder()).containsExactly("Clip 1", "Clip 2", "Clip 3").inOrder()
    val chip = compose.onNodeWithTag(LibraryTags.filter(ClipFilter.ON_THIS_PHONE)).fetchSemanticsNode().config
    assertThat(chip[SemanticsProperties.Selected]).isTrue()

    compose.onNodeWithTag(LibraryTags.filter(ClipFilter.NOT_ON_THIS_PHONE)).performClick()
    compose.waitForIdle()
    assertThat(text(LibraryTags.COUNT)).isEqualTo("9 of 12 clips")
    compose.onNodeWithTag(LibraryTags.filter(ClipFilter.NOT_ON_THIS_PHONE)).performClick()
    compose.waitForIdle()
    assertThat(text(LibraryTags.COUNT)).isEqualTo("12 clips")
  }

  @Test
  fun clipsOfTheSameSecondKeepTheirOrderAcrossRefreshes() {
    // F-04-AC4.
    val second = LibrarySamples.now.minusSeconds(60)
    val a = LibrarySamples.clip("20261004-102900-a77c", "First of the second", addedAt = second)
    val b = LibrarySamples.clip("20261004-102900-0b1d", "Second of the second", addedAt = second)
    val clips = CountingClips().apply { fake.all.value = listOf(a, b) }
    live(clips)
    val first = rowOrder()
    repeat(4) { n ->
      clips.fake.all.value = if (n % 2 == 0) listOf(b, a) else listOf(a, b.copy(shortsSeen = n))
      compose.waitForIdle()
      assertThat(rowOrder()).isEqualTo(first)
    }
    assertThat(first).containsExactly("First of the second", "Second of the second").inOrder()
  }

  @Test
  fun theSortMenuOffersTheWebsThreeOrders() {
    screen.setContent { LibraryScreen(LibrarySamples.ready, actions) }
    assertThat(compose.onNodeWithTag(LibraryTags.SORT).description()).isEqualTo("Sort: Newest first")
    compose.onNodeWithTag(LibraryTags.SORT).performClick()
    listOf("Newest first", "Oldest first", "Name A–Z").zip(LibraryRules.SORTS).forEach { (words, sort) ->
      compose.onNode(hasTestTag(LibraryTags.sortOption(sort)) and hasText(words)).assertIsDisplayed()
    }
    screen.audit()
    compose.onNodeWithTag(LibraryTags.sortOption(ClipSort.OLDEST)).performClick()
    assertThat(asked).containsExactly("sort:OLDEST")
  }

  @Test
  fun inProgressListsThisPhonesRequestsAndOneLineForTheComputersOwn() {
    screen.setContent { LibraryScreen(LibrarySamples.ready, actions) }
    compose.onNodeWithTag(LibraryTags.IN_PROGRESS).assertIsDisplayed()
    listOf(LibrarySamples.failed, LibrarySamples.waiting, LibrarySamples.analyzing).forEach {
      compose.onNodeWithTag(LibraryTags.request(it.id)).assertIsDisplayed()
    }
    // F-04-AC6: the computer's own jobs are one line, never rows.
    assertThat(compose.onNodeWithTag(LibraryTags.STARTED_ON_COMPUTER).description()).isEqualTo("2 more started on your computer")
    compose.onNodeWithTag(LibraryTags.STARTED_ON_COMPUTER).performClick()
    compose.onNode(hasAnyAncestor(hasTestTag(LibraryTags.request(LibrarySamples.failed.id))) and hasTestTag("ui.request.open"))
      .performClick()
    assertThat(asked).containsExactly("startedOnComputer", "openRequest:${LibrarySamples.failed.id.value}").inOrder()
  }

  @Test
  fun withNothingOfItsOwnTheComputersLineHasNoMore() {
    screen.setContent { LibraryScreen(LibrarySamples.ready.copy(inProgress = emptyList(), startedOnComputer = 1), actions) }
    assertThat(exists(LibraryTags.IN_PROGRESS)).isFalse()
    assertThat(compose.onNodeWithTag(LibraryTags.STARTED_ON_COMPUTER).description()).isEqualTo("1 started on your computer")
  }

  @Test
  fun everyRowTagIsSaid() {
    screen.setContent { LibraryScreen(LibrarySamples.ready, actions) }
    assertThat(row(LibrarySamples.station.id).description()).contains(
      "X, Bringing to this phone 45 %, Older rules: re-analyze on your computer to update",
    )
    assertThat(row(LibrarySamples.lake.id).description()).contains("Not on this phone, You've seen 3 of 15 shorts, From a computer you forgot")
    assertThat(row(LibrarySamples.market.id).description())
      .contains("Download paused: storage budget reached, You've seen 12 of 15 shorts, From another library")
    assertThat(row(LibrarySamples.coffee.id).description()).contains("YouTube, On this phone, Kept, You've seen 12 of 15 shorts")
    assertThat(row(LibrarySamples.radio.id).description()).contains("10 min 14 s · Audio only · 691 words · 14 changes, Uploaded file")
    row(LibrarySamples.coffee.id).performClick()
    assertThat(asked).containsExactly("open:${LibrarySamples.coffee.id.value}")
  }

  @Test
  fun theRowMenuHoldsItsItemsAndNeverDeletesFromTheComputer() {
    screen.setContent(ScreenVariant.REQUIRED.first()) { LibraryScreen(LibrarySamples.ready, actions) }
    more(LibrarySamples.coffee.id).performClick()
    val items = listOf(
      RowMenuItem.WATCH to "Watch",
      RowMenuItem.OPEN to "Open clip page",
      RowMenuItem.STOP_KEEPING to "Stop keeping",
      RowMenuItem.REMOVE_FROM_PHONE to "Remove from this phone…",
      RowMenuItem.REMOVE_VIDEO to "Remove the video only",
    )
    items.forEach { (item, words) -> compose.onNode(hasTestTag(LibraryTags.menu(item)) and hasText(words)).assertIsDisplayed() }
    assertThat(compose.onAllNodes(hasText("Delete from your computer…")).fetchSemanticsNodes()).isEmpty()
    assertThat(compose.onAllNodes(hasText("Download to this phone")).fetchSemanticsNodes()).isEmpty()
    screen.audit()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.REMOVE_VIDEO)).performClick()

    more(LibrarySamples.lake.id).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.KEEP)).performClick()
    more(LibrarySamples.lake.id).performClick()
    compose.onNode(hasTestTag(LibraryTags.menu(RowMenuItem.DOWNLOAD)) and hasText("Download to this phone")).performClick()
    more(LibrarySamples.lake.id).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.WATCH)).performClick()
    more(LibrarySamples.lake.id).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.OPEN)).performClick()
    val lake = LibrarySamples.lake.id.value
    assertThat(asked).containsExactly(
      "removeVideo:${LibrarySamples.coffee.id.value}", "keep:$lake:true", "download:$lake", "watch:$lake", "open:$lake",
    ).inOrder()
  }

  @Test
  fun removeFromThisPhoneAsksFirstWithTheWordsOfTheSpec() {
    // F-05-AC11 from the Library's menu.
    screen.setContent(ScreenVariant.REQUIRED.first()) { LibraryScreen(LibrarySamples.ready, actions) }
    more(LibrarySamples.coffee.id).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.REMOVE_FROM_PHONE)).performClick()

    compose.onNode(isDialog()).assertIsDisplayed()
    assertThat(text(LibraryTags.REMOVE_TITLE)).isEqualTo("Remove “Coffee shop small talk” from this phone?")
    assertThat(text(LibraryTags.REMOVE_BODY)).isEqualTo(
      "Its clip data, audio and video are deleted from this phone only. It stays on your computer, and you can " +
        "download it again at any time. Your saved items and progress stay.",
    )
    compose.onNode(hasTestTag(LibraryTags.REMOVE_CANCEL) and hasText("Cancel")).assertIsFocused()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_CONFIRM) and hasText("Remove from this phone")).assertIsDisplayed()
    val pane = compose.onNodeWithTag(LibraryTags.REMOVE_DIALOG).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
    assertThat(pane).isEqualTo("Remove “Coffee shop small talk” from this phone?")
    ScreenVariant.REQUIRED.forEach {
      screen.show(it)
      screen.audit()
    }
    compose.onNodeWithTag(LibraryTags.REMOVE_CANCEL).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(asked).isEmpty()

    more(LibrarySamples.coffee.id).performClick()
    compose.onNodeWithTag(LibraryTags.menu(RowMenuItem.REMOVE_FROM_PHONE)).performClick()
    compose.onNodeWithTag(LibraryTags.REMOVE_CONFIRM).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(asked).containsExactly("remove:${LibrarySamples.coffee.id.value}")
  }

  @Test
  fun theTopBarGoesBackAndAddsAClip() {
    screen.setContent { LibraryScreen(LibrarySamples.ready, actions) }
    compose.onNodeWithContentDescription("Back").performClick()
    compose.onNodeWithTag(LibraryTags.ADD).performClick()
    assertThat(asked).containsExactly("back", "add").inOrder()
  }

  @Test
  fun anOfflineCatalogueStillListsItsRows() {
    val status = FakeComputerStatusRepository(ComputerStatus.NotReachable("Desk PC", null, false, ProbeOutcome.NoAnswer(3)))
    status.pairedState.value = LibraryFixtures.paired(LibrarySamples.now.minusSeconds(86_400 + 600))
    live(CountingClips().apply { fake.all.value = LibrarySamples.clips }, status = status)
    assertInside(LibraryTags.OFFLINE, "Showing what this phone knows. Last synced yesterday at 10:20.")
    assertThat(rowOrder()).hasSize(5)
  }
}
