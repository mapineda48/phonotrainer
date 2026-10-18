package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the request detail shows (spec F-20 item 2, design 10.2 P-04). */
@Immutable
internal data class RequestDetailUiState(
  val load: Load = Load.LOADING,
  /** The request; null once loaded means it is gone (removed here or elsewhere). */
  val request: Request? = null,
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val capabilities: Capabilities? = null,
  /** "Show it all" on the link. */
  val linkExpanded: Boolean = false,
  /** "Show details": the computer's cleaned message. */
  val detailsExpanded: Boolean = false,
  val now: Instant = Instant.EPOCH,
  val zone: ZoneId = ZoneOffset.UTC,
) {
  /** Loaded, and no request has this id any more. */
  val removed: Boolean get() = load == Load.READY && request == null
}

/**
 * The request detail (spec F-20 item 2): one request with its source, its milestone timeline, the stages of the
 * analysis with their teaching lines, its failure and its actions (only `Request.actions`, architecture 8.4). Stage
 * changes are announced politely (F-20 item 5). "Show it all" and "Show details" survive a process death.
 */
@HiltViewModel(assistedFactory = RequestDetailViewModel.Factory::class)
internal class RequestDetailViewModel @AssistedInject constructor(
  @Assisted key: RequestDetailKey,
  private val requests: RequestRepository,
  status: ComputerStatusRepository,
  private val clock: Clock,
  private val announcer: Announcer,
  private val savedState: SavedStateHandle,
) : ViewModel() {
  private val id = key.requestId
  private val watch = StageWatch()
  private val stages = Channel<Stage>(Channel.BUFFERED)

  /** Stage changes to announce (the route words them and calls [announce]). */
  val stageChanges: Flow<Stage> = stages.receiveAsFlow()

  private val request: Flow<Pair<Load, Request?>> = requests.request(id)
    .onEach { watch.changes(listOfNotNull(it)).forEach { stage -> stages.trySend(stage) } }
    .map<Request?, Pair<Load, Request?>> { Load.READY to it }
    .catch { if (it is CancellationException) throw it else emit(Load.FAILED to null) }

  private val ticks: Flow<Instant> = flow {
    while (true) {
      emit(clock.now())
      delay(RequestsViewModel.TICK_MS)
    }
  }

  private val toggles = combine(savedState.getStateFlow(LINK_OPEN, false), savedState.getStateFlow(DETAILS_OPEN, false)) { link, details ->
    link to details
  }

  val state: StateFlow<RequestDetailUiState> = combine(
    request,
    combine(status.status, status.paired) { now, paired -> now to paired?.capabilities },
    toggles,
    ticks,
  ) { (load, request), (computer, capabilities), (link, details), now ->
    RequestDetailUiState(
      load = load,
      request = request,
      status = computer,
      capabilities = capabilities,
      linkExpanded = link,
      detailsExpanded = details,
      now = now,
      zone = clock.zone(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RequestDetailUiState(now = clock.now(), zone = clock.zone()))

  /** Carries out [action] (the route has already asked first when the action needs it). */
  fun perform(action: RequestAction) {
    viewModelScope.launch { withContext(NonCancellable) { requests.perform(id, action) } }
  }

  fun setLinkExpanded(open: Boolean) {
    savedState[LINK_OPEN] = open
  }

  fun setDetailsExpanded(open: Boolean) {
    savedState[DETAILS_OPEN] = open
  }

  /** A polite TalkBack announcement (architecture 18): the words of a stage change. */
  fun announce(text: String) {
    announcer.polite(text)
  }

  @AssistedFactory
  interface Factory {
    fun create(key: RequestDetailKey): RequestDetailViewModel
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
    const val LINK_OPEN = "request.detail.link.open"
    const val DETAILS_OPEN = "request.detail.details.open"
  }
}
