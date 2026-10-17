package com.mapineda48.phonotrainer.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.api.NotificationPermissionAsker
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.feature.share.intake.SharedFiles
import com.mapineda48.phonotrainer.feature.share.intake.SharedInput
import com.mapineda48.phonotrainer.feature.share.intake.SharedItem
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The share sheet's logic (spec 8.4, architecture 12): a fresh probe that the sheet waits for at most 2 s; the link
 * parsed and checked for duplicates, or the files' facts read and the unsupported ones named, when the sheet opens; the
 * request stored first on "Analyze" / "Add to queue" (never cancelled half-way by the sheet closing); the copies
 * followed to "Added"; the notification question after the first stored request (F-23); and the sources held for the
 * sheet's life (`RequestRepository.holdSources`, closed in [onCleared], i.e. when the activity is destroyed for good),
 * so "Try audio only" can read a shared video again after its copy was refused for space (D-49, SH-AC15).
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
  private val requests: RequestRepository,
  private val parser: LinkParser,
  private val computer: ComputerStatusRepository,
  private val asker: NotificationPermissionAsker,
  private val files: SharedFiles,
  private val saved: SavedStateHandle,
) : ViewModel() {
  private val mutable = MutableStateFlow(ShareUiState())

  val state: StateFlow<ShareUiState> = mutable.asStateFlow()

  /** The runtime permission "Notify me" asks for (null below Android 13). */
  val systemPermission: String? get() = asker.systemPermission

  private var started = false
  private var text: SharedInput.Text? = null
  private var accepted: List<SharedItem> = emptyList()
  private var sheetRefusals: List<FileRefusal> = emptyList()
  private var total = 0
  private var hold: AutoCloseable? = null
  private var watch: Job? = null

  /** TalkBack, as the sheet's composition reports it; assumed on until it does (A-08: never close too early). */
  private val talkBack = MutableStateFlow(true)

  init {
    // Closes by itself AUTO_CLOSE_MS after the request is stored (and copied), never with TalkBack on and never while
    // the notification question or the system dialog is open; the count starts again once answered (8.4 item 8, D-51).
    viewModelScope.launch {
      combine(mutable, talkBack) { state, on -> state.mayCloseByItself && !on }
        .distinctUntilChanged()
        .collectLatest { may ->
          if (may) {
            delay(AUTO_CLOSE_MS)
            close()
          }
        }
    }
    viewModelScope.launch { computer.status.collect { status -> mutable.update { it.copy(status = status) } } }
    viewModelScope.launch {
      computer.paired.collect { paired ->
        val capabilities = paired?.capabilities
        mutable.update {
          it.copy(
            maxUploadBytes = capabilities?.maxUploadBytes?.takeIf { bytes -> bytes > 0 }
              ?: ShareUiState.DEFAULT_MAX_UPLOAD_BYTES,
            maxLinkDurationS = capabilities?.maxLinkDurationS?.takeIf { seconds -> seconds > 0 },
          )
        }
      }
    }
  }

  /** Starts the sheet for [input] (once; a recreated activity keeps the sheet where it was). */
  fun start(input: SharedInput) {
    if (started) return
    started = true
    probe()
    viewModelScope.launch {
      when (input) {
        is SharedInput.Text -> startLink(input)
        is SharedInput.Files -> startFiles(input)
      }
    }
  }

  /** The sheet starts a fresh probe and waits for it at most [PROBE_WAIT_MS] (spec 8.4 item 3). */
  private fun probe() {
    val answer = viewModelScope.async { runCatching { computer.checkNow() } }
    viewModelScope.launch {
      withTimeoutOrNull(PROBE_WAIT_MS) { answer.await() }
      mutable.update { it.copy(checking = false) }
    }
  }

  private suspend fun startLink(input: SharedInput.Text) {
    text = input
    val parsed = parser.parse(input.text, input.subject)
    if (parsed == null) {
      mutable.update { it.copy(phase = SharePhase.Problem(ShareProblem.NoLink)) }
      return
    }
    mutable.update { it.copy(summary = SharedSummary.Link(parsed.site, parsed.sent), audioOnlyOffered = true) }
    restoredIds()?.let { ids ->
      watchLink(ids.first())
      return
    }
    val duplicate = runCatching { requests.findDuplicate(linkInput(force = false)) }.getOrNull()
    mutable.update { it.copy(phase = if (duplicate != null) SharePhase.DuplicateFound(duplicate) else SharePhase.Confirm) }
  }

  private suspend fun startFiles(input: SharedInput.Files) {
    val items = if (input.uris.isEmpty()) emptyList() else files.inspect(input.uris, input.type)
    total = items.size + input.overLimit
    accepted = items.filter { it.refused == null }
    sheetRefusals = items.mapNotNull { item -> item.refused?.let { FileRefusal(item.displayName, it, null) } } +
      List(input.overLimit) { FileRefusal(null, FileRefusalReason.TOO_MANY, null) }
    val videos = items.count { it.kind == SharedKind.VIDEO }
    val summary = when {
      items.size == 1 -> items.single().let {
        SharedSummary.OneFile(it.displayName, it.kind ?: SharedKind.VIDEO, it.declaredBytes, it.durationMs)
      }
      items.isNotEmpty() -> SharedSummary.ManyFiles(
        count = items.size,
        videos = videos,
        audios = items.count { it.kind == SharedKind.AUDIO },
        bytes = items.mapNotNull { it.declaredBytes }.takeIf { it.isNotEmpty() }?.sum(),
      )
      else -> SharedSummary.Reading
    }
    val large = accepted.any { it.kind == SharedKind.VIDEO && (it.declaredBytes ?: 0) > ShareUiState.LARGE_FILE_BYTES }
    mutable.update {
      it.copy(
        summary = summary,
        audioOnlyOffered = accepted.any { item -> item.kind == SharedKind.VIDEO },
        largeFile = large,
        audioOnly = large,
      )
    }
    restoredIds()?.let { ids ->
      holdSources(ids)
      watchFiles(ids, emptyList())
      return
    }
    val phase = when {
      items.isEmpty() -> SharePhase.Problem(ShareProblem.NothingReadable)
      accepted.isEmpty() -> SharePhase.Problem(ShareProblem.Refused(total, sheetRefusals))
      else -> SharePhase.Confirm
    }
    mutable.update { it.copy(phase = phase) }
  }

  /** Whether TalkBack is on (the sheet's composition reports `LocalTalkBackOn`). */
  fun setTalkBack(on: Boolean) {
    talkBack.value = on
  }

  /** "Audio only" (links; local videos). */
  fun setAudioOnly(on: Boolean) {
    mutable.update { if (it.audioOnlyOffered) it.copy(audioOnly = on) else it }
  }

  /**
   * "Analyze" / "Add to queue" (and "Analyze again anyway" with [force]): the request is stored first (spec 7.7);
   * the call runs to its end even if the sheet closes meanwhile, so nothing is left half-made.
   */
  fun submit(force: Boolean = false) {
    val current = mutable.value
    val allowed = current.phase is SharePhase.Confirm || current.phase is SharePhase.DuplicateFound ||
      (current.phase as? SharePhase.Problem)?.problem.let { it is ShareProblem.Refused && it.tryAudioOnly }
    if (!allowed) return
    mutable.update { it.copy(phase = SharePhase.Saving) }
    viewModelScope.launch {
      withContext(NonCancellable) {
        if (text != null) addLink(force) else addFiles()
      }
    }
  }

  private suspend fun addLink(force: Boolean) {
    val result = runCatching { requests.addLink(linkInput(force)) }.getOrNull()
    when (result) {
      is AddResult.Added -> {
        stored(listOf(result.id))
        watchLink(result.id)
      }
      is AddResult.DuplicateFound -> mutable.update { it.copy(phase = SharePhase.DuplicateFound(result.duplicate)) }
      AddResult.NoLink -> mutable.update { it.copy(phase = SharePhase.Problem(ShareProblem.NoLink)) }
      AddResult.OutOfSpace, null -> mutable.update { it.copy(phase = SharePhase.Problem(ShareProblem.RecordOutOfSpace)) }
    }
  }

  private suspend fun addFiles() {
    val audioOnly = mutable.value.audioOnly
    val result = requests.addFiles(accepted.map { it.source() }, FileOptions(audioOnly = audioOnly))
    val refusals = sheetRefusals + result.refused
    if (result.added.isEmpty()) {
      val only = accepted.singleOrNull()
      val audioWouldFit = total == 1 && only?.kind == SharedKind.VIDEO && !audioOnly &&
        result.refused.singleOrNull()?.reason.let { it == FileRefusalReason.NO_SPACE || it == FileRefusalReason.TOO_LARGE }
      mutable.update { it.copy(phase = SharePhase.Problem(ShareProblem.Refused(total, refusals, audioWouldFit))) }
      return
    }
    holdSources(result.added)
    stored(result.added)
    watchFiles(result.added, refusals)
  }

  private fun holdSources(ids: List<RequestId>) {
    hold?.close()
    hold = requests.holdSources(ids.toSet())
  }

  /** The first request is safely stored: the notification question of F-23 comes now, once. */
  private fun stored(ids: List<RequestId>) {
    saved[KEY_STORED] = ArrayList(ids.map { it.value })
    if (asker.shouldAsk.value) mutable.update { it.copy(question = NotificationQuestion.ASKING) }
  }

  private fun restoredIds(): List<RequestId>? =
    saved.get<ArrayList<String>>(KEY_STORED)?.takeIf { it.isNotEmpty() }?.map(::RequestId)

  private fun watchLink(id: RequestId) {
    watch?.cancel()
    watch = viewModelScope.launch {
      combine(requests.request(id), computer.status) { request, status -> messageOf(request, status) }
        .distinctUntilChanged()
        .collect { message -> mutable.update { it.copy(phase = SharePhase.Done(listOf(id), message)) } }
    }
  }

  private fun watchFiles(ids: List<RequestId>, refusals: List<FileRefusal>) {
    watch?.cancel()
    val shared = total.coerceAtLeast(ids.size)
    val each: List<Flow<Watched>> = ids.map { id ->
      combine(
        requests.intake(id).onStart<IntakeProgress?> { emit(null) },
        requests.request(id),
      ) { progress, request -> Watched(id, progress, request) }
    }
    watch = viewModelScope.launch {
      (if (each.isEmpty()) flowOf(emptyArray<Watched>()) else combine(each) { it })
        .collect { watched -> mutable.update { it.copy(phase = phaseOf(watched.toList(), shared, refusals)) } }
    }
  }

  private fun phaseOf(watched: List<Watched>, shared: Int, refusals: List<FileRefusal>): SharePhase {
    val live = watched.filter { it.request != null }
    if (live.isEmpty()) return SharePhase.Closed
    live.firstOrNull { it.request?.state == RequestState.COPY_FAILED }?.let { failed ->
      val request = checkNotNull(failed.request)
      val error = request.lastError ?: failed.progress?.failed
      if (error != null) return SharePhase.CopyFailed(request, error)
    }
    val preparing = live.filter { it.request?.state == RequestState.PREPARING }
    if (preparing.isNotEmpty()) {
      val totals = live.map { it.progress?.totalBytes?.takeIf { bytes -> bytes > 0 } }
      val fraction = if (totals.any { it == null }) {
        null
      } else {
        val copied = live.sumOf { (it.progress?.copiedBytes ?: 0).coerceAtMost(it.progress?.totalBytes ?: 0) }
        (copied.toFloat() / totals.sumOf { it ?: 0 }).coerceIn(0f, 1f)
      }
      val anyVideo = accepted.any { it.kind == SharedKind.VIDEO } || accepted.isEmpty()
      return SharePhase.Preparing(preparing.map { it.id }, fraction, anyVideo)
    }
    return SharePhase.Done(live.map { it.id }, DoneMessage.ADDED, total = shared, refusals = refusals)
  }

  private fun messageOf(request: Request?, status: ComputerStatus): DoneMessage = when {
    request == null -> DoneMessage.ADDED
    request.state in ACCEPTED -> DoneMessage.SENT
    request.state == RequestState.NEEDS_ATTENTION -> DoneMessage.REFUSED
    status is ComputerStatus.Connected -> DoneMessage.SENDING
    else -> DoneMessage.ADDED
  }

  /** "Try audio only": the failed copy again for its audio track only, or the refused video added as audio. */
  fun tryAudioOnly() {
    when (val phase = mutable.value.phase) {
      is SharePhase.CopyFailed -> if (RequestAction.TRY_AUDIO_ONLY in phase.request.actions) {
        viewModelScope.launch { requests.perform(phase.request.id, RequestAction.TRY_AUDIO_ONLY) }
      }
      is SharePhase.Problem -> if ((phase.problem as? ShareProblem.Refused)?.tryAudioOnly == true) {
        mutable.update { it.copy(audioOnly = true) }
        submit()
      }
      else -> Unit
    }
  }

  /** "Remove" on a failed copy: the request and what was copied are deleted (spec 7.7). */
  fun removeFailed() {
    val phase = mutable.value.phase as? SharePhase.CopyFailed ?: return
    if (RequestAction.REMOVE !in phase.request.actions) return
    viewModelScope.launch { withContext(NonCancellable) { requests.perform(phase.request.id, RequestAction.REMOVE) } }
  }

  /**
   * Back (and a tap on the scrim): during a copy it asks "Stop adding this video?" first (SH-AC13); while the request
   * is being stored it waits; otherwise the sheet closes at once (spec 8.4 item 8).
   */
  fun back() {
    val current = mutable.value
    when {
      current.confirmStop -> keepAdding()
      current.phase is SharePhase.Preparing -> mutable.update { it.copy(confirmStop = true) }
      current.phase is SharePhase.Saving -> Unit
      else -> close()
    }
  }

  /** "Keep adding" (the default of "Stop adding this video?"). */
  fun keepAdding() {
    mutable.update { it.copy(confirmStop = false) }
  }

  /** "Stop": the copies stop, their records and partial copies are deleted (spec 7.7), and the sheet closes. */
  fun stopAdding() {
    val preparing = mutable.value.phase as? SharePhase.Preparing
    mutable.update { it.copy(confirmStop = false) }
    if (preparing == null) return
    watch?.cancel()
    viewModelScope.launch {
      withContext(NonCancellable) { preparing.ids.forEach { requests.stopIntake(it) } }
      close()
    }
  }

  /** "Cancel", "Close", or the sheet closing by itself, or after "Open PhonoTrainer" / "See requests". */
  fun close() {
    watch?.cancel()
    mutable.update { it.copy(phase = SharePhase.Closed, confirmStop = false) }
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

  /** The system prompt answered: the 2 s count may start again (D-51). */
  fun onSystemPromptResult(granted: Boolean) {
    asker.onSystemPromptResult(granted)
    mutable.update { it.copy(question = NotificationQuestion.NONE) }
  }

  override fun onCleared() {
    hold?.close()
    hold = null
  }

  private fun linkInput(force: Boolean): LinkInput {
    val input = checkNotNull(text)
    return LinkInput(input.text, input.subject, audioOnly = mutable.value.audioOnly, force = force)
  }

  private data class Watched(val id: RequestId, val progress: IntakeProgress?, val request: Request?)

  companion object {
    /** The sheet waits this long for its probe before it shows the last known state (spec 8.4 item 3). */
    const val PROBE_WAIT_MS: Long = 2_000

    /** The sheet closes by itself this long after the request is stored (and copied): spec 8.4 item 8. */
    const val AUTO_CLOSE_MS: Long = 2_000

    private const val KEY_STORED = "share.stored"

    private val ACCEPTED = setOf(
      RequestState.SENT,
      RequestState.IN_LINE,
      RequestState.ANALYZING,
      RequestState.READY_ON_COMPUTER,
      RequestState.GETTING_ON_PHONE,
      RequestState.ON_PHONE,
    )
  }
}
