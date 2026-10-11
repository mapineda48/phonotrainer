package com.mapineda48.phonotrainer.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.mapineda48.phonotrainer.R
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.PhonoNavSerialization
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.mapineda48.phonotrainer.core.designsystem.R as DesignR

/** What the shell needs from the graph; [MainActivity][com.mapineda48.phonotrainer.MainActivity] hands it over. */
internal class ShellInputs(
  val installers: Set<EntryInstaller>,
  val resolver: TargetResolver,
  val userMessages: UserMessages,
  val computerStatus: StateFlow<ComputerStatus>,
  val announcer: ShellAnnouncer,
)

/** The navigator the shell remembers: a saved back stack per tab and the current tab (architecture 16.2). */
@Composable
internal fun rememberAppNavigator(resolver: TargetResolver): AppNavigator {
  val stacks = Tab.entries.associateWith { tab ->
    key(tab) { rememberNavBackStack(PhonoNavSerialization.configuration, tab.rootKey) }
  }
  val tab = rememberSaveable { mutableStateOf(Tab.HOME) }
  val scope = rememberCoroutineScope()
  // The stacks and the tab state are the same objects on every recomposition; keying on the stacks themselves would
  // compare their contents (a back stack is a List) and rebuild the navigator on every push.
  return remember(resolver) { AppNavigator(stacks, tab, scope, resolver) }
}

/**
 * The app shell (architecture 16.2, 3.1; design 9.23, 9.24): the bottom bar, every tab's back stack decorated on its
 * own (so switching tabs keeps each tab's saved state and ViewModels, Android review H1), the overlay scene strategy,
 * the one host of `UserMessages` snackbars, the slot of the global "Connect again" banner and the polite live region
 * of the `Announcer`.
 */
@Composable
internal fun PhonoShell(inputs: ShellInputs, navigator: AppNavigator, modifier: Modifier = Modifier) {
  val provider = remember(inputs.installers, navigator) {
    entryProvider<NavKey> { inputs.installers.forEach { with(it) { install(navigator) } } }
  }
  // One decorated list per stack, all composed whatever the current tab is: Navigation 3 clears an entry's saved
  // state and ViewModelStore when it leaves the list it decorates, so a shared list would lose the inactive tabs.
  val decorated = Tab.entries.associateWith { tab ->
    key(tab) { rememberStackEntries(tab, navigator.stack(tab), provider) }
  }
  val current = navigator.currentTab
  val shown = if (current == Tab.HOME) decorated.getValue(Tab.HOME) else decorated.getValue(Tab.HOME) + decorated.getValue(current)

  val snackbar = remember { SnackbarHostState() }
  val status by inputs.computerStatus.collectAsStateWithLifecycle()
  val announcement by inputs.announcer.latest.collectAsStateWithLifecycle()
  UserMessageEffect(inputs.userMessages, snackbar)

  Scaffold(
    modifier = modifier,
    snackbarHost = { SnackbarHost(snackbar, Modifier.testTag(ShellTags.SNACKBAR)) },
    bottomBar = { ShellBottomBar(current = current, onSelect = navigator::switchTab) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      ConnectAgainBanner(
        visible = status == ComputerStatus.ConnectAgain,
        onConnectAgain = { navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN)) },
      )
      Box(Modifier.weight(1f)) {
        NavDisplay(
          entries = shown,
          sceneStrategies = listOf(BottomSheetSceneStrategy(), DialogSceneStrategy()),
          onBack = navigator::back,
        )
        Box(
          Modifier.size(1.dp).testTag(ShellTags.ANNOUNCER).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement.orEmpty()
          },
        )
      }
    }
  }
}

/** One stack's entries, decorated on their own, with content keys prefixed by the stack ("watch/…"). */
@Composable
private fun rememberStackEntries(
  tab: Tab,
  stack: NavBackStack<NavKey>,
  provider: (NavKey) -> NavEntry<NavKey>,
): List<NavEntry<NavKey>> {
  val prefix = tab.name.lowercase()
  return rememberDecoratedNavEntries(
    backStack = stack,
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    entryProvider = { navKey ->
      val inner = provider(navKey)
      NavEntry(navKey, "$prefix/${inner.contentKey}", inner.metadata) {
        Box(Modifier.testTag(ShellTags.destination(navKey))) { inner.Content() }
      }
    },
  )
}

