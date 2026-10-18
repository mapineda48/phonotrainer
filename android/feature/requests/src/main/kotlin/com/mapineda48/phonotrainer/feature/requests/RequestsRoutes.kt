package com.mapineda48.phonotrainer.feature.requests

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.component.RequestRowStages
import com.mapineda48.phonotrainer.feature.requests.add.AddClipActions
import com.mapineda48.phonotrainer.feature.requests.add.AddClipScreen
import com.mapineda48.phonotrainer.feature.requests.add.AddClipViewModel
import kotlinx.coroutines.launch

/** The MIME types the picker offers (architecture 12: "Choose a file" offers any video and any audio). */
internal val PICKER_TYPES: Array<String> = arrayOf("video/*", "audio/*")

/**
 * How a request's action that is not the queue's own is carried out (architecture 8.4: `RequestRepository.perform`
 * does nothing for these): the link handed to the system, the connection screens. Returns false for the queue's own.
 */
internal fun routeUiAction(navigator: Navigator, uriHandler: UriHandler, request: Request, action: RequestAction): Boolean {
  when (RequestsRules.route(action)) {
    ActionRoute.OPEN_LINK -> RequestsRules.openable(request.link)?.let { link -> runCatching { uriHandler.openUri(link) } }
    ActionRoute.CONNECT_AGAIN -> navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN))
    ActionRoute.WHY -> navigator.navigate(ConnectionDetailsKey)
    ActionRoute.PERFORM, ActionRoute.CONFIRM_CANCEL, ActionRoute.CONFIRM_REMOVE, ActionRoute.DETAILS -> return false
  }
  return true
}

/** The feed in order on a clip (its resume short): "Watch" on a request that is on this phone. */
internal fun watchOf(request: Request): WatchKey? =
  request.jobId?.let { WatchKey(FeedStart.InOrder(it, firstSegment = null, fromStart = false)) }

/** Requests' entry (P-03): the ViewModel's state, each control wired to a call, a destination or the system. */
@Composable
internal fun RequestsRoute(navigator: Navigator, viewModel: RequestsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val uriHandler = LocalUriHandler.current
  val resources = LocalResources.current
  LaunchedEffect(viewModel) {
    viewModel.stageChanges.collect { viewModel.announce(RequestRowStages.announcement(it, resources)) }
  }
  val actions = remember(viewModel, navigator, uriHandler) {
    RequestsActions(
      onBack = navigator::back,
      onAdd = { navigator.navigate(AddClipKey()) },
      onRetry = viewModel::retry,
      onOpen = { request ->
        val clip = request.jobId?.takeIf { request.state == RequestState.ON_PHONE }
        navigator.navigate(if (clip != null) ClipKey(clip) else RequestDetailKey(request.id))
      },
      onWatch = { request -> watchOf(request)?.let(navigator::navigate) },
      onAction = { request, action ->
        when {
          RequestsRules.route(action) == ActionRoute.DETAILS -> navigator.navigate(RequestDetailKey(request.id))
          !routeUiAction(navigator, uriHandler, request, action) -> viewModel.perform(request.id, action)
        }
      },
      onBrowserExpanded = viewModel::setBrowserExpanded,
      onHide = viewModel::hideBrowserJob,
      onConnectAgain = { navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN)) },
      onWhy = { navigator.navigate(ConnectionDetailsKey) },
    )
  }
  RequestsScreen(state, actions)
}

