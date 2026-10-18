package com.mapineda48.phonotrainer.feature.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.AddClipStart
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.PracticeKey
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.SavedKey
import com.mapineda48.phonotrainer.core.navigation.SettingsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction

/** Where Home's destinations lead (spec F-03, architecture 16.1); pure, so the route test covers every one. */
internal object HomeDestinations {
  /** "Start" / "Continue": the Mix at today's set (F-03-AC1). "Another set" goes there too: its summary builds one. */
  val today: NavKey = WatchKey(FeedStart.TodaySet)

  /** A clip's play action: "In order" on it at its resume short (short 1 if never opened), F-03-AC8. */
  fun watchClip(id: JobId): NavKey = WatchKey(FeedStart.InOrder(id, firstSegment = null, fromStart = false))

  /** "Keep watching": the feed at the short where the learner stopped (F-03-AC6). */
  fun keepWatching(short: ShortId): NavKey = WatchKey(FeedStart.InOrder(short.jobId, firstSegment = short.firstSegment, fromStart = false))

  /** A chip: the change mode with its [shorts] (F-03-AC7), or the change's Learn page when it has none (D-48). */
  fun change(label: ChangeLabel, shorts: Int): NavKey =
    if (shorts > 0) WatchKey(FeedStart.ChangeMode(label, onlyClip = null)) else PhenomenonKey(label)

  /** A status card control that leads somewhere (null: carried out here, or by the system). */
  fun connection(action: ConnectionAction): NavKey? = when (action) {
    ConnectionAction.CONNECT -> ConnectKey(ConnectMode.FROM_HOME)
    ConnectionAction.CONNECT_AGAIN -> ConnectKey(ConnectMode.CONNECT_AGAIN)
    ConnectionAction.NEW_ADDRESS -> ConnectKey(ConnectMode.NEW_ADDRESS)
    ConnectionAction.WHY, ConnectionAction.DETAILS -> ConnectionDetailsKey
    ConnectionAction.SEE_REQUESTS -> RequestsKey
    ConnectionAction.STORAGE -> StorageKey
    ConnectionAction.SYNC_NOW, ConnectionAction.ATTENTION, ConnectionAction.BATTERY_SETTINGS -> null
  }
}

/** Home's entry (T1): the ViewModel's state, each control wired to a call, a destination or a system screen. */
@Composable
internal fun HomeRoute(navigator: Navigator, viewModel: HomeViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val actions = remember(viewModel, navigator, context) {
    HomeActions(
      onSettings = { navigator.navigate(SettingsKey) },
      onRefresh = viewModel::refresh,
      onDismissNotice = viewModel::dismissNotice,
      onConnection = { action, requestAction ->
        when (action) {
          ConnectionAction.SYNC_NOW -> viewModel.syncNow()
          ConnectionAction.BATTERY_SETTINGS -> openAppSettings(context)
          ConnectionAction.ATTENTION -> {
            val request = viewModel.state.value.attention
            if (request != null && requestAction != null) attention(navigator, viewModel, request.id, requestAction)
          }
          else -> HomeDestinations.connection(action)?.let(navigator::navigate)
        }
      },
      onConnect = { navigator.navigate(ConnectKey(ConnectMode.FROM_HOME)) },
      onLearn = { navigator.navigate(LearnKey) },
      onAdd = { navigator.navigate(AddClipKey()) },
      onPaste = { navigator.navigate(AddClipKey(AddClipStart.PASTE)) },
      onChooseFile = { navigator.navigate(AddClipKey(AddClipStart.FILES)) },
      onRetry = viewModel::retry,
      onStartToday = { navigator.navigate(HomeDestinations.today) },
      onAnotherSet = { navigator.navigate(HomeDestinations.today) },
      onKeepWatching = { navigator.navigate(HomeDestinations.keepWatching(it)) },
      onWatchClip = { navigator.navigate(HomeDestinations.watchClip(it)) },
      onOpenClip = { navigator.navigate(ClipKey(it)) },
      onAllClips = { navigator.navigate(LibraryKey) },
      onChange = { label, shorts -> navigator.navigate(HomeDestinations.change(label, shorts)) },
      onPractice = { navigator.navigate(PracticeKey()) },
      onSaved = { navigator.navigate(SavedKey) },
      onInsights = { navigator.navigate(InsightsKey) },
    )
  }
  HomeScreen(state, actions)
}

/** The inline action of the request that needs attention: done here, in its detail (confirmations) or in Connect. */
private fun attention(
  navigator: Navigator,
  viewModel: HomeViewModel,
  id: RequestId,
  action: RequestAction,
) {
  when (HomeRules.route(action)) {
    RequestRoute.PERFORM -> viewModel.perform(id, action)
    RequestRoute.DETAIL -> navigator.navigate(RequestDetailKey(id))
    RequestRoute.CONNECT_AGAIN -> navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN))
  }
}

/**
 * "Battery settings" (F-03 States: background limited): this app's system page, where Android and One UI show its
 * battery and sleeping-app settings. Nothing happens on a phone without that screen.
 */
private fun openAppSettings(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
  try {
    context.startActivity(intent)
  } catch (missing: ActivityNotFoundException) {
    // No settings screen to open on this device.
  }
}
