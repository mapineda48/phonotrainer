package com.mapineda48.phonotrainer.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.navigation.AboutKey
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.ConnectionDetailsKey
import com.mapineda48.phonotrainer.core.navigation.HelpKey
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.NotificationSettingsKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.StorageKey
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey
import com.mapineda48.phonotrainer.core.navigation.YourComputerKey
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction

/** Where the controls of :feature:settings lead (architecture 16.1); pure, so a unit test covers every one. */
internal object SettingsDestinations {
  /** "Restart the tour" (F-01-AC5): the Welcome in its restart mode, which never re-pairs. */
  val tour: NavKey = WelcomeKey(restart = true)

  val details: NavKey = ConnectionDetailsKey
  val newAddress: NavKey = ConnectKey(ConnectMode.NEW_ADDRESS)
  val connectAgain: NavKey = ConnectKey(ConnectMode.CONNECT_AGAIN)
  val connect: NavKey = ConnectKey(ConnectMode.FROM_HOME)

  /** A status card control that leads somewhere (null: carried out here, or by the system). */
  fun card(action: ConnectionAction): NavKey? = when (action) {
    ConnectionAction.CONNECT -> connect
    ConnectionAction.CONNECT_AGAIN -> connectAgain
    ConnectionAction.NEW_ADDRESS -> newAddress
    ConnectionAction.WHY, ConnectionAction.DETAILS -> details
    ConnectionAction.SEE_REQUESTS, ConnectionAction.ATTENTION -> RequestsKey
    ConnectionAction.STORAGE -> StorageKey
    ConnectionAction.SYNC_NOW, ConnectionAction.BATTERY_SETTINGS -> null
  }
}

/** Settings (P-12, `SettingsKey`). */
@Composable
internal fun SettingsRoute(navigator: Navigator, viewModel: SettingsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = remember(viewModel, navigator) {
    SettingsActions(
      onBack = navigator::back,
      onAppearance = viewModel::setAppearance,
      onColors = viewModel::setColors,
      onPatterns = viewModel::setPatterns,
      onMotion = viewModel::setMotion,
      onSpeed = viewModel::setSpeed,
      onPlayEach = viewModel::setPlayEach,
      onEndOfShort = viewModel::setEndOfShort,
      onCaptions = viewModel::setCaptions,
      onListenFirst = viewModel::setListenFirst,
      onTrack = viewModel::setTrack,
      onInMix = viewModel::setInMix,
      onQuestions = viewModel::setQuestions,
      onGuidance = viewModel::setGuidance,
      onComputer = { navigator.navigate(YourComputerKey) },
      onStorage = { navigator.navigate(StorageKey) },
      onNotifications = { navigator.navigate(NotificationSettingsKey) },
      onHelp = { navigator.navigate(HelpKey) },
      onAbout = { navigator.navigate(AboutKey) },
    )
  }
  SettingsScreen(state, actions)
}

/** Settings > Your computer (`YourComputerKey`). */
@Composable
internal fun YourComputerRoute(navigator: Navigator, viewModel: YourComputerViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val actions = remember(viewModel, navigator, context) {
    YourComputerActions(
      onBack = navigator::back,
      onCard = { action ->
        when (action) {
          ConnectionAction.SYNC_NOW -> viewModel.syncNow()
          ConnectionAction.BATTERY_SETTINGS -> SystemScreens.appDetails(context)
          else -> SettingsDestinations.card(action)?.let(navigator::navigate)
        }
      },
      onCheckNow = viewModel::checkNow,
      onDetails = { navigator.navigate(SettingsDestinations.details) },
      onNewAddress = { navigator.navigate(SettingsDestinations.newAddress) },
      onConnectAgain = { navigator.navigate(SettingsDestinations.connectAgain) },
      onForget = viewModel::forget,
      onConnect = { navigator.navigate(SettingsDestinations.connect) },
    )
  }
  YourComputerScreen(state, actions)
}

/** Settings > Storage (`StorageKey`). */
@Composable
internal fun StorageRoute(navigator: Navigator, viewModel: StorageViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val actions = remember(viewModel, navigator, context) {
    StorageActions(
      onBack = navigator::back,
      onRetry = viewModel::retry,
      onBudget = viewModel::setBudget,
      onDownloadVideos = viewModel::setDownloadVideos,
      onRemoveVideo = viewModel::removeVideo,
      onRemoveFromPhone = viewModel::removeFromPhone,
      onKeep = viewModel::setKept,
      onRemoveAllVideos = viewModel::removeAllVideos,
      onBatterySettings = { SystemScreens.appDetails(context) },
    )
  }
  StorageScreen(state, actions)
}

/** Settings > Notifications (`NotificationSettingsKey`). */
@Composable
internal fun NotificationSettingsRoute(navigator: Navigator, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onPromptResult)
  val actions = remember(viewModel, navigator, context, prompt) {
    NotificationSettingsActions(
      onBack = navigator::back,
      onReady = viewModel::setReady,
      onAttention = viewModel::setAttention,
      onSystemSettings = { SystemScreens.notifications(context) },
      onTurnOn = {
        viewModel.turnOn { viewModel.systemPermission?.let(prompt::launch) }
      },
    )
  }
  NotificationSettingsScreen(state, actions)
}

/** Settings > Help (`HelpKey`). */
@Composable
internal fun HelpRoute(navigator: Navigator, viewModel: HelpViewModel = hiltViewModel()) {
  val actions = remember(viewModel, navigator) {
    HelpActions(
      onBack = navigator::back,
      onRestartTour = { viewModel.restartTour { navigator.navigate(SettingsDestinations.tour) } },
    )
  }
  HelpScreen(actions)
}

/** About PhonoTrainer (`AboutKey`). */
@Composable
internal fun AboutRoute(navigator: Navigator, viewModel: AboutViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val uriHandler = LocalUriHandler.current
  val actions = remember(viewModel, navigator, uriHandler) {
    AboutActions(
      onBack = navigator::back,
      onReadLicence = viewModel::openLicence,
      onCloseLicence = viewModel::closeLicence,
      onSource = { runCatching { uriHandler.openUri(Licences.SOURCE_URL) } },
      onLicences = { navigator.navigate(LicencesKey) },
    )
  }
  AboutScreen(state, actions)
}

/** Open-source licences (`LicencesKey`). */
@Composable
internal fun LicencesRoute(navigator: Navigator, viewModel: LicencesViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val actions = remember(viewModel, navigator) {
    LicencesActions(onBack = navigator::back, onRetry = viewModel::retry, onOpen = viewModel::open, onClose = viewModel::close)
  }
  LicencesScreen(state, actions)
}

/** The system screens Settings opens. Nothing happens on a device without the screen. */
internal object SystemScreens {
  /** Android's notification settings of this app ("Open settings", "Notification settings"; spec F-23). */
  fun notifications(context: Context) {
    start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
  }

  /** This app's system page, where Android shows its battery and background settings ("Battery settings", F-21). */
  fun appDetails(context: Context) {
    start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
  }

  private fun start(context: Context, intent: Intent) {
    try {
      context.startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
      // No such settings screen on this device.
    }
  }
}
