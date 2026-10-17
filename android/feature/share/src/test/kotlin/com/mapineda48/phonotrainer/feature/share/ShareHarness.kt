package com.mapineda48.phonotrainer.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeNotificationPermissionAsker
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeLinkParser
import com.mapineda48.phonotrainer.core.testing.fake.requests.FakeRequestRepository
import com.mapineda48.phonotrainer.feature.share.intake.SharedFiles
import com.mapineda48.phonotrainer.feature.share.intake.SharedItem
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** A probe the test answers by hand ([answer]); [asked] counts the probes. Everything else is the settable fake. */
class ScriptedStatus(val fake: FakeComputerStatusRepository = FakeComputerStatusRepository()) :
  ComputerStatusRepository by fake {
  var answer = CompletableDeferred<ComputerStatus>()
  var asked = 0
    private set

  override suspend fun checkNow(): ComputerStatus {
    asked++
    return answer.await()
  }
}

/** [SharedFiles] whose items the test lists; [inspected] records each call. */
class FakeSharedFiles(var items: (List<String>) -> List<SharedItem> = { uris -> uris.map(::video) }) : SharedFiles {
  val inspected = mutableListOf<List<String>>()

  override suspend fun inspect(uris: List<String>, intentType: String?): List<SharedItem> {
    inspected += uris
    return items(uris)
  }

  companion object {
    fun video(uri: String, bytes: Long? = 48_000_000, name: String = "${uri.substringAfterLast('/')}.mp4") =
      SharedItem(uri, name, "video/mp4", bytes, 42_000, SharedKind.VIDEO)

    fun audio(uri: String, name: String = "${uri.substringAfterLast('/')}.m4a") =
      SharedItem(uri, name, "audio/mp4", 2_000_000, 30_000, SharedKind.AUDIO)

    fun amr(uri: String, name: String = "voice.amr") =
      SharedItem(uri, name, "audio/amr", 30_000, null, SharedKind.AUDIO, refused = FileRefusalReason.UNSUPPORTED_TYPE)
  }
}

/** [FakeRequestRepository] that also records the options of each `addFiles`. */
class RecordingRequests(private val fake: FakeRequestRepository) : RequestRepository by fake {
  val fileOptions = mutableListOf<FileOptions>()

  override suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult {
    fileOptions += options
    return fake.addFiles(files, options)
  }
}

/** The share sheet's view model over the settable fakes of work-plan 4.6, in a store the test can clear. */
class ShareHarness {
  val requests = FakeRequestRepository()
  private val recording = RecordingRequests(requests)

  /** The options of every `addFiles` call, in order. */
  val fileOptions: List<FileOptions> get() = recording.fileOptions
  val parser = FakeLinkParser()
  val status = ScriptedStatus()
  val asker = FakeNotificationPermissionAsker(shouldAsk = false)
  val files = FakeSharedFiles()
  val saved = SavedStateHandle()
  private val store = ViewModelStore()

  val viewModel: ShareViewModel by lazy {
    val factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : androidx.lifecycle.ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
        ShareViewModel(recording, parser, status, asker, files, saved) as T
    }
    ViewModelProvider.create(store, factory)[ShareViewModel::class]
  }

  val state: ShareUiState get() = viewModel.state.value

  /** The activity is destroyed for good: `onCleared`. */
  fun destroy() = store.clear()

  /** A request row as `:core:requests` reports it. */
  fun request(
    id: RequestId,
    state: RequestState,
    kind: RequestKind = RequestKind.FILE,
    actions: Set<RequestAction> = emptySet(),
    error: RequestError? = null,
  ) = Request(
    id = id,
    kind = kind,
    state = state,
    title = id.value,
    site = null,
    link = null,
    fileName = "${id.value}.mp4",
    fileBytes = 48_000_000,
    createdAt = Instant.EPOCH,
    progress = null,
    lastError = error,
    jobId = null,
    actions = actions,
  )

  /** Puts [request] in the fake's list (replacing one with its id). */
  fun put(request: Request) {
    requests.all.value = requests.all.value.filterNot { it.id == request.id } + request
  }

  /** The copy progress the fake's `intake(id)` reports. */
  fun intake(id: RequestId): MutableStateFlow<IntakeProgress> =
    MutableStateFlow(IntakeProgress(id, 0, 48_000_000, done = false, failed = null)).also { requests.intakes[id] = it }
}