@Composable
private fun ShellBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
  NavigationBar {
    Tab.entries.forEach { tab ->
      val label = stringResource(tab.label())
      NavigationBarItem(
        selected = tab == current,
        onClick = { onSelect(tab) },
        icon = { Icon(painterResource(tab.icon()), contentDescription = null) },
        label = { Text(label) },
        modifier = Modifier.testTag(ShellTags.tab(tab)),
      )
    }
  }
}

private fun Tab.label(): Int = when (this) {
  Tab.HOME -> R.string.tab_home
  Tab.WATCH -> R.string.tab_watch
  Tab.LEARN -> R.string.tab_learn
  Tab.PRACTICE -> R.string.tab_practice
}

private fun Tab.icon(): Int = when (this) {
  Tab.HOME -> DesignR.drawable.ic_tab_home
  Tab.WATCH -> DesignR.drawable.ic_tab_watch
  Tab.LEARN -> DesignR.drawable.ic_tab_learn
  Tab.PRACTICE -> DesignR.drawable.ic_tab_practice
}

/** The global "Connect again" banner (design 9.23): drawn once, under every destination's top, while revoked. */
@Composable
internal fun ConnectAgainBanner(visible: Boolean, onConnectAgain: () -> Unit, modifier: Modifier = Modifier) {
  if (!visible) return
  Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth().testTag(ShellTags.BANNER)) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.banner_connect_again), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      PhonoButton(
        text = stringResource(R.string.banner_connect_again_action),
        onClick = onConnectAgain,
        modifier = Modifier.testTag(ShellTags.BANNER_ACTION),
        kind = PhonoButtonKind.SECONDARY,
      )
    }
  }
}

/**
 * Shows [UserMessages.current] as one snackbar at a time (design 9.23): never on a timer shorter than 10 s, and with
 * TalkBack on it waits for the learner; then it reports the message consumed.
 */
@Composable
private fun UserMessageEffect(messages: UserMessages, host: SnackbarHostState) {
  val current by messages.current.collectAsStateWithLifecycle()
  val message = current ?: return
  val text = messageText(message)
  val dismiss = stringResource(R.string.message_dismiss)
  val talkBackOn = LocalTalkBackOn.current
  LaunchedEffect(message.id) {
    val timer = if (talkBackOn) {
      null
    } else {
      launch {
        delay(SNACKBAR_MS)
        host.currentSnackbarData?.dismiss()
      }
    }
    host.showSnackbar(text, actionLabel = dismiss, duration = SnackbarDuration.Indefinite)
    timer?.cancel()
    messages.consumed(message.id)
  }
}

private const val SNACKBAR_MS = 10_000L

@Composable
private fun messageText(message: UserMessage): String {
  val title = message.args.firstOrNull().orEmpty()
  return when (message.kind) {
    UserMessageKind.CLIP_GONE -> stringResource(R.string.message_clip_gone)
    UserMessageKind.REQUEST_REMOVED -> stringResource(R.string.message_request_removed)
    UserMessageKind.LIBRARY_CHANGED -> stringResource(R.string.message_library_changed)
    UserMessageKind.CLIPS_REMOVED -> {
      val count = title.toIntOrNull() ?: 1
      pluralStringResource(R.plurals.message_clips_removed, count, count)
    }
    UserMessageKind.TOO_LATE_TO_CANCEL -> stringResource(R.string.message_too_late_to_cancel, title)
    UserMessageKind.CANCEL_UNDONE -> stringResource(R.string.message_cancel_undone, title)
    UserMessageKind.WORD_CLIP_GONE -> stringResource(R.string.message_word_clip_gone)
  }
}

@Preview
@Composable
private fun ConnectAgainBannerPreview() {
  PhonoTheme { ConnectAgainBanner(visible = true, onConnectAgain = {}) }
}

@Preview
@Composable
private fun ShellBottomBarPreview() {
  PhonoTheme { ShellBottomBar(current = Tab.LEARN, onSelect = {}) }
}
