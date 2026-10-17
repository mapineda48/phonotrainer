package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey

/** The feed In order on [id]: at [segment]'s short, from the first short, or (neither) at its resume short (F-04-AC5). */
internal fun inOrder(id: JobId, segment: Int? = null, fromStart: Boolean = false): WatchKey =
  WatchKey(FeedStart.InOrder(id, firstSegment = segment, fromStart = fromStart))

/** Carries out an "In progress" or processing request's action: here, in the request detail, or in Connect. */
internal fun routeRequestAction(navigator: Navigator, request: Request, action: RequestAction, perform: () -> Unit) {
  when (LibraryRules.route(action)) {
    RequestRoute.PERFORM -> perform()
    RequestRoute.DETAIL -> navigator.navigate(RequestDetailKey(request.id))
    RequestRoute.CONNECT_AGAIN -> navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN))
  }
}

/** The Library's entry (P-01): the ViewModel's state, each control wired to a call or a destination. */
@Composable
internal fun LibraryRoute(navigator: Navigator, viewModel: LibraryViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = remember(viewModel, navigator) {
    LibraryActions(
      onBack = navigator::back,
      onAdd = { navigator.navigate(AddClipKey()) },
      onSearch = viewModel::setSearch,
      onFilter = viewModel::toggleFilter,
      onShowAll = viewModel::showAll,
      onSort = viewModel::setSort,
      onRetry = viewModel::retry,
      onOpenClip = { navigator.navigate(ClipKey(it)) },
      onWatch = { navigator.navigate(inOrder(it)) },
      onKeep = viewModel::setKept,
      onDownload = viewModel::download,
      onRemoveFromPhone = viewModel::removeFromPhone,
      onRemoveVideo = viewModel::removeVideo,
      onRequestAction = { request, action ->
        routeRequestAction(navigator, request, action) { viewModel.perform(request.id, action) }
      },
      onOpenRequest = { navigator.navigate(RequestDetailKey(it)) },
      onStartedOnComputer = { navigator.navigate(RequestsKey) },
      onConnect = { navigator.navigate(ConnectKey(ConnectMode.FROM_HOME)) },
      onLearn = { navigator.navigate(LearnKey) },
    )
  }
  LibraryScreen(state, actions)
}

/** The Clip page's entry (P-02). */
@Composable
internal fun ClipRoute(
  key: ClipKey,
  navigator: Navigator,
  slots: ClipSlots,
  viewModel: ClipViewModel = hiltViewModel<ClipViewModel, ClipViewModel.Factory>(creationCallback = { it.create(key) }),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val uriHandler = LocalUriHandler.current
  val id = key.jobId
  val actions = remember(viewModel, navigator, uriHandler) {
    ClipActions(
      onBack = navigator::back,
      onSeeAll = { navigator.navigate(LibraryKey) },
      onWatch = { navigator.navigate(inOrder(id, segment = viewModel.state.value.resume?.id?.firstSegment)) },
      onFromStart = { navigator.navigate(inOrder(id, fromStart = true)) },
      onShort = { navigator.navigate(inOrder(id, segment = it.firstSegment)) },
      onChange = { navigator.navigate(WatchKey(FeedStart.ChangeMode(it, onlyClip = id))) },
      onTab = viewModel::selectTab,
      onKeep = viewModel::setKept,
      onDownload = viewModel::download,
      onDownloadVideo = viewModel::downloadVideo,
      onRemoveVideo = viewModel::removeVideo,
      onRemoveFromPhone = viewModel::removeFromPhone,
      onOpenLink = { link -> if (ClipRules.openable(link)) runCatching { uriHandler.openUri(link.trim()) } },
      onRequestAction = { request, action ->
        routeRequestAction(navigator, request, action) { viewModel.perform(request.id, action) }
      },
      onOpenRequest = { navigator.navigate(RequestDetailKey(it)) },
      onOpenWord = { navigator.navigate(LessonKey(it.jobId, it.segment, it.word, LessonOrigin.CLIP_PAGE, LessonPresentation.FULL)) },
      onOpenSegment = { navigator.navigate(inOrder(id, segment = it)) },
    )
  }
  ClipScreen(state, actions, slots = slots)
}
