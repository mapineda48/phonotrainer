package com.mapineda48.phonotrainer.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
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
import com.mapineda48.phonotrainer.core.designsystem.component.BottomBarItem
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBottomBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSnackbar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.texture.paperGrain
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.EntryInstaller
import com.mapineda48.phonotrainer.core.navigation.PhonoNavSerialization
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.LocalTalkBackOn
import com.mapineda48.phonotrainer.core.ui.extension.PhonoAppRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
 * The app shell (architecture 16.2, 3.1; design 9.23, 9.24): every tab's back stack decorated on its own (so switching
 * tabs keeps each tab's saved state and ViewModels, Android review H1) and the overlay scene strategy, drawn in the
 * [ShellFrame]: the bottom bar, the one host of `UserMessages` snackbars, the global "Connect again" banner and the
 * polite live region of the `Announcer`.
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

  val status by inputs.computerStatus.collectAsStateWithLifecycle()
  val message by inputs.userMessages.current.collectAsStateWithLifecycle()
  val announcement by inputs.announcer.latest.collectAsStateWithLifecycle()

  ShellFrame(
    current = current,
    onSelectTab = navigator::switchTab,
    connectAgain = status == ComputerStatus.ConnectAgain,
    onConnectAgain = { navigator.navigate(ConnectKey(ConnectMode.CONNECT_AGAIN)) },
    message = message,
    onMessageDone = inputs.userMessages::consumed,
    announcement = announcement,
    modifier = modifier,
  ) {
    NavDisplay(
      entries = shown,
      sceneStrategies = listOf(BottomSheetSceneStrategy(), DialogSceneStrategy()),
      onBack = navigator::back,
    )
  }
}

/**
 * The shell's frame (design 9.23, 9.24, 10; decision I-12), stateless: the page with its paper grain, the
 * "Connect again" banner on top while [connectAgain], the destinations ([content]), the snackbar of [message] above
 * the bottom bar, the announcer's polite live region, and `PhonoBottomBar` on the bottom edge.
 *
 * The inset contract (architecture 16.5): the frame never pads a destination with the status-bar inset, so each
 * destination handles its own top (a top bar or a title behind the status bar, the feed full bleed). The bottom bar
 * draws its sheet behind the navigation bar and keeps its items above it, and the destinations see the bar's height
 * (navigation bar included) as consumed at the bottom, so their `navigationBarsPadding()` is zero and their
 * `imePadding()` counts from the top of the bar. While the banner shows, it pads the status bar itself and its height
 * is consumed at the top in the same way.
 */
@Composable
internal fun ShellFrame(
  current: Tab,
  onSelectTab: (Tab) -> Unit,
  connectAgain: Boolean,
  onConnectAgain: () -> Unit,
  message: UserMessage?,
  onMessageDone: (Long) -> Unit,
  announcement: String?,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  var bannerHeight by remember { mutableStateOf(0.dp) }
  var barHeight by remember { mutableStateOf(0.dp) }
  fun Int.asDp(): Dp = with(density) { toDp() }

  Column(modifier.fillMaxSize().background(PhonoTheme.colors.page).paperGrain()) {
    if (connectAgain) {
      ConnectAgainBanner(
        onConnectAgain = onConnectAgain,
        modifier = Modifier
          .onSizeChanged { bannerHeight = it.height.asDp() }
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
      )
    }
    Box(
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .consumeWindowInsets(PaddingValues(top = if (connectAgain) bannerHeight else 0.dp, bottom = barHeight)),
    ) {
      content()
      Box(
        Modifier.size(1.dp).testTag(ShellTags.ANNOUNCER).semantics {
          liveRegion = LiveRegionMode.Polite
          contentDescription = announcement.orEmpty()
        },
      )
      if (message != null) {
        UserMessageHost(
          message = message,
          onDone = onMessageDone,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding()
            .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s8),
        )
      }
    }
    ShellBottomBar(current, onSelectTab, Modifier.onSizeChanged { barHeight = it.height.asDp() })
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

/**
 * The bottom bar (design 9.24): `PhonoBottomBar` on the bottom edge, its sheet continued behind the navigation bar
 * (the gesture area) and its items kept clear of the system bars and cutouts.
 */
@Composable
private fun ShellBottomBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
  val items = Tab.entries.map { tab -> BottomBarItem(stringResource(tab.label()), tab.icon(), ShellTags.tab(tab)) }
  Box(modifier.fillMaxWidth().background(PhonoTheme.colors.sheet).testTag(ShellTags.BOTTOM_BAR)) {
    PhonoBottomBar(
      items = items,
      selectedIndex = current.ordinal,
      onSelect = { onSelect(Tab.entries[it]) },
      modifier = Modifier.windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
      ),
    )
  }
}

private fun Tab.label(): Int = when (this) {
  Tab.HOME -> R.string.tab_home
  Tab.WATCH -> R.string.tab_watch
  Tab.LEARN -> R.string.tab_learn
  Tab.PRACTICE -> R.string.tab_practice
}

private fun Tab.icon(): Int = when (this) {
  Tab.HOME -> PhonoIcons.TabHome
  Tab.WATCH -> PhonoIcons.TabWatch
  Tab.LEARN -> PhonoIcons.TabLearn
  Tab.PRACTICE -> PhonoIcons.TabPractice
}

/**
 * The global "Connect again" banner (design 9.23): a full-width caution notice with its action, drawn once by the
 * shell, above every destination, while this phone's credential is revoked.
 */
@Composable
internal fun ConnectAgainBanner(onConnectAgain: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s8)) {
    Notice(
      body = stringResource(R.string.banner_connect_again),
      kind = NoticeKind.CAUTION,
      modifier = Modifier.testTag(ShellTags.BANNER),
      action = {
        PhonoButton(
          text = stringResource(R.string.banner_connect_again_action),
          onClick = onConnectAgain,
          modifier = Modifier.testTag(ShellTags.BANNER_ACTION),
          kind = PhonoButtonKind.SECONDARY,
        )
      },
    )
  }
}

/**
 * Shows [message] as one `PhonoSnackbar` (design 9.23) with its "Dismiss" action: never on a timer shorter than 10 s,
 * and with TalkBack on it waits for the learner; then it reports the message done. Message ids are unique (decision
 * I-16), so the timer restarts for every message.
 */
@Composable
private fun UserMessageHost(message: UserMessage, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
  val talkBackOn = LocalTalkBackOn.current
  if (!talkBackOn) {
    LaunchedEffect(message.id) {
      delay(SNACKBAR_MS)
      onDone(message.id)
    }
  }
  PhonoSnackbar(
    message = messageText(message),
    actionLabel = stringResource(R.string.message_dismiss),
    onAction = { onDone(message.id) },
    modifier = modifier.testTag(ShellTags.SNACKBAR),
  )
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

@PreviewLightDark
@Composable
private fun ShellFramePreview() {
  PhonoAppRoot(settings = UserSettings()) {
    ShellFrame(
      current = Tab.LEARN,
      onSelectTab = {},
      connectAgain = true,
      onConnectAgain = {},
      message = UserMessage(1, UserMessageKind.REQUEST_REMOVED),
      onMessageDone = {},
      announcement = null,
    ) {}
  }
}
