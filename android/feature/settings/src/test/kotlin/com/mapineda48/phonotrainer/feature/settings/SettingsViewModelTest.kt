package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.FakeNotifier
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
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

/** The Settings page's state and choices (spec F-22): every choice is written at once to the settings store. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val settings = FakeSettingsRepository()
  private val computer = FakeComputerStatusRepository()
  private val sync = FakeSyncController()
  private val storage = FakeStorageRepository()
  private val clips = FakeClipRepository()
  private val notifier = FakeNotifier(NotificationPermission.DENIED)

  private fun viewModel(storageRepository: StorageRepository = storage) =
    SettingsViewModel(settings, computer, sync, storageRepository, clips, notifier)

  private fun clip(id: String, title: String) = ClipSummary(
    id = JobId(id),
    title = title,
    durationS = 60.0,
    words = 100,
    changeCount = 10,
    source = ClipSource(SourceKind.UPLOAD, null, null, "$id.mp4"),
    availability = ClipAvailability.OnThisPhone,
    video = VideoAvailability.OnThisPhone,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 0,
    shortsTotal = 10,
    addedAt = Instant.EPOCH,
  )

  private fun TestScope.subscribe(viewModel: SettingsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
  }

  @Test
  fun theStateGathersTheChoicesAndTheStatusOfEachSubPage() = runTest {
    computer.statusState.value = ComputerStatus.Connected("Study desk", 0, 0, false)
    storage.usageState.value = StorageUsage(100, 200, 300, 400, StorageBudget.Limit(1_000_000_000), 0, 0, false, false, emptyList())
    clips.all.value = listOf(clip("a", "Coffee"), clip("b", "Park"))
    settings.state.value = UserSettings(excludedFromMix = setOf(JobId("b"), JobId("gone")))
    val viewModel = viewModel()
    subscribe(viewModel)

    val state = viewModel.state.value
    assertThat(state.computer).isEqualTo(ComputerStatus.Connected("Study desk", 0, 0, false))
    assertThat(state.storage).isEqualTo(StorageLine(1_000, StorageBudget.Limit(1_000_000_000)))
    assertThat(state.notifications).isEqualTo(NotificationPermission.DENIED)
    assertThat(state.clips).containsExactly(MixClip(JobId("a"), "Coffee", true), MixClip(JobId("b"), "Park", false)).inOrder()
    // A clip no longer on the list is not counted.
    assertThat(state.leftOut).isEqualTo(1)
  }

  @Test
  fun everyChoiceIsWrittenToTheStoreAtOnce() = runTest {
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setAppearance(Appearance.DARK)
    viewModel.setColors(ColorChoice.COLOR_VISION_FRIENDLY)
    viewModel.setPatterns(PatternChoice.OFF)
    viewModel.setMotion(MotionChoice.REDUCE)
    viewModel.setSpeed(0.5f)
    viewModel.setPlayEach(false)
    viewModel.setEndOfShort(EndOfShort.NEXT)
    viewModel.setCaptions(CaptionMode.PLAIN)
    viewModel.setListenFirst(true)
    viewModel.setTrack(Track.DIALOGUE)
    viewModel.setQuestions(false)
    viewModel.setGuidance(LessonGuidance.COMPACT)

    assertThat(settings.state.value).isEqualTo(
      UserSettings(
        appearance = Appearance.DARK,
        colors = ColorChoice.COLOR_VISION_FRIENDLY,
        patterns = PatternChoice.OFF,
        motion = MotionChoice.REDUCE,
        defaultSpeed = 0.5f,
        playEachShort = false,
        endOfShort = EndOfShort.NEXT,
        captions = CaptionMode.PLAIN,
        listenFirst = true,
        track = Track.DIALOGUE,
        questionsAfterSet = false,
        lessonGuidance = LessonGuidance.COMPACT,
      ),
    )
    assertThat(viewModel.state.value.settings).isEqualTo(settings.state.value)
  }

  @Test
  fun aClipLeavesTheMixAndComesBack() = runTest {
    clips.all.value = listOf(clip("a", "Coffee"))
    val viewModel = viewModel()
    subscribe(viewModel)

    viewModel.setInMix(JobId("a"), inMix = false)
    assertThat(settings.state.value.excludedFromMix).containsExactly(JobId("a"))
    assertThat(viewModel.state.value.clips.single().inMix).isFalse()

    viewModel.setInMix(JobId("a"), inMix = true)
    assertThat(settings.state.value.excludedFromMix).isEmpty()
    assertThat(viewModel.state.value.leftOut).isEqualTo(0)
  }

  @Test
  fun aStorageThatCannotBeMeasuredLeavesItsLineOutAndTheChoicesStay() = runTest {
    val broken = object : StorageRepository by storage {
      override val usage: Flow<StorageUsage> = flow { throw IllegalStateException("the disk is unreadable") }
    }
    val viewModel = viewModel(broken)
    subscribe(viewModel)
    viewModel.setCaptions(CaptionMode.OFF)

    assertThat(viewModel.state.value.storage).isNull()
    assertThat(viewModel.state.value.settings.captions).isEqualTo(CaptionMode.OFF)
  }
}
