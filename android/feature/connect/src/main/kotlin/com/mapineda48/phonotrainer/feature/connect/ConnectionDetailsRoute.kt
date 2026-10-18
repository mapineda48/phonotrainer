package com.mapineda48.phonotrainer.feature.connect

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction

/** Where the status card's controls lead from Connection details; null: handled on this page. Pure, for its test. */
internal object DetailsDestinations {
  fun of(action: ConnectionAction): NavKey? = when (action) {
    ConnectionAction.CONNECT -> ConnectKey(ConnectMode.FROM_HOME)
    ConnectionAction.CONNECT_AGAIN -> ConnectKey(ConnectMode.CONNECT_AGAIN)
    ConnectionAction.NEW_ADDRESS -> ConnectKey(ConnectMode.NEW_ADDRESS)
    ConnectionAction.SEE_REQUESTS -> RequestsKey
    ConnectionAction.STORAGE -> StorageKey
    ConnectionAction.WHY, ConnectionAction.DETAILS, ConnectionAction.SYNC_NOW, ConnectionAction.ATTENTION,
    ConnectionAction.BATTERY_SETTINGS,
    -> null
  }
}

/** Connection details' entry (P-07). */
@Composable
internal fun ConnectionDetailsRoute(navigator: Navigator, viewModel: ConnectionDetailsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val platform = remember(context) { platformOf(context) }
  val actions = remember(viewModel, navigator, clipboard, scope) {
    DetailsActions(
      onBack = navigator::back,
      onCard = { action ->
        when (action) {
          ConnectionAction.SYNC_NOW -> viewModel.syncNow()
          else -> DetailsDestinations.of(action)?.let(navigator::navigate)
        }
      },
      onCheckNow = viewModel::checkNow,
      onCopyDiagnostics = { text ->
        copyText(scope, clipboard, text)
        viewModel.copied()
      },
    )
  }
  ConnectionDetailsScreen(state, platform, actions)
}

private fun platformOf(context: Context): PlatformInfo {
  val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
  return PlatformInfo(appVersion = version, androidRelease = Build.VERSION.RELEASE ?: "", sdk = Build.VERSION.SDK_INT)
}
