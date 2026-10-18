package com.mapineda48.phonotrainer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.mapineda48.phonotrainer.core.designsystem.brand.PhonoMarkImage
import com.mapineda48.phonotrainer.core.designsystem.component.DoubleRule
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTrace
import com.mapineda48.phonotrainer.core.designsystem.trace.sampleContour
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCard
import com.mapineda48.phonotrainer.core.ui.component.RequestRowCopy

/** What Home's controls ask for; the route turns each into a ViewModel call or a navigation. */
internal class HomeActions(
  val onSettings: () -> Unit = {},
  val onRefresh: () -> Unit = {},
  val onDismissNotice: () -> Unit = {},
  /** A status card control; [RequestAction] is the inline action of the request that needs attention. */
  val onConnection: (ConnectionAction, RequestAction?) -> Unit = { _, _ -> },
  val onConnect: () -> Unit = {},
  val onLearn: () -> Unit = {},
  val onAdd: () -> Unit = {},
  val onPaste: () -> Unit = {},
  val onChooseFile: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onStartToday: () -> Unit = {},
  val onAnotherSet: () -> Unit = {},
  val onKeepWatching: (ShortId) -> Unit = {},
  val onWatchClip: (JobId) -> Unit = {},
  val onOpenClip: (JobId) -> Unit = {},
  val onAllClips: () -> Unit = {},
  val onChange: (ChangeLabel, Int) -> Unit = { _, _ -> },
  val onPractice: () -> Unit = {},
  val onSaved: () -> Unit = {},
  val onInsights: () -> Unit = {},
)

/**
 * Home (spec F-03, design 9.16 and 10.1 T1, renders `01`–`03`): the masthead (stylus P, wordmark, Settings, double rule,
 * dateline, lede), then the blocks of F-03 in their order ([HomeRules.blocks]) 20 dp apart, in one scrolling column
 * whose saveable position Back from the feed restores (F-03-AC8). Pull to refresh is "Sync now" when connected;
 * otherwise it says "Your computer isn't reachable right now." and changes nothing else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.home_title)
  val scroll = rememberScrollState()
  val pull = rememberPullToRefreshState()
  val syncLabel = stringResource(R.string.home_refresh_action)
  PullToRefreshBox(
    isRefreshing = state.refreshing,
    onRefresh = actions.onRefresh,
    state = pull,
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .testTag(HomeTags.REFRESH)
      // TalkBack users cannot pull: the same "Sync now" is the status card's action and this page action.
      .semantics { customActions = listOf(CustomAccessibilityAction(syncLabel) { actions.onRefresh(); true }) },
    indicator = { RefreshTrace(pull, state.refreshing, Modifier.align(Alignment.TopCenter)) },
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scroll)
        .testTag(HomeTags.PAGE)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = PhonoSpacing.gutter)
        .padding(bottom = PhonoSpacing.s32),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.sectionGap),
    ) {
      Masthead(state, actions)
      state.notice?.let { NoticeLine(it) }
      HomeRules.blocks(state).forEach { block ->
        Box(Modifier.fillMaxWidth().testTag(HomeTags.block(block))) {
          when (block) {
            HomeBlock.STATUS -> StatusBlock(state, actions)
            HomeBlock.TEACHING -> TeachingBlock(actions)
            HomeBlock.EMPTY -> EmptyBlock(actions)
            HomeBlock.TODAY -> TodayBlock(state.today, actions)
            HomeBlock.KEEP_WATCHING -> state.keepWatching?.let { KeepWatchingBlock(it, actions) }
            HomeBlock.ADD -> AddBlock(actions)
            HomeBlock.NEW -> NewBlock(state.newOnPhone, actions)
            HomeBlock.ERROR -> ErrorBlock(actions)
            HomeBlock.SHELF -> ShelfBlock(state, actions)
            HomeBlock.CHANGES -> ChangesBlock(state.changes, actions)
            HomeBlock.PRACTICE -> PracticeBlock(state.practice, actions)
            HomeBlock.INSIGHTS -> state.insights?.let { InsightsBlock(it, actions) }
          }
        }
      }
    }
  }
}

/** Design 9.16 block 1: the stylus P, the wordmark "Phono*Trainer*", Settings; the double rule; dateline and lede. */
@Composable
private fun Masthead(state: HomeUiState, actions: HomeActions) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val title = stringResource(R.string.home_title)
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
      PhonoMarkImage(size = MARK_SIZE)
      val phono = stringResource(R.string.home_wordmark_phono)
      val trainer = stringResource(R.string.home_wordmark_trainer)
      Text(
        text = buildAnnotatedString {
          append(phono)
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(trainer) }
        },
        style = MaterialTheme.typography.displaySmall,
        color = colors.ink,
        modifier = Modifier
          .weight(1f)
          .clearAndSetSemantics {
            contentDescription = title
            heading()
          },
      )
      PhonoIconButton(
        icon = PhonoIcons.Settings,
        contentDescription = stringResource(R.string.home_settings),
        onClick = actions.onSettings,
        modifier = Modifier.testTag(HomeTags.SETTINGS),
      )
    }
    DoubleRule()
    PhonoText(
      remember(state.now, state.zone, resources) { HomeCopy.dateline(state.now, state.zone, resources) },
      style = PhonoTheme.type.accentItalic,
      color = colors.ink2,
    )
    PhonoText(stringResource(R.string.home_lede), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
  }
}

/** "Your computer isn't reachable right now." after a pull while the computer is out of reach (a polite live line). */
@Composable
private fun NoticeLine(notice: HomeNotice) {
  val body = when (notice) {
    HomeNotice.NOT_REACHABLE -> stringResource(R.string.home_refresh_not_reachable)
  }
  Notice(body = body, live = true, modifier = Modifier.testTag(HomeTags.NOTICE))
}

/** F-03 item 2: the shared status card with Home's extras; the inline action comes from the failed request's copy. */
@Composable
private fun StatusBlock(state: HomeUiState, actions: HomeActions) {
  val resources = LocalResources.current
  val attentionAction = remember(state.attention, state.capabilities, resources) {
    state.attention?.let { RequestRowCopy.failureOf(it, state.capabilities, resources)?.actions?.firstOrNull() }
  }
  ConnectionStatusCard(
    status = state.status,
    sync = state.sync,
    counts = state.counts,
    transfers = null,
    onAction = { actions.onConnection(it, attentionAction) },
    extras = state.extras.copy(attentionAction = attentionAction),
  )
}

/**
 * The pull indicator: the stylus trace drawn as far as the pull goes, then held while the pass runs (design 8: the
 * pen moves, the paper doesn't); with reduced motion it is the words "Syncing…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshTrace(pull: PullToRefreshState, refreshing: Boolean, modifier: Modifier) {
  val fraction = if (refreshing) 1f else pull.distanceFraction.coerceIn(0f, 1f)
  if (fraction <= 0f) return
  Box(modifier.fillMaxWidth().padding(horizontal = PhonoSpacing.gutter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
    if (PhonoTheme.motion.reduced) {
      if (refreshing) PhonoText(stringResource(R.string.home_refresh_syncing), style = MaterialTheme.typography.labelLarge)
    } else {
      StylusTrace(sampleContour(seed = 3), progress = fraction, modifier = Modifier.fillMaxWidth().height(TRACE_HEIGHT), showNib = !refreshing)
    }
  }
}

private val MARK_SIZE = 36.dp
private val TRACE_HEIGHT = 24.dp
