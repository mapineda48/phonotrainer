package com.mapineda48.phonotrainer.feature.connect

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where Connect goes when it ends; pure, so its test covers every way out. */
internal object ConnectExits {
  /** "Go to Home" after pairing (F-02 behaviour 6): Connect leaves the stack and Home shows at its root. */
  fun home(navigator: Navigator) {
    navigator.back()
    navigator.switchTab(Tab.HOME)
  }
}

/** Connect's entry (P-06): the ViewModel for [key]'s mode, each control wired to a call, a destination or the system. */
@Composable
internal fun ConnectRoute(
  key: ConnectKey,
  navigator: Navigator,
  viewModel: ConnectViewModel = hiltViewModel<ConnectViewModel, ConnectViewModel.Factory>(creationCallback = { it.create(key) }),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  // Back from the system's Wi-Fi settings: search again if this phone was not on Wi-Fi before.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    if (viewModel.state.value.search == SearchUi.NotOnWifi) viewModel.search()
  }
  val actions = remember(viewModel, navigator, context, clipboard, scope) {
    ConnectActions(
      onBack = navigator::back,
      onSearchAgain = viewModel::search,
      onOpenWifi = { openWifiSettings(context) },
      onEnterAddress = viewModel::openAddress,
      onAddressChange = viewModel::setAddress,
      onCheck = viewModel::check,
      onSelect = viewModel::select,
      onCodeChange = viewModel::setCode,
      onNameChange = viewModel::setDeviceName,
      onConnect = viewModel::connect,
      onNotNow = navigator::back,
      onGoHome = { ConnectExits.home(navigator) },
      onDone = navigator::back,
      onCopy = { text -> copyText(scope, clipboard, text) },
    )
  }
  ConnectScreen(state, actions)
}

/** Puts [text] on the clipboard (a command, or the diagnostics, which carry no address, id or key). */
internal fun copyText(scope: CoroutineScope, clipboard: Clipboard, text: String) {
  scope.launch {
    runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(CLIP_LABEL, text))) }
  }
}

/** The system's Wi-Fi panel (API 29+), or Wi-Fi settings where the panel is missing. */
private fun openWifiSettings(context: Context) {
  val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(panel)
  } catch (_: ActivityNotFoundException) {
    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
}

private const val CLIP_LABEL = "PhonoTrainer"
