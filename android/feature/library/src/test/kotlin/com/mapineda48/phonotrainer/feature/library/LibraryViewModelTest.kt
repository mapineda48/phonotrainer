package com.mapineda48.phonotrainer.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Library's ViewModel over the settable fakes of work-plan 4.6 (spec F-04). */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val clips = CountingClips()
  private val requests = FakeRequestRepository()
  private val content = FakeClipContentRepository()
  private val status = FakeComputerStatusRepository()
  private val clock = TestClock(LibrarySamples.now)

  private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
    LibraryViewModel(clips, requests, content, status, clock, saved)

  /** A view model whose state is collected for the test's duration. */
  private fun TestScope.collected(saved: SavedStateHandle = SavedStateHandle()): LibraryViewModel =
    viewModel(saved).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

  @Test
  fun theCatalogueIsReadOnceAndTheCountFollowsSearchAndFilter() = runTest {
    clips.fake.all.value = LibrarySamples.clips
    val vm = collected()
    assertThat(vm.state.value.total).isEqualTo(5)
    assertThat(vm.state.value.clips).hasSize(5)

    vm.setSearch("the")
    assertThat(vm.state.value.clips.map { it.clip.title })
      .containsExactly("At the station", "Ordering at the market", "Two friends at the lake").inOrder()
    vm.toggleFilter(ClipFilter.ON_THIS_PHONE)
    assertThat(vm.state.value.clips).isEmpty()
    assertThat(vm.state.value.total).isEqualTo(5)
    vm.setSearch("")
    assertThat(vm.state.value.clips.map { it.clip.title })
      .containsExactly("Coffee shop small talk", "Morning radio interview").inOrder()
    vm.toggleFilter(ClipFilter.ON_THIS_PHONE)
    assertThat(vm.state.value.filter).isEqualTo(ClipFilter.ALL)

    // One subscription per ViewModel: a kept ViewModel never reads the catalogue again.
    assertThat(clips.calls).isEqualTo(1)
  }

  @Test
  fun searchFilterAndSortSurviveARecreationFromTheSavedState() = runTest {
    clips.fake.all.value = LibrarySamples.clips
    val saved = SavedStateHandle()
    val first = collected(saved)
    first.setSearch("o")
    first.toggleFilter(ClipFilter.NOT_ON_THIS_PHONE)
    first.setSort(ClipSort.TITLE)

    val second = collected(saved)
    with(second.state.value) {
      assertThat(search).isEqualTo("o")
      assertThat(filter).isEqualTo(ClipFilter.NOT_ON_THIS_PHONE)
      assertThat(sort).isEqualTo(ClipSort.TITLE)
      assertThat(clips.map { it.clip.title })
        .containsExactly("At the station", "Ordering at the market", "Two friends at the lake").inOrder()
    }
  }

  @Test
  fun anUnreadableCatalogueSaysSoAndTryAgainReadsItAgain() = runTest {
    clips.fake.all.value = LibrarySamples.clips
    clips.failures = 1
    val vm = collected()
    assertThat(vm.state.value.failed).isTrue()

    vm.retry()
    assertThat(vm.state.value.failed).isFalse()
    assertThat(vm.state.value.total).isEqualTo(5)
  }

  @Test
  fun theCatalogueLoadsThenIsEmptyWhenTheComputerHasNoClips() = runTest {
    val vm = viewModel()
    assertThat(vm.state.value.loading).isTrue()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    assertThat(vm.state.value.empty).isTrue()
    assertThat(vm.state.value.paired).isFalse()
  }

  @Test
  fun inProgressAndTheBrowserLineComeFromTheQueueAndTheComputer() = runTest {
    requests.all.value = listOf(
      LibrarySamples.analyzing,
      LibrarySamples.request("done", RequestState.ON_PHONE, "done"),
      LibrarySamples.failed,
    )
    clips.fake.browser.value = listOf(
      ComputerJob(JobId("b1"), "From the browser", RemoteStatus.ERROR, null, 0,
        null, ClipSource(SourceKind.UPLOAD, null, null, null), null, LibrarySamples.now, hidden = false),
    )
    val vm = collected()
    assertThat(vm.state.value.inProgress).containsExactly(LibrarySamples.failed, LibrarySamples.analyzing).inOrder()
    assertThat(vm.state.value.startedOnComputer).isEqualTo(1)
  }

  @Test
  fun aPairedPhoneThatIsNotConnectedShowsTheOfflineBannerWithTheLastSync() = runTest {
    val synced = LibrarySamples.now.minusSeconds(600)
    status.pairedState.value = LibraryFixtures.paired(synced)
    status.statusState.value = ComputerStatus.NotReachable("Desk PC", synced, false, ProbeOutcome.NoAnswer(3))
    val vm = collected()
    assertThat(vm.state.value.offline).isTrue()
    assertThat(vm.state.value.lastSync).isEqualTo(synced)

    status.statusState.value = ComputerStatus.Connected("Desk PC", analyzing = 0, inLine = 0, lowOnSpace = false)
    assertThat(vm.state.value.offline).isFalse()
  }

  @Test
  fun eachClipCarriesItsLocalPoster() = runTest {
    val poster = File("poster.jpg")
    clips.fake.all.value = listOf(LibrarySamples.coffee, LibrarySamples.radio)
    content.mediaById[LibrarySamples.coffee.id] = ClipMedia(
      LibrarySamples.coffee.id, File("mix.wav"), null, null, null, null, null, 0, 0, poster, VideoAvailability.OnThisPhone,
    )
    val vm = collected()
    assertThat(vm.state.value.clips.associate { it.clip.id to it.poster })
      .containsExactly(LibrarySamples.coffee.id, poster, LibrarySamples.radio.id, null)
  }

  @Test
  fun removingFromThePhoneStaysOnThePhone() = runTest {
    // F-05-AC11: nothing is sent to the computer.
    clips.fake.all.value = LibrarySamples.clips
    val vm = collected()
    vm.removeFromPhone(LibrarySamples.coffee.id)
    vm.removeVideo(LibrarySamples.coffee.id)
    vm.download(LibrarySamples.lake.id)
    assertThat(clips.fake.actions).containsExactly(
      "removeFromPhone:${LibrarySamples.coffee.id.value}",
      "removeVideo:${LibrarySamples.coffee.id.value}",
      "downloadToPhone:${LibrarySamples.lake.id.value}",
    ).inOrder()
    assertThat(requests.performed).isEmpty()
    assertThat(requests.links).isEmpty()
  }

  @Test
  fun aDoubleTapWritesOnce() = runTest {
    val gate = CompletableDeferred<Unit>()
    clips.keepGate = gate
    val vm = collected()
    vm.setKept(LibrarySamples.coffee.id, true)
    vm.setKept(LibrarySamples.coffee.id, true)
    gate.complete(Unit)
    vm.setKept(LibrarySamples.coffee.id, false)
    assertThat(clips.keeps).isEqualTo(2)
    assertThat(clips.fake.actions).containsExactly(
      "setKept:${LibrarySamples.coffee.id.value}:true",
      "setKept:${LibrarySamples.coffee.id.value}:false",
    ).inOrder()
  }

  @Test
  fun aRequestsInlineActionIsPerformed() = runTest {
    val vm = collected()
    vm.perform(LibrarySamples.failed.id, RequestAction.TRY_AGAIN)
    assertThat(requests.performed).containsExactly(LibrarySamples.failed.id to RequestAction.TRY_AGAIN)
  }

  @Test
  fun theRowsFollowTheCatalogueLive() = runTest {
    clips.fake.all.value = listOf(LibrarySamples.coffee)
    val vm = collected()
    clips.fake.all.value = listOf(LibrarySamples.coffee.copy(availability = ClipAvailability.NotOnThisPhone))
    assertThat(vm.state.value.clips.single().clip.availability).isEqualTo(ClipAvailability.NotOnThisPhone)
  }
}
