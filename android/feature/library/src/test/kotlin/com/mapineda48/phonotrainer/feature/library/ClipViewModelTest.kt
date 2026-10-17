package com.mapineda48.phonotrainer.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeProgressRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import kotlin.reflect.KClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Clip page's ViewModel over the settable fakes of work-plan 4.6 (spec F-05 v1-core). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val id = LibrarySamples.coffee.id
  private val clips = FakeClipRepository()
  private val shorts = FakeShortRepository()
  private val content = FakeClipContentRepository()
  private val progress = FakeProgressRepository()
  private val requests = FakeRequestRepository()
  private val reference = FakeReferenceRepository()
  private val status = FakeComputerStatusRepository(ComputerStatus.Connected("Desk PC", 0, 0, false))
  private val store = ViewModelStore()

  /** A view model in a store the test can clear (onCleared), its state collected for the test's duration. */
  private fun TestScope.page(key: ClipKey = ClipKey(id), saved: SavedStateHandle = SavedStateHandle()): ClipViewModel {
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : androidx.lifecycle.ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
        ClipViewModel(key, clips, shorts, content, progress, requests, reference, status, TestClock(LibrarySamples.now), saved) as T
    }
    val vm = ViewModelProvider.create(store, factory)[key.jobId.value, ClipViewModel::class]
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return vm
  }

  @Test
  fun thePageHoldsALeaseWhileShownAndMarksTheClipOpened() = runTest {
    clips.details.value = mapOf(id to LibrarySamples.detail())
    page()
    assertThat(content.leaseCount(id)).isEqualTo(1)
    assertThat(progress.opened).containsExactly(id)
    store.clear()
    assertThat(content.leaseCount(id)).isEqualTo(0)
  }

  @Test
  fun anUnknownClipIsGoneAndAClipDeletedWhileOpenStaysWithItsBanner() = runTest {
    val gone = page(ClipKey(JobId("20250101-000000-dead")))
    assertThat(gone.state.value.presence).isEqualTo(ClipPresence.Gone)

    clips.details.value = mapOf(id to LibrarySamples.detail())
    val open = page()
    assertThat(open.state.value.presence).isEqualTo(ClipPresence.Known(LibrarySamples.detail()))
    clips.details.value = emptyMap()
    assertThat(open.state.value.presence).isEqualTo(ClipPresence.Known(LibrarySamples.detail(), deleted = true))
  }

  @Test
  fun theShortsTabNamesEachSpotlightWithItsWordsAndMarksSeenAndNext() = runTest {
    clips.details.value = mapOf(id to LibrarySamples.detail())
    shorts.shortsById.value = mapOf(id to LibrarySamples.shorts.reversed())
    content.data[id] = LibraryFixtures.clipData(
      id,
      listOf("so", "we", "can", "pick", "it", "up"),
      emptyList(),
      listOf("want", "to", "go"),
      emptyList(),
      listOf("a", "bit", "better"),
      emptyList(),
      listOf("did", "you", "see"),
    )
    progress.seenState.value = setOf(LibrarySamples.shorts[0].id)
    progress.resumeById.value = mapOf(id to LibrarySamples.shorts[3].id)
    val vm = page()

    val rows = (vm.state.value.shorts as ShortsState.Ready).rows
    assertThat(rows.map { Triple(it.short.ordinal, it.changeName, it.words) }).containsExactly(
      Triple(1, "Linking", "pick it"),
      Triple(2, "Vowel reduction", "to"),
      Triple(3, "Flapping", "better"),
      Triple(4, "Palatalization", "did you"),
      Triple(5, null, null),
    ).inOrder()
    assertThat(rows.map { it.family }).containsExactly(Family.BOUNDARY, Family.REDUCTION, Family.TD, Family.ASSIMILATION, null).inOrder()
    assertThat(rows.map { it.seen }).containsExactly(true, false, false, false, false).inOrder()
    assertThat(rows.map { it.next }).containsExactly(false, false, false, true, false).inOrder()
    assertThat(vm.state.value.resume?.ordinal).isEqualTo(4)
  }

  @Test
  fun withoutReadableClipDataTheShortsStillListTheirChange() = runTest {
    clips.details.value = mapOf(id to LibrarySamples.detail())
    shorts.shortsById.value = mapOf(id to LibrarySamples.shorts)
    val vm = page()
    val rows = (vm.state.value.shorts as ShortsState.Ready).rows
    assertThat(rows.first().changeName).isEqualTo("Linking")
    assertThat(rows.first().words).isNull()
  }

  @Test
  fun aClipNotOnThisPhoneSaysTheTranscriptComesWithIt() = runTest {
    clips.details.value = mapOf(LibrarySamples.lake.id to LibrarySamples.detail(LibrarySamples.lake))
    val vm = page(ClipKey(LibrarySamples.lake.id))
    assertThat(vm.state.value.shorts).isEqualTo(ShortsState.NotHere)
  }

  @Test
  fun changesInThisClipAreTheMostFrequentFirst() = runTest {
    clips.details.value = mapOf(id to LibrarySamples.detail())
    val vm = page()
    assertThat(vm.state.value.changes.map { it.label.value to it.count })
      .containsExactly("flapping" to 12, "vowel_reduction" to 9, "linking" to 7, "palatalization" to 3).inOrder()
    val flapping = vm.state.value.changes.first()
    assertThat(flapping.name).isEqualTo("Flapping")
    assertThat(flapping.family).isEqualTo(Family.TD)
    assertThat(flapping.advice).isEqualTo(reference.reference.value.practice[ChangeLabel("flapping")]?.advice ?: Advice.UNDERSTAND)
  }

  @Test
  fun theTabIsTheKeysAtFirstAndThenTheLearnersChoice() = runTest {
    clips.details.value = mapOf(id to LibrarySamples.detail())
    val saved = SavedStateHandle()
    val vm = page(ClipKey(id, ClipTab.SUMMARY), saved)
    assertThat(vm.state.value.tab).isEqualTo(ClipTab.SUMMARY)
    vm.selectTab(ClipTab.SHORTS)
    assertThat(vm.state.value.tab).isEqualTo(ClipTab.SHORTS)
    assertThat(saved.get<String>("tab")).isEqualTo("SHORTS")
  }

  @Test
  fun downloadWhenConnectedMarksTheClipWanted() = runTest {
    // F-05-AC4: marked wanted at once; the sync downloads it the next time the computer is reachable.
    status.statusState.value = ComputerStatus.Offline
    clips.details.value = mapOf(LibrarySamples.lake.id to LibrarySamples.detail(LibrarySamples.lake))
    val vm = page(ClipKey(LibrarySamples.lake.id))
    assertThat(vm.state.value.connected).isFalse()
    vm.download()
    assertThat(clips.actions).containsExactly("downloadToPhone:${LibrarySamples.lake.id.value}")
    assertThat(vm.state.value.wanted).isTrue()
    assertThat(ClipRules.notices(vm.state.value.detail!!, vm.state.value.wanted)).containsExactly(ClipNotice.WANTED)
  }

  @Test
  fun removingFromThePhoneSendsNothingToTheComputer() = runTest {
    // F-05-AC11.
    clips.details.value = mapOf(id to LibrarySamples.detail())
    val vm = page()
    vm.removeFromPhone()
    vm.setKept(false)
    vm.downloadVideo()
    assertThat(clips.actions).containsExactly(
      "removeFromPhone:${id.value}",
      "setKept:${id.value}:false",
      "downloadVideo:${id.value}",
    ).inOrder()
    assertThat(requests.performed).isEmpty()
  }

  @Test
  fun aClipStillAnalyzedShowsThisPhonesRequest() = runTest {
    val analyzing = LibrarySamples.analyzing.copy(jobId = id)
    requests.all.value = listOf(LibrarySamples.request("other", RequestState.WAITING, "other"), analyzing)
    clips.details.value = mapOf(id to LibrarySamples.processing.detail!!)
    val vm = page()
    assertThat(ClipRules.processing(vm.state.value.detail!!)).isTrue()
    assertThat(vm.state.value.request).isEqualTo(analyzing)
    clips.details.value = mapOf(id to LibrarySamples.detail())
    assertThat(ClipRules.processing(vm.state.value.detail!!)).isFalse()
  }

  @Test
  fun theHeaderNoticesFollowTheClipsState() = runTest {
    val detail = LibrarySamples.detail()
    assertThat(ClipRules.notices(detail, wanted = false)).isEmpty()
    assertThat(ClipRules.playable(detail)).isTrue()
    assertThat(ClipRules.notices(LibrarySamples.detail(LibrarySamples.lake), wanted = false)).containsExactly(ClipNotice.NOT_HERE)
    assertThat(ClipRules.notices(LibrarySamples.detail(LibrarySamples.market), wanted = false)).containsExactly(ClipNotice.PAUSED)
    val again = LibrarySamples.coffee.copy(availability = ClipAvailability.NeedsDownloadingAgain, olderRules = true,
      video = com.mapineda48.phonotrainer.core.domain.model.VideoAvailability.NotAvailableFromComputer)
    assertThat(ClipRules.notices(LibrarySamples.detail(again), wanted = false))
      .containsExactly(ClipNotice.NEEDS_AGAIN, ClipNotice.VIDEO_NOT_AVAILABLE, ClipNotice.OLDER_RULES).inOrder()
    val noAudio = LibrarySamples.noAudio.detail!!
    assertThat(ClipRules.notices(noAudio, wanted = false)).containsExactly(ClipNotice.NO_AUDIO)
    assertThat(ClipRules.playable(noAudio)).isFalse()
    assertThat(ClipRules.audioPercent(LibrarySamples.bringing.detail!!)).isEqualTo(40)
  }

  @Test
  fun onlyWebAddressesOpenAndEnginesReadAsTheWebNamesThem() {
    assertThat(ClipRules.openable("https://www.youtube.com/watch?v=example")).isTrue()
    assertThat(ClipRules.openable(" HTTP://example.com/a ")).isTrue()
    assertThat(ClipRules.openable("javascript:alert(1)")).isFalse()
    assertThat(ClipRules.openable("intent://scan/#Intent;end")).isFalse()
    assertThat(ClipRules.openable("file:///sdcard/a.mp4")).isFalse()
    assertThat(ClipRules.openable(null)).isFalse()
    assertThat(ClipRules.engineLabel("timit61")).isEqualTo("TIMIT-61")
    assertThat(ClipRules.engineLabel("wav2vec2")).isEqualTo("espeak")
    assertThat(ClipRules.engineLabel(null)).isNull()
  }
}
