package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.Appearance
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.EndOfShort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LessonGuidance
import com.mapineda48.phonotrainer.core.domain.model.MotionChoice
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A clip as the "Clips in the Mix" sheet lists it. */
@Immutable
data class MixClip(val id: JobId, val title: String, val inMix: Boolean)

/** What the storage row says: "1.4 GB of 2 GB". */
@Immutable
data class StorageLine(val usedBytes: Long, val budget: StorageBudget)

/** The Settings page (spec F-22, design 10.2 P-12): every choice, and the status lines of its five sub-page rows. */
@Immutable
data class SettingsUiState(
  val settings: UserSettings = UserSettings(),
  val computer: ComputerStatus = ComputerStatus.NotConnected,
  val sync: SyncState = SyncState.Idle,
  val storage: StorageLine? = null,
  val notifications: NotificationPermission = NotificationPermission.NOT_NEEDED,
  val clips: List<MixClip> = emptyList(),
) {
  /** How many of the clips on the list are left out of the Mix. */
  val leftOut: Int get() = clips.count { !it.inMix }
}

/** The speeds of "Default speed" (spec F-07 item 5: 0.5×, 0.75× and 1× keep the pitch). */
internal val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f)

/**
 * The Settings page (spec F-22): the learner's choices from [SettingsRepository], each applied at once and remembered
 * on this phone, and the status lines of Your computer, Storage and Notifications from their repositories. A source
 * that fails leaves its line out; the choices never depend on it.
 */
@HiltViewModel
internal class SettingsViewModel @Inject constructor(
  private val repository: SettingsRepository,
  computer: ComputerStatusRepository,
  sync: SyncController,
  storage: StorageRepository,
  clips: ClipRepository,
  notifier: Notifier,
) : ViewModel() {
  private val storageLine: Flow<StorageLine?> = storage.usage
    .map<_, StorageLine?> { StorageLine(it.clipsBytes + it.videosBytes + it.waitingCopiesBytes + it.cacheBytes, it.budget) }
    .catch { if (it is CancellationException) throw it else emit(null) }
    .onStart { emit(null) }

  private val allClips = clips.clips(ClipQuery())
    .catch { if (it is CancellationException) throw it else emit(emptyList()) }
    .onStart { emit(emptyList()) }

  private val connection = combine(computer.status, sync.state) { status, syncState -> status to syncState }

  val state: StateFlow<SettingsUiState> = combine(
    repository.settings,
    connection,
    storageLine,
    notifier.permissionState,
    allClips,
  ) { settings, (status, syncState), line, permission, list ->
    SettingsUiState(
      settings = settings,
      computer = status,
      sync = syncState,
      storage = line,
      notifications = permission,
      clips = list.map { MixClip(it.id, it.title, it.id !in settings.excludedFromMix) },
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(settings = repository.settings.value))

  fun setAppearance(value: Appearance) = change { it.copy(appearance = value) }

  fun setColors(value: ColorChoice) = change { it.copy(colors = value) }

  fun setPatterns(value: PatternChoice) = change { it.copy(patterns = value) }

  fun setMotion(value: MotionChoice) = change { it.copy(motion = value) }

  fun setSpeed(value: Float) = change { it.copy(defaultSpeed = value) }

  fun setPlayEach(value: Boolean) = change { it.copy(playEachShort = value) }

  fun setEndOfShort(value: EndOfShort) = change { it.copy(endOfShort = value) }

  fun setCaptions(value: CaptionMode) = change { it.copy(captions = value) }

  fun setListenFirst(value: Boolean) = change { it.copy(listenFirst = value) }

  fun setTrack(value: Track) = change { it.copy(track = value) }

  /** "Clips in the Mix": [inMix] false leaves the clip out of the Mix and of new sets (spec F-06). */
  fun setInMix(id: JobId, inMix: Boolean) = change {
    it.copy(excludedFromMix = if (inMix) it.excludedFromMix - id else it.excludedFromMix + id)
  }

  fun setQuestions(value: Boolean) = change { it.copy(questionsAfterSet = value) }

  fun setGuidance(value: LessonGuidance) = change { it.copy(lessonGuidance = value) }

  private fun change(edit: (UserSettings) -> UserSettings) {
    viewModelScope.launch {
      try {
        repository.update(edit)
      } catch (failure: Exception) {
        // The store could not write: the page keeps showing what the store holds.
        if (failure is CancellationException) throw failure
      }
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