/** The request detail's entry (P-04). A confirmed "Remove" goes back: the request is gone. */
@Composable
internal fun RequestDetailRoute(
  key: RequestDetailKey,
  navigator: Navigator,
  viewModel: RequestDetailViewModel = hiltViewModel<RequestDetailViewModel, RequestDetailViewModel.Factory>(
    creationCallback = { it.create(key) },
  ),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val uriHandler = LocalUriHandler.current
  val resources = LocalResources.current
  LaunchedEffect(viewModel) {
    viewModel.stageChanges.collect { viewModel.announce(RequestRowStages.announcement(it, resources)) }
  }
  val actions = remember(viewModel, navigator, uriHandler) {
    DetailActions(
      onBack = navigator::back,
      onSeeRequests = { navigator.navigate(RequestsKey) },
      onAction = { action ->
        val request = viewModel.state.value.request
        if (request != null && !routeUiAction(navigator, uriHandler, request, action)) {
          viewModel.perform(action)
          if (action == RequestAction.REMOVE) navigator.back()
        }
      },
      onWatch = { viewModel.state.value.request?.let(::watchOf)?.let(navigator::navigate) },
      onLinkExpanded = viewModel::setLinkExpanded,
      onDetailsExpanded = viewModel::setDetailsExpanded,
    )
  }
  RequestDetailScreen(state, actions)
}

/**
 * "Add a clip"'s entry (P-05): the system picker (`OpenMultipleDocuments`, video and audio), the clipboard read only
 * when "Paste" is tapped (F-19-AC1), the notification prompt, and the app's destinations. `AddClipStart.FILES` opens
 * the picker once when the screen first shows.
 */
@Composable
internal fun AddClipRoute(
  key: AddClipKey,
  navigator: Navigator,
  viewModel: AddClipViewModel = hiltViewModel<AddClipViewModel, AddClipViewModel.Factory>(creationCallback = { it.create(key) }),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    viewModel.pick(uris.map { it.toString() })
  }
  val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    viewModel.onSystemPromptResult(granted)
  }
  if (viewModel.opensPickerFirst) {
    LaunchedEffect(Unit) {
      viewModel.pickerOpened()
      runCatching { picker.launch(PICKER_TYPES) }
    }
  }
  val actions = remember(viewModel, navigator) {
    AddClipActions(
      onBack = navigator::back,
      onLink = viewModel::setLink,
      onPaste = { scope.launch { viewModel.paste(readClipboard(clipboard, context)) } },
      onChooseFiles = { runCatching { picker.launch(PICKER_TYPES) } },
      onClearFiles = viewModel::clearFiles,
      onAudioOnly = viewModel::setAudioOnly,
      onSeparate = viewModel::setSeparateDialogue,
      onSubmit = { viewModel.submit() },
      onAnalyzeAgain = { viewModel.submit(force = true) },
      onOpenDuplicate = { navigator.navigate(targetOf(it)) },
      onSeeRequests = { navigator.navigate(RequestsKey) },
      onAnother = viewModel::another,
      onFileAction = { id, action ->
        when (RequestsRules.route(action)) {
          ActionRoute.PERFORM, ActionRoute.CONFIRM_REMOVE, ActionRoute.CONFIRM_CANCEL -> viewModel.perform(id, action)
          else -> navigator.navigate(RequestDetailKey(id))
        }
      },
      onOpenRequest = { navigator.navigate(RequestDetailKey(it)) },
      onNotNow = viewModel::notNow,
      onNotifyMe = {
        scope.launch {
          val permission = viewModel.systemPermission
          if (viewModel.notifyMe() && permission != null) prompt.launch(permission)
        }
      },
    )
  }
  AddClipScreen(state, actions, focusLink = viewModel.focusesLink)
}

/** The clipboard's text, read now (only "Paste" calls this, F-19-AC1); null when it holds none. */
internal suspend fun readClipboard(clipboard: Clipboard, context: Context): String? = runCatching {
  val data = clipboard.getClipEntry()?.clipData ?: return@runCatching null
  (0 until data.itemCount).firstNotNullOfOrNull { index ->
    data.getItemAt(index)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
  }
}.getOrNull()

/** "Open it" on a duplicate: its request when this phone has one, else its clip's feed, else Requests (spec 7.7). */
internal fun targetOf(duplicate: Duplicate): NavKey = when {
  duplicate.requestId != null -> RequestDetailKey(checkNotNull(duplicate.requestId))
  duplicate.jobId != null -> WatchKey(FeedStart.InOrder(checkNotNull(duplicate.jobId), firstSegment = null, fromStart = false))
  else -> RequestsKey
}
