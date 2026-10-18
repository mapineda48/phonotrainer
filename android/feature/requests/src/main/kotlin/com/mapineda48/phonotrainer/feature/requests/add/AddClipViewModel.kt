package com.mapineda48.phonotrainer.feature.requests.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.AddClipStart
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What "Add a clip" is doing (spec F-19, 8.4.1). */
@Immutable
internal sealed interface AddPhase {
  /** The learner fills in a link or chooses files. */
  data object Editing : AddPhase

  /** The tap was taken: the request is being stored (it is never left half-made). */
  data object Saving : AddPhase

  /** "You already added this link." with "Open it" / "Analyze again anyway" (SH-AC8). */
  data class DuplicateFound(val duplicate: Duplicate) : AddPhase

  /** Nothing could be added; [problem] says why. */
  data class Problem(val problem: AddProblem) : AddPhase

  /** A link was stored: [message] is current (spec 8.4 item 8). */
  data class LinkAdded(val id: RequestId, val message: DoneMessage) : AddPhase

  /** Files were stored: each with its own copy (F-19-AC3); [refusals] of [total] were not added. */
  data class FilesAdded(val files: List<AddedFile>, val total: Int, val refusals: List<FileRefusal>) : AddPhase
}

/** Why nothing was added. */
@Immutable
internal sealed interface AddProblem {
  /** "PhonoTrainer couldn't save this request: your phone is out of space." (architecture 8.1). */
  data object OutOfSpace : AddProblem

  /** Every chosen file was refused (spec 8.3): the reasons. */
  data class Refused(val total: Int, val refusals: List<FileRefusal>) : AddProblem
}

/** The message after a link is stored (spec 8.4 item 8). */
internal enum class DoneMessage { ADDED, SENDING, SENT, REFUSED }

/**
 * One added file and its own copy (F-19-AC3): "Preparing the file… 40 %", then "Added", or its failure. [name] and
 * [bytes] are its own request's, never a picked file's by position.
 */
@Immutable
internal data class AddedFile(val id: RequestId, val name: String?, val bytes: Long?, val progress: IntakeProgress?, val request: Request?) {
  /** Its copy is complete: the request is waiting or further along ("Added" only now). */
  val added: Boolean
    get() = request != null && request.state != RequestState.PREPARING && request.state != RequestState.COPY_FAILED &&
      request.state != RequestState.COPY_MISSING

  /** Its copy failed: the request's failure card shows instead. */
  val failed: Boolean get() = request?.state == RequestState.COPY_FAILED || request?.state == RequestState.COPY_MISSING
}

/** The in-context notification question of F-23 (asked once, after the first request is stored). */
internal enum class NotificationQuestion { NONE, ASKING, SYSTEM_PROMPT }

/** Everything "Add a clip" draws (spec F-19, design 10.2 P-05). */
@Immutable
internal data class AddClipUiState(
  val link: String = "",
  val parsed: ParsedLink? = null,
  /** "That doesn't look like a link." under the field (F-19-AC2). */
  val linkError: Boolean = false,
  val files: List<PickedFile> = emptyList(),
  /** The chosen files' facts are being read. */
  val reading: Boolean = false,
  val audioOnly: Boolean = false,
  val separateDialogue: Boolean = true,
  val phase: AddPhase = AddPhase.Editing,
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val capabilities: Capabilities? = null,
  val question: NotificationQuestion = NotificationQuestion.NONE,
) {
  /** Files are chosen: they are what "Analyze" adds, and the link field steps aside. */
  val filesMode: Boolean get() = files.isNotEmpty()

  /** "Audio only" applies: always for a link, for files when one is a video. */
  val audioOnlyOffered: Boolean get() = if (filesMode) files.any { it.kind == PickedKind.VIDEO } else parsed != null

  /** A video above 500 MB: "Large file: sending takes a few minutes on Wi-Fi." (spec 8.3). */
  val largeFile: Boolean get() = files.any { it.kind == PickedKind.VIDEO && (it.bytes ?: 0) > LARGE_FILE_BYTES }

  /** "Analyze" when connected, "Add to queue" otherwise (spec F-19). */
  val analyzeNow: Boolean get() = status is ComputerStatus.Connected

  /** Something can be added now. */
  val canSubmit: Boolean
    get() = !reading && (phase == AddPhase.Editing || phase is AddPhase.Problem) && (filesMode || parsed != null)

  /** The upload limit, for "larger than your computer accepts ({limit})" (2 GiB when never connected). */
  val maxUploadBytes: Long get() = capabilities?.maxUploadBytes?.takeIf { it > 0 } ?: DEFAULT_MAX_UPLOAD_BYTES

  companion object {
    /** Above this, a video's "Audio only" is preselected (spec F-19, 8.3). */
    const val LARGE_FILE_BYTES: Long = 500L * 1024 * 1024

    /** The limit when the phone has never been connected (spec 8.3). */
    const val DEFAULT_MAX_UPLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** The pasted or typed text is read up to this many characters (architecture 12: 8 KiB of shared text). */
    const val MAX_TEXT_CHARS: Int = 8 * 1024
  }
}

