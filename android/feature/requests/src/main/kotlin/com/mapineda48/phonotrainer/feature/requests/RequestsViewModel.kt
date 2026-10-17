package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the queue could be read (spec F-20 States: Loading, Error). */
internal enum class Load { LOADING, FAILED, READY }

/** What the Requests screen shows (spec F-20, design 10.2 P-03). */
@Immutable
internal data class RequestsUiState(
  val load: Load = Load.LOADING,
  val groups: List<RequestGroup> = emptyList(),
  /** "Started on your computer" (collapsed by default, never counted). */
  val browser: List<ComputerJob> = emptyList(),
  val browserExpanded: Boolean = false,
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val capabilities: Capabilities? = null,
  val now: Instant = Instant.EPOCH,
) {
  /** Nothing in progress at all: the empty state (spec F-20 States, First run / Empty). */
  val empty: Boolean get() = load == Load.READY && groups.isEmpty() && browser.isEmpty()

  /** The queue-wide pause of architecture 8.4, derived from the status, shown as the header notice. */
  val paused: Boolean
    get() = status == ComputerStatus.ConnectAgain || status == ComputerStatus.ComputerTooOld ||
      status == ComputerStatus.AppTooOld || status is ComputerStatus.DifferentComputer
}

/**
 * Requests (spec F-20): this phone's device queue in its groups, the browser's jobs in their own collapsed group, the
 * connection line and the queue's pause, the actions of each request (only `Request.actions`, architecture 8.4) and
 * the polite announcement of a stage change (F-20 item 5). "Try again" re-reads the queue after an error; the queue is
 * never cleared by it. Whether "Started on your computer" is open survives a process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class RequestsViewModel @Inject constructor(
  private val requests: RequestRepository,
  private val clips: ClipRepository,
  status: ComputerStatusRepository,
  private val clock: Clock,
  private val announcer: Announcer,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  private val attempts = MutableStateFlow(0)
  private val watch = StageWatch()
  private val stages = Channel<Stage>(Channel.BUFFERED)

  /** Stage changes to announce (the route words them and calls [announce]). */
  val stageChanges: Flow<Stage> = stages.receiveAsFlow()

  private sealed interface Queue {
    data object Loading : Queue

    data object Failed : Queue

    data class Ready(val requests: List<Request>) : Queue
  }

  /**
   * The queue. "Loading" is only the first state and that of "Try again": when the screen comes back after a while the
   * last list stays until the new one arrives, so the list keeps its scroll position.
   */
  private val queue: Flow<Queue> = attempts.flatMapLatest { attempt ->
    val read = requests.requests()
      .onEach { list -> watch.changes(list).forEach { stages.trySend(it) } }
      .map<List<Request>, Queue> { Queue.Ready(it) }
      .catch { if (it is CancellationException) throw it else emit(Queue.Failed) }
    if (attempt == 0) read else read.onStart { emit(Queue.Loading) }
  }

  private val ticks: Flow<Instant> = flow {
    while (true) {
      emit(clock.now())
      delay(TICK_MS)
    }
  }

  private val connection = combine(status.status, status.paired) { now, paired -> now to paired?.capabilities }

  val state: StateFlow<RequestsUiState> = combine(
    queue,
    clips.browserJobs().catch { emit(emptyList()) },
    connection,
    savedState.getStateFlow(BROWSER_OPEN, false),
    ticks,
  ) { queue, browser, (computer, capabilities), open, now ->
    RequestsUiState(
      load = when (queue) {
        Queue.Loading -> Load.LOADING
        Queue.Failed -> Load.FAILED
        is Queue.Ready -> Load.READY
      },
      groups = (queue as? Queue.Ready)?.requests?.let { RequestsRules.groups(it, now) }.orEmpty(),
      browser = RequestsRules.browser(browser),
      browserExpanded = open,
      status = computer,
      capabilities = capabilities,
      now = now,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RequestsUiState(now = clock.now()))

  /** Carries out [action] on [id] (the route has already asked first when the action needs it). */
  fun perform(id: RequestId, action: RequestAction) {
    viewModelScope.launch { withContext(NonCancellable) { requests.perform(id, action) } }
  }

  /** "Try again" after the queue could not be read. */
  fun retry() {
    attempts.value += 1
  }

  /** Opens or closes "Started on your computer". */
  fun setBrowserExpanded(open: Boolean) {
    savedState[BROWSER_OPEN] = open
  }

  /** "Hide on this phone" on a failed browser job (spec F-20-AC8). */
  fun hideBrowserJob(id: JobId) {
    viewModelScope.launch { clips.hideBrowserJob(id) }
  }

  /** A polite TalkBack announcement (architecture 18): the words of a stage change. */
  fun announce(text: String) {
    announcer.polite(text)
  }

  companion object {
    /** The ages ("added 3 min ago") and "Will try again in {n} min" are counted again this often. */
    const val TICK_MS: Long = 30_000

    private const val STOP_TIMEOUT_MS = 5_000L
    private const val BROWSER_OPEN = "requests.browser.open"
  }
}
