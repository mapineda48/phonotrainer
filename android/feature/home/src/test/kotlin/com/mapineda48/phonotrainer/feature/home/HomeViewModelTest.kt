package com.mapineda48.phonotrainer.feature.home

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.SetRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.KeepWatching
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeOccurrenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakePracticeRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeProgressRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSetRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeStorageRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import com.mapineda48.phonotrainer.core.ui.extension.InsightsAvailability
import java.time.Duration
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Home's ViewModel over the settable fakes of work-plan 4.6 (spec F-03). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val now = Instant.parse("2026-10-04T10:30:00Z")
  private val computer = FakeComputerStatusRepository()
  private val sync = FakeSyncController()
  private val requests = FakeRequestRepository()
  private val clips = CountingClips()
  private val content = FakeClipContentRepository()
  private val shorts = FakeShortRepository()
  private val occurrences = FakeOccurrenceRepository()
  private val reference = FakeReferenceRepository()
  private val sets = CountingSets()
  private val progress = FakeProgressRepository()
  private val practice = FakePracticeRepository()
  private val saved = FakeSavedRepository()
  private val storage = FakeStorageRepository()
  private val clock = TestClock(now)

  private fun viewModel(insights: InsightsAvailability? = null) = HomeViewModel(
    computer, sync, requests, clips, content, shorts, occurrences, reference, sets, progress, practice, saved, storage,
    Optional.ofNullable(insights), clock,
  )

  /** A view model whose state is collected for the test's duration. */
  private fun TestScope.collected(insights: InsightsAvailability? = null): HomeViewModel =
    viewModel(insights).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

  private val coffee = HomeSamples.coffee
  private val radio = HomeSamples.radio

  @Test
  fun theStatusCardGetsTheComputerTheCountsAndHomesExtras() = runTest {
    computer.statusState.value = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 0, lowOnSpace = false)
    computer.pairedState.value = HomeFixtures.paired()
    computer.restricted.value = true
    computer.diagnosticsState.value = computer.diagnosticsState.value.copy(lastProbeAt = now.minusSeconds(10))
    requests.countsState.value = RequestCounts(waiting = 2, onComputer = 1, needsAttention = 1, readyNew = 0, analyzing = 1, inLine = 0)
    requests.all.value = listOf(
      request("analyzing", RequestState.ANALYZING, progress = RequestProgress(Stage.PHONES, 40, null, null, null, null)),
      request("failed", RequestState.NEEDS_ATTENTION, error = RequestError("download_failed", null, null, atSending = false)),
    )
    storage.usageState.value = storage.usageState.value.copy(belowFloor = true)
    clips.fake.all.value = listOf(coffee, HomeSamples.station)
    val vm = collected()

    val state = vm.state.value
    assertThat(state.status).isInstanceOf(ComputerStatus.Connected::class.java)
    assertThat(state.paired).isTrue()
    assertThat(state.counts.waiting).isEqualTo(2)
    assertThat(state.attention?.id).isEqualTo(RequestId("failed"))
    assertThat(state.extras.checkedAt).isEqualTo(now.minusSeconds(10))
    assertThat(state.extras.now).isEqualTo(now)
    assertThat(state.extras.analyzing?.percent).isEqualTo(40)
    assertThat(state.extras.storageNearlyFull).isTrue()
    assertThat(state.extras.backgroundRestricted).isTrue()
    assertThat(state.extras.clipsOnPhone).isEqualTo(1)
    assertThat(state.extras.clipsTotal).isEqualTo(2)
  }

  @Test
  fun aStageChangeReachesTheCardAtOnce() = runTest {
    // F-03-AC5: the card follows the request as its stage changes.
    requests.countsState.value = RequestCounts(0, 1, 0, 0, analyzing = 1)
    requests.all.value = listOf(request("analyzing", RequestState.ANALYZING, progress = RequestProgress(Stage.TRANSCRIBE, 20, null, null, null, null)))
    val vm = collected()
    assertThat(vm.state.value.extras.analyzing?.stage).isEqualTo(Stage.TRANSCRIBE)
    requests.all.value = listOf(request("analyzing", RequestState.ANALYZING, progress = RequestProgress(Stage.PHONES, 61, null, null, null, null)))
    assertThat(vm.state.value.extras.analyzing?.stage).isEqualTo(Stage.PHONES)
    assertThat(vm.state.value.extras.analyzing?.percent).isEqualTo(61)
  }

  @Test
  fun jobsStartedFromTheBrowserAreNeverCounted() = runTest {
    // F-03-AC9: the counts are this phone's own requests (RequestRepository.counts), never the computer's jobs.
    clips.fake.browser.value = listOf(
      ComputerJob(
        id = coffee.id, title = "Started in the browser", status = RemoteStatus.ERROR, stage = null, percent = 0, queuePosition = null,
        source = ClipSource(SourceKind.UPLOAD, null, null, "talk.webm"), error = null, createdAt = now, hidden = false,
      ),
    )
    val vm = collected()
    assertThat(vm.state.value.counts).isEqualTo(RequestCounts(0, 0, 0, 0))
    assertThat(vm.state.value.attention).isNull()
  }

  @Test
  fun todaysSetIsMadeOnceAClipIsOnThisPhone() = runTest {
    clips.fake.all.value = listOf(coffee.copy(availability = ClipAvailability.Bringing(40)))
    val vm = collected()
    assertThat(sets.ensured).isEqualTo(0)
    assertThat(vm.state.value.today).isEqualTo(TodayCard.NoClips)

    sets.fake.todayState.value = null
    clips.fake.all.value = listOf(coffee)
    runCurrent()
    assertThat(sets.ensured).isEqualTo(1)
    assertThat(vm.state.value.today).isEqualTo(TodayCard.NothingNew)

    sets.fake.todayState.value = HomeFixtures.acTenSet()
    val card = vm.state.value.today as TodayCard.Ready
    assertThat(card.summary.shorts).isEqualTo(8)
    assertThat(card.phase).isEqualTo(TodayPhase.FRESH)

    // Another clip arriving does not make the set again (F-08-AC3: today's set never changes).
    clips.fake.all.value = listOf(coffee, radio)
    runCurrent()
    assertThat(sets.ensured).isEqualTo(1)
  }

  @Test
  fun whileTheSetIsBeingMadeTheCardSaysSo() = runTest {
    sets.gate = CompletableDeferred()
    clips.fake.all.value = listOf(coffee)
    val vm = collected()
    runCurrent()
    assertThat(vm.state.value.today).isEqualTo(TodayCard.Preparing)
    sets.gate?.complete(Unit)
    runCurrent()
    assertThat(vm.state.value.today).isEqualTo(TodayCard.NothingNew)
  }

  @Test
  fun anUnreadableCatalogueIsAnErrorAndTryAgainReadsItAgain() = runTest {
    clips.failures = 1
    val vm = collected()
    assertThat(vm.state.value.catalogue).isEqualTo(HomeCatalogue.Failed)
    assertThat(HomeRules.blocks(vm.state.value)).contains(HomeBlock.ERROR)
    clips.fake.all.value = listOf(coffee)
    vm.retry()
    assertThat(vm.state.value.catalogue).isEqualTo(HomeCatalogue.Ready(1, 1))
    assertThat(clips.calls).isEqualTo(2)
  }

  @Test
  fun pullToRefreshSyncsWhenConnectedAndOtherwiseOnlySaysSo() = runTest {
    val vm = collected()
    computer.statusState.value = ComputerStatus.NotReachable("Desk PC", null, false, ProbeOutcome.NoAnswer(3))
    vm.refresh()
    assertThat(vm.state.value.notice).isEqualTo(HomeNotice.NOT_REACHABLE)
    assertThat(sync.reasons).isEmpty()
    assertThat(vm.state.value.refreshing).isFalse()
    advanceTimeBy(4_100)
    assertThat(vm.state.value.notice).isNull()

    computer.statusState.value = ComputerStatus.Connected("Desk PC", 0, 0, false)
    vm.refresh()
    assertThat(sync.reasons).containsExactly(SyncReason.PULL_TO_REFRESH)
    assertThat(vm.state.value.refreshing).isTrue()
    sync.stateFlow.value = SyncState.Active(bringing = 1, percent = 10, sending = 0)
    advanceTimeBy(1_000)
    assertThat(vm.state.value.refreshing).isTrue()
    sync.stateFlow.value = SyncState.Idle
    runCurrent()
    assertThat(vm.state.value.refreshing).isFalse()

    vm.syncNow()
    assertThat(sync.reasons).containsExactly(SyncReason.PULL_TO_REFRESH, SyncReason.SYNC_NOW).inOrder()
  }

  @Test
  fun keepWatchingSaysWhichShortAndWhatToListenFor() = runTest {
    shorts.shortsById.value = mapOf(radio.id to (0 until 31).map { HomeFixtures.short(it, if (it == 3) "flapping" else null, ordinal = it + 1, ofTotal = 31, id = radio.id) })
    content.data[radio.id] = HomeFixtures.clipData(radio.id, listOf("a"), listOf("b"), listOf("c"), listOf("water", "please"))
    progress.keepWatchingState.value = KeepWatching(radio, ShortId(radio.id, 3), positionMs = 1_200)
    val vm = collected()
    val card = checkNotNull(vm.state.value.keepWatching)
    assertThat(card.ordinal).isEqualTo(4)
    assertThat(card.total).isEqualTo(31)
    assertThat(card.changeName).isEqualTo("flapping")
    assertThat(card.family).isEqualTo(Family.TD)
    assertThat(card.words).isEqualTo("water")
    assertThat(card.short).isEqualTo(ShortId(radio.id, 3))
    progress.keepWatchingState.value = null
    assertThat(vm.state.value.keepWatching).isNull()
  }

  @Test
  fun theShelfShowsTheClipsMostRecentlyStudiedOrAddedWithTheirFamilies() = runTest {
    val old = coffee.copy(id = JobId("old"), title = "Old clip", addedAt = now.minusSeconds(900_000), shortsSeen = 2)
    clips.fake.all.value = listOf(radio, old)
    clips.fake.details.value = mapOf(
      old.id to detail(old, lastWatched = now.minusSeconds(60), counts = mapOf("flapping" to 3, "t_deletion" to 2, "linking" to 1)),
      radio.id to detail(radio, lastWatched = null, counts = emptyMap()),
    )
    val vm = collected()
    val shelf = vm.state.value.shelf
    assertThat(shelf.map { it.clip.title }).containsExactly("Old clip", "Morning radio interview").inOrder()
    assertThat(shelf.first().families.map { it.family to it.count }).containsExactly(Family.TD to 5, Family.BOUNDARY to 1).inOrder()
  }

  @Test
  fun newOnThisPhoneAndTheChipsAndThePracticeCardComeFromTheirRepositories() = runTest {
    clips.fake.newOnPhone.value = listOf(coffee, radio, HomeSamples.station, HomeSamples.lake)
    occurrences.counts.value = mapOf(ChangeLabel("flapping") to ChangeCount(34, 90))
    practice.accuracyState.value = listOf(ChangeAccuracy(ChangeLabel("t_deletion"), seen = 9, correct = 4))
    saved.items.value = listOf(savedItem(1, SavedKind.SHORT), savedItem(2, SavedKind.SHORT), savedItem(3, SavedKind.WORD))
    val vm = collected()
    val state = vm.state.value
    assertThat(state.newOnPhone.map { it.clip.id }).containsExactly(coffee.id, radio.id, HomeSamples.station.id).inOrder()
    assertThat(state.newOnPhone.first().shorts).isEqualTo(coffee.shortsTotal)
    val flapping = state.changes.flatMap { it.chips }.single { it.label == ChangeLabel("flapping") }
    assertThat(flapping.shorts).isEqualTo(34)
    assertThat(state.changes.flatMap { it.chips }).hasSize(16)
    assertThat(state.practice).isEqualTo(PracticeCard(WeakSpot("t/d deletion", 4, 9), savedShorts = 2))
    assertThat(state.insights).isNull()
  }

  @Test
  fun theInsightsTeaserShowsOnlyWhenInsightsIsBound() = runTest {
    val vm = collected(insights = object : InsightsAvailability {
      override val teaser: Flow<String?> = flowOf("About 7 in 10 words you hear don't sound the way the dictionary writes them.")
    })
    assertThat(vm.state.value.insights).startsWith("About 7 in 10")
    assertThat(HomeRules.blocks(vm.state.value).last()).isEqualTo(HomeBlock.INSIGHTS)
  }

  @Test
  fun aDoubleTapOnTheInlineActionCarriesItOutOnce() = runTest {
    val gated = GatedRequests(requests)
    val vm = HomeViewModel(
      computer, sync, gated, clips, content, shorts, occurrences, reference, sets, progress, practice, saved, storage, Optional.empty(), clock,
    )
    vm.perform(RequestId("failed"), RequestAction.TRY_AGAIN)
    vm.perform(RequestId("failed"), RequestAction.TRY_AGAIN)
    gated.gate.complete(Unit)
    runCurrent()
    assertThat(requests.performed).containsExactly(RequestId("failed") to RequestAction.TRY_AGAIN)
    // Once it is done, the action can be asked again.
    vm.perform(RequestId("failed"), RequestAction.TRY_AGAIN)
    runCurrent()
    assertThat(requests.performed).hasSize(2)
  }

  @Test
  fun theTimesOnTheCardStayTrueWhileHomeIsShown() = runTest {
    computer.diagnosticsState.value = computer.diagnosticsState.value.copy(lastProbeAt = now.minusSeconds(10))
    val vm = collected()
    assertThat(vm.state.value.extras.now).isEqualTo(now)
    clock.advanceBy(Duration.ofSeconds(30))
    advanceTimeBy(30_001)
    // "Checked 40 s ago" a moment later, without any other change.
    assertThat(vm.state.value.extras.now).isEqualTo(now.plusSeconds(30))
    assertThat(vm.state.value.now).isEqualTo(now.plusSeconds(30))
  }

  @Test
  fun comingBackAfterALongAbsenceKeepsWhatHomeShowed() = runTest {
    // Back from the feed must find Home at its height (F-03-AC8): no collapse to a loading state.
    clips.fake.all.value = listOf(coffee, radio)
    val vm = viewModel()
    val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    assertThat(vm.state.value.catalogue).isEqualTo(HomeCatalogue.Ready(2, 2))
    shown.cancel()
    advanceTimeBy(60_000)
    val seen = mutableListOf<HomeUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
    assertThat(seen.map { it.catalogue }).doesNotContain(HomeCatalogue.Loading)
  }

  private fun request(id: String, state: RequestState, progress: RequestProgress? = null, error: RequestError? = null) = Request(
    id = RequestId(id), kind = RequestKind.LINK, state = state, title = id, site = null, link = null, fileName = null, fileBytes = null,
    createdAt = now, progress = progress, lastError = error, jobId = null, actions = setOf(RequestAction.TRY_AGAIN, RequestAction.REMOVE),
  )

  private fun detail(clip: ClipSummary, lastWatched: Instant?, counts: Map<String, Int>) = ClipDetail(
    summary = clip, durationS = clip.durationS, segments = 10, changeCounts = counts.mapKeys { ChangeLabel(it.key) }, rulesVersion = 3,
    phoneEngine = "timit61", dialogueSeparation = true, hasDialogueTrack = false, studyPackBytes = null, videoBytes = null,
    lastWatched = lastWatched, resume = null, computerError = null,
  )

  private fun savedItem(id: Long, kind: SavedKind) = SavedItem(
    id = id, kind = kind, word = null, short = ShortId(coffee.id, id.toInt()), change = null, savedAt = now, lastWatchedAt = null,
    clipTitle = coffee.title, available = true,
  )
}

/** [FakeClipRepository] that counts `clips()` calls and can fail them. */
class CountingClips(val fake: FakeClipRepository = FakeClipRepository()) : ClipRepository by fake {
  var calls = 0
    private set
  var failures = 0

  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> {
    calls++
    if (failures > 0) {
      failures--
      return flow { throw IllegalStateException("the catalogue is unreadable") }
    }
    return fake.clips(query)
  }
}

/** [FakeRequestRepository] whose `perform` waits for [gate]. */
class GatedRequests(private val fake: FakeRequestRepository) : RequestRepository by fake {
  val gate = CompletableDeferred<Unit>()

  override suspend fun perform(id: RequestId, action: RequestAction) {
    gate.await()
    fake.perform(id, action)
  }
}

/** [FakeSetRepository] that counts `ensureToday()` calls and can hold them until [gate] completes. */
class CountingSets(val fake: FakeSetRepository = FakeSetRepository()) : SetRepository by fake {
  var ensured = 0
    private set
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun ensureToday(): StudySet? {
    ensured++
    gate?.await()
    return fake.ensureToday()
  }
}