/**
 * "Add a clip" (spec F-19; design 10.2 P-05): a link pasted or typed (the clipboard is read only when "Paste" is
 * tapped, by the screen, F-19-AC1), or files chosen in the system picker (each one request, with its own copy,
 * F-19-AC3); the options; and the same confirmation logic as the share sheet (spec 8.4): the request is stored first
 * (the store is never cancelled half-way), duplicates offer "Open it" / "Analyze again anyway", a link's message
 * follows it to "Sent to your computer.", each file reads "Added" only once its copy is complete, and the notification
 * question comes after the first stored request (F-23). The link text and the options survive a process death.
 */
@HiltViewModel(assistedFactory = AddClipViewModel.Factory::class)
internal class AddClipViewModel @AssistedInject constructor(
  @Assisted key: AddClipKey,
  private val requests: RequestRepository,
  private val parser: LinkParser,
  private val computer: ComputerStatusRepository,
  private val asker: NotificationPermissionAsker,
  private val picked: PickedFiles,
  private val saved: SavedStateHandle,
) : ViewModel() {
  private val mutable = MutableStateFlow(
    AddClipUiState(
      audioOnly = saved[AUDIO_ONLY] ?: false,
      separateDialogue = saved[SEPARATE] ?: true,
    ),
  )

  val state: StateFlow<AddClipUiState> = mutable.asStateFlow()

  /** The runtime permission "Notify me" asks for (null below Android 13). */
  val systemPermission: String? get() = asker.systemPermission

  /** "Choose a file" was opened by the destination itself (`AddClipStart.FILES`): the picker opens once. */
  val opensPickerFirst: Boolean = key.start == AddClipStart.FILES && saved.get<Boolean>(PICKER_OPENED) != true

  /** `AddClipStart.PASTE`: the link field takes the focus (the clipboard is still read only on "Paste"). */
  val focusesLink: Boolean = key.start == AddClipStart.PASTE

  private var watch: Job? = null

  init {
    saved.get<String>(LINK)?.let { setLink(it) }
    viewModelScope.launch { computer.status.collect { status -> mutable.update { it.copy(status = status) } } }
    viewModelScope.launch { computer.paired.collect { paired -> mutable.update { it.copy(capabilities = paired?.capabilities) } } }
  }

  /** The picker was opened by the destination (so a recreate does not open it again). */
  fun pickerOpened() {
    saved[PICKER_OPENED] = true
  }

  /** The link field changed: its first http(s) URL and site are read again (spec F-19). */
  fun setLink(text: String) {
    val value = text.take(AddClipUiState.MAX_TEXT_CHARS)
    saved[LINK] = value
    val parsed = value.takeIf { it.isNotBlank() }?.let { runCatching { parser.parse(it, null) }.getOrNull() }
    mutable.update {
      it.copy(
        link = value,
        parsed = parsed,
        linkError = parsed == null && value.isNotBlank() && !isLinkPrefix(value),
        phase = if (it.phase is AddPhase.DuplicateFound || it.phase is AddPhase.Problem) AddPhase.Editing else it.phase,
      )
    }
  }

  /**
   * "Paste" (F-19-AC1, AC2): the clipboard's text, read by the screen on this tap only. Its first http(s) URL fills the
   * field; without one the text stays visible with "That doesn't look like a link." and nothing can be added.
   */
  fun paste(text: String?) {
    val clip = text?.take(AddClipUiState.MAX_TEXT_CHARS).orEmpty()
    val parsed = clip.takeIf { it.isNotBlank() }?.let { runCatching { parser.parse(it, null) }.getOrNull() }
    val value = parsed?.shared ?: clip.trim()
    saved[LINK] = value
    mutable.update {
      it.copy(
        link = value,
        parsed = parsed,
        linkError = parsed == null,
        files = emptyList(),
        phase = AddPhase.Editing,
      )
    }
  }

  /** The picker answered with [uris] (up to 10 are added; `:core:requests` refuses the rest, spec 8.1). */
  fun pick(uris: List<String>) {
    if (uris.isEmpty()) return
    mutable.update { it.copy(reading = true, phase = AddPhase.Editing) }
    viewModelScope.launch {
      val files = runCatching { picked.inspect(uris) }.getOrElse { uris.map { uri -> PickedFile(uri, null, null, null, null, null) } }
      val large = files.any { it.kind == PickedKind.VIDEO && (it.bytes ?: 0) > AddClipUiState.LARGE_FILE_BYTES }
      saved[LINK] = ""
      mutable.update {
        it.copy(
          files = files,
          reading = false,
          link = "",
          parsed = null,
          linkError = false,
          audioOnly = if (large) true else it.audioOnly,
        )
      }
    }
  }

  /** "Paste a link instead": the chosen files are put aside. */
  fun clearFiles() {
    mutable.update { it.copy(files = emptyList(), phase = AddPhase.Editing) }
  }

  fun setAudioOnly(on: Boolean) {
    saved[AUDIO_ONLY] = on
    mutable.update { it.copy(audioOnly = on) }
  }

  fun setSeparateDialogue(on: Boolean) {
    saved[SEPARATE] = on
    mutable.update { it.copy(separateDialogue = on) }
  }

  /**
   * "Analyze" / "Add to queue" ([force]: "Analyze again anyway"): the request is stored first (spec 7.7) and the call
   * runs to its end even if the screen goes away meanwhile.
   */
  fun submit(force: Boolean = false) {
    val current = mutable.value
    if (!current.canSubmit && !(force && current.phase is AddPhase.DuplicateFound)) return
    mutable.update { it.copy(phase = AddPhase.Saving) }
    viewModelScope.launch {
      withContext(NonCancellable) { if (current.filesMode) addFiles(current) else addLink(current, force) }
    }
  }

  private suspend fun addLink(current: AddClipUiState, force: Boolean) {
    val input = LinkInput(current.link, null, audioOnly = current.audioOnly, force = force)
    when (val result = runCatching { requests.addLink(input) }.getOrNull()) {
      is AddResult.Added -> {
        stored()
        watchLink(result.id)
      }
      is AddResult.DuplicateFound -> mutable.update { it.copy(phase = AddPhase.DuplicateFound(result.duplicate)) }
      AddResult.NoLink -> mutable.update { it.copy(phase = AddPhase.Editing, linkError = true, parsed = null) }
      AddResult.OutOfSpace, null -> mutable.update { it.copy(phase = AddPhase.Problem(AddProblem.OutOfSpace)) }
    }
  }

  private suspend fun addFiles(current: AddClipUiState) {
    val sources = current.files.map { FileSource(it.uri, it.name, it.mimeType, it.bytes, persisted = true) }
    val result = runCatching {
      requests.addFiles(sources, FileOptions(audioOnly = current.audioOnly, separateDialogue = current.separateDialogue))
    }.getOrNull()
    if (result == null) {
      mutable.update { it.copy(phase = AddPhase.Problem(AddProblem.OutOfSpace)) }
      return
    }
    if (result.added.isEmpty()) {
      mutable.update { it.copy(phase = AddPhase.Problem(AddProblem.Refused(sources.size, result.refused))) }
      return
    }
    stored()
    watchFiles(result.added, sources.size, result.refused)
  }

  /** The first request is safely stored: the notification question of F-23 comes now, once. */
  private fun stored() {
    if (asker.shouldAsk.value) mutable.update { it.copy(question = NotificationQuestion.ASKING) }
  }

  private fun watchLink(id: RequestId) {
    watch?.cancel()
    watch = viewModelScope.launch {
      combine(requests.request(id), computer.status) { request, status -> messageOf(request, status) }
        .distinctUntilChanged()
        .collect { message -> mutable.update { it.copy(phase = AddPhase.LinkAdded(id, message)) } }
    }
  }

  /**
   * Each added request shows its own name and size, read from the request itself (as the share sheet does): the
   * intake skips refused and repeated sources, so [added] is not aligned with the picked files (SH-AC14, F-19-AC3).
   */
  private fun watchFiles(added: List<RequestId>, total: Int, refusals: List<FileRefusal>) {
    watch?.cancel()
    val each: List<Flow<AddedFile>> = added.map { id ->
      combine(
        requests.intake(id).onStart<IntakeProgress?> { emit(null) },
        requests.request(id),
      ) { progress, request -> AddedFile(id, request?.fileName, request?.fileBytes, progress, request) }
    }
    watch = viewModelScope.launch {
      (if (each.isEmpty()) flowOf(emptyArray<AddedFile>()) else combine(each) { it })
        .collect { files -> mutable.update { it.copy(phase = AddPhase.FilesAdded(files.toList(), total, refusals)) } }
    }
  }

  private fun messageOf(request: Request?, status: ComputerStatus): DoneMessage = when {
    request == null -> DoneMessage.ADDED
    request.state in ACCEPTED -> DoneMessage.SENT
    request.state == RequestState.NEEDS_ATTENTION -> DoneMessage.REFUSED
    status is ComputerStatus.Connected -> DoneMessage.SENDING
    else -> DoneMessage.ADDED
  }

  /** An action on an added file's request (its failure card: "Try audio only", "Remove"). */
  fun perform(id: RequestId, action: RequestAction) {
    viewModelScope.launch { withContext(NonCancellable) { requests.perform(id, action) } }
  }

  /** "Add another clip": the screen starts again, empty. */
  fun another() {
    watch?.cancel()
    saved[LINK] = ""
    mutable.update {
      it.copy(link = "", parsed = null, linkError = false, files = emptyList(), reading = false, phase = AddPhase.Editing)
    }
  }

  /** "Not now", or the question dismissed (which counts as "Not now"). */
  fun notNow() {
    mutable.update { it.copy(question = NotificationQuestion.NONE) }
    viewModelScope.launch { asker.notNow() }
  }

  /** "Notify me": true when the caller must now launch the system prompt for [systemPermission]. */
  suspend fun notifyMe(): Boolean {
    val prompt = asker.notifyMe() && asker.systemPermission != null
    mutable.update { it.copy(question = if (prompt) NotificationQuestion.SYSTEM_PROMPT else NotificationQuestion.NONE) }
    return prompt
  }

  /** The system prompt answered. */
  fun onSystemPromptResult(granted: Boolean) {
    asker.onSystemPromptResult(granted)
    mutable.update { it.copy(question = NotificationQuestion.NONE) }
  }

  @AssistedFactory
  interface Factory {
    fun create(key: AddClipKey): AddClipViewModel
  }

  companion object {
    private const val LINK = "add.link"
    private const val AUDIO_ONLY = "add.audio_only"
    private const val SEPARATE = "add.separate"
    private const val PICKER_OPENED = "add.picker_opened"

    private val ACCEPTED = setOf(
      RequestState.SENT,
      RequestState.IN_LINE,
      RequestState.ANALYZING,
      RequestState.READY_ON_COMPUTER,
      RequestState.GETTING_ON_PHONE,
      RequestState.ON_PHONE,
    )

    /** While the learner is still typing "https://", no error shows yet. */
    fun isLinkPrefix(text: String): Boolean {
      val typed = text.trim().lowercase()
      return "https://".startsWith(typed) || "http://".startsWith(typed)
    }
  }
}
