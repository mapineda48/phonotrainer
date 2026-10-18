package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.model.ArtifactProgress
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSyncTrigger
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeStorageRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Storage's state and actions (spec F-21, F-05-AC11): every action stays on this phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val storage = FakeStorageRepository()
  private val settings = FakeSettingsRepository()
  private val clips = FakeClipRepository()
  private val trigger = FakeSyncTrigger()
  private val sync = FakeSyncController()
  private val computer = FakeComputerStatusRepository()

  private fun viewModel(repository: StorageRepository = storage) = StorageViewModel(repository, settings, clips, trigger, sync, computer)

  private fun detail(id: JobId, artifacts: List<ArtifactProgress>) = ClipDetail(
    summary = ClipSummary(
      id = id,
      title = "A walk in the park",
      durationS = 60.0,
      words = 100,
      changeCount = 10,
      source = ClipSource(SourceKind.UPLOAD, null, null, "park.mp4"),
      availability = ClipAvailability.Bringing(60),
      video = VideoAvailability.NotOnThisPhone,
      kept = false,
      origin = ClipOrigin.ACTIVE,
      olderRules = false,
      shortsSeen = 0,
      shortsTotal = 10,
      addedAt = Instant.EPOCH,
    ),
    durationS = 60.0,
    segments = 10,
    changeCounts = emptyMap(),
    rulesVersion = null,
    phoneEngine = null,
    dialogueSeparation = null,
    hasDialogueTrack = false,
    studyPackBytes = null,
    videoBytes = null,
    lastWatched = null,
    resume = null,
    computerError = null,
    artifacts = artifacts,
  )

  private fun TestScope.subscribe(viewModel: StorageViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
  }

  @Test
  fun theStateGathersTheUsageTheVideosSwitchAPauseAndTheBackgroundRestriction() = runTest {
    storage.usageState.value = SettingsSamples.usage
    settings.state.value = settings.state.value.copy(downloadVideos = false)
    sync.stateFlow.value = SyncState.Paused(PauseReason.NOT_CONNECTED)
    computer.restricted.value = true
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(state.usage).isEqualTo(SettingsSamples.usage)
    assertThat(state.downloadVideos).isFalse()
    assertThat(state.paused).isEqualTo(PauseReason.NOT_CONNECTED)
    assertThat(state.backgroundRestricted).isTrue()

    sync.stateFlow.value = SyncState.Idle
    assertThat(viewModel.state.value.paused).isNull()
  }

  @Test
  fun theBudgetGoesToTheStorageRepository() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setBudget(StorageBudget.FreeSpaceOnly)

    assertThat(storage.usageState.value.budget).isEqualTo(StorageBudget.FreeSpaceOnly)
    assertThat(viewModel.state.value.usage?.budget).isEqualTo(StorageBudget.FreeSpaceOnly)
  }

  @Test
  fun turningVideosOnAsksForASyncAndTurningThemOffDoesNot() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setDownloadVideos(false)
    assertThat(settings.state.value.downloadVideos).isFalse()
    assertThat(trigger.reasons).isEmpty()

    viewModel.setDownloadVideos(true)
    assertThat(settings.state.value.downloadVideos).isTrue()
    assertThat(trigger.reasons).containsExactly(SyncReason.SYNC_NOW)
  }

  @Test
  fun theClipActionsAreLocalRemovalsAndKeeps() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.removeVideo(SettingsSamples.park)
    viewModel.setKept(SettingsSamples.coffee, false)
    viewModel.removeFromPhone(SettingsSamples.coffee)
    viewModel.removeAllVideos()

    assertThat(clips.actions).containsExactly(
      "removeVideo:${SettingsSamples.park.value}",
      "setKept:${SettingsSamples.coffee.value}:false",
      "removeFromPhone:${SettingsSamples.coffee.value}",
    ).inOrder()
    assertThat(storage.videosRemoved).isEqualTo(1)
    // Nothing of this reaches the computer: no sync is asked for (F-05-AC11).
    assertThat(trigger.reasons).isEmpty()
    assertThat(sync.reasons).isEmpty()
    assertThat(viewModel.state.value.busy).isEmpty()
  }

  @Test
  fun eachListedClipCarriesItsFilesAndTryAgainAsksForTheClipAgain() = runTest {
    storage.usageState.value = SettingsSamples.usage
    clips.details.value = mapOf(SettingsSamples.park to detail(SettingsSamples.park, SettingsSamples.parkArtifacts))
    val viewModel = viewModel()
    subscribe(viewModel)

    val files = viewModel.state.value.artifacts
    assertThat(files[SettingsSamples.park]).isEqualTo(SettingsSamples.parkArtifacts)
    // A clip without a detail has no files to show.
    assertThat(files[SettingsSamples.coffee]).isEmpty()

    viewModel.downloadAgain(SettingsSamples.park)
    assertThat(clips.actions).containsExactly("downloadToPhone:${SettingsSamples.park.value}")
  }

  @Test
  fun anUnreadableStorageFailsAndTryAgainReadsItAgain() = runTest {
    var fail = true
    val flaky = object : StorageRepository by storage {
      override val usage: Flow<StorageUsage> = flow {
        if (fail) throw IllegalStateException("the disk is unreadable")
        emit(SettingsSamples.usage)
      }
    }
    val viewModel = viewModel(flaky)
    subscribe(viewModel)
    assertThat(viewModel.state.value.read).isEqualTo(StorageRead.Failed)

    fail = false
    viewModel.retry()
    assertThat(viewModel.state.value.read).isEqualTo(StorageRead.Ready(SettingsSamples.usage))
  }
}
