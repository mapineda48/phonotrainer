package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.StatusLine
import com.mapineda48.phonotrainer.core.ui.StatusTone
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.RequestRow
import com.mapineda48.phonotrainer.core.ui.component.RequestRowState

/**
 * What the Requests screen's controls ask for; the route turns each into a ViewModel call, a navigation or the system.
 * [onAction] receives an action already confirmed when it needed it ("Cancel", "Remove").
 */
internal class RequestsActions(
  val onBack: () -> Unit = {},
  val onAdd: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onOpen: (Request) -> Unit = {},
  val onWatch: (Request) -> Unit = {},
  val onAction: (Request, RequestAction) -> Unit = { _, _ -> },
  val onBrowserExpanded: (Boolean) -> Unit = {},
  val onHide: (JobId) -> Unit = {},
  val onConnectAgain: () -> Unit = {},
  val onWhy: () -> Unit = {},
)

/** The confirmation open on screen: the request and the action it confirms (saved across a recreate). */
private data class Pending(val requestId: String, val action: String)

/**
 * Requests (spec F-20, design 10.2 P-03, render `17`): top bar with "Add a clip", the lede, the connection line, the
 * queue's pause as a header notice, then the groups in the spec's order (each a kicker head with its count and the
 * request cards of design 9.19) and "Started on your computer", collapsed. Empty: the illustration, "Nothing in
 * progress." and "Add a clip". The queue unreadable: a caution notice with "Try again", never a cleared queue.
 */
@Composable
internal fun RequestsScreen(state: RequestsUiState, actions: RequestsActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.requests_title)
  val listState = rememberLazyListState()
  var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
  val all = state.groups.flatMap { it.requests }
  val pending = pendingId?.let { id -> all.firstOrNull { it.id.value == id } }
    ?.let { request -> RequestAction.entries.firstOrNull { it.name == pendingAction }?.takeIf { it in request.actions }?.let { request to it } }
  val onRowAction: (Request, RequestAction) -> Unit = { request, action ->
    when (RequestsRules.route(action)) {
      ActionRoute.CONFIRM_CANCEL, ActionRoute.CONFIRM_REMOVE -> {
        pendingId = request.id.value
        pendingAction = action.name
      }
      else -> actions.onAction(request, action)
    }
  }
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    PhonoTopBar(
      title = title,
      onBack = actions.onBack,
      backLabel = stringResource(R.string.requests_back),
      scrolled = listState.canScrollBackward,
      actions = {
        PhonoIconButton(
          icon = PhonoIcons.Add,
          contentDescription = stringResource(R.string.requests_add),
          onClick = actions.onAdd,
          modifier = Modifier.testTag(RequestsTags.LINK_ADD),
        )
      },
    )
    if (state.load == Load.LOADING) {
      Column(Modifier.fillMaxSize().padding(horizontal = PhonoSpacing.gutter)) {
        Lede()
        Box(Modifier.testTag(RequestsTags.LOADING)) {
          LoadingGate(loading = true, label = stringResource(R.string.requests_loading)) {}
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(RequestsTags.LIST),
        contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s24),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      ) {
        item(key = "lede") { Lede() }
        item(key = "line") { ConnectionStateLine(state.status, Modifier.testTag(RequestsTags.LINE)) }
        if (state.paused) item(key = "paused") { PausedNotice(state.status, actions) }
        when {
          state.load == Load.FAILED -> item(key = "error") { QueueError(actions.onRetry) }
          state.empty -> item(key = "empty") {
            Box(Modifier.testTag(RequestsTags.EMPTY)) {
              EmptyState(
                illustration = PhonoIllustrations.RequestsEmpty,
                title = stringResource(R.string.requests_empty_title),
                body = stringResource(R.string.requests_empty_body),
                actions = listOf(EmptyStateAction(stringResource(R.string.requests_add), actions.onAdd)),
              )
            }
          }
          else -> {
            state.groups.forEach { group -> group(group, state, actions, onRowAction) }
            browser(state, actions)
          }
        }
      }
    }
  }
  pending?.let { (request, action) ->
    val close = {
      pendingId = null
      pendingAction = null
    }
    val confirm = {
      close()
      actions.onAction(request, action)
    }
    when (action) {
      RequestAction.CANCEL -> CancelDialog(request, state.status is ComputerStatus.Connected, confirm, close)
      else -> RemoveDialog(request, confirm, close)
    }
  }
}

@Composable
private fun Lede() {
  PhonoText(
    stringResource(R.string.requests_lede),
    style = MaterialTheme.typography.bodyMedium,
    color = PhonoTheme.colors.ink2,
    modifier = Modifier.padding(bottom = PhonoSpacing.s4),
  )
}

/** The queue's pause (architecture 8.4, design 9.19 "header notice"): what is wrong and the one way out. */
@Composable
private fun PausedNotice(status: ComputerStatus, actions: RequestsActions) {
  val (label, onClick) = when (status) {
    ComputerStatus.ConnectAgain -> stringResource(R.string.requests_paused_connect_again) to actions.onConnectAgain
    is ComputerStatus.DifferentComputer -> stringResource(R.string.requests_paused_details) to actions.onWhy
    else -> stringResource(R.string.requests_paused_why) to actions.onWhy
  }
  Notice(
    body = stringResource(R.string.requests_paused),
    kind = NoticeKind.CAUTION,
    lead = connectionWords(status) + ".",
    modifier = Modifier.testTag(RequestsTags.PAUSED),
    action = {
      PhonoButton(
        text = label,
        onClick = onClick,
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(RequestsTags.PAUSED_ACTION),
      )
    },
  )
}

/** The queue could not be read (spec F-20 States, Error): what happened, that nothing was lost, and "Try again". */
@Composable
private fun QueueError(onRetry: () -> Unit) {
  Notice(
    body = stringResource(R.string.requests_error_body),
    kind = NoticeKind.CAUTION,
    lead = stringResource(R.string.requests_error),
    modifier = Modifier.testTag(RequestsTags.ERROR),
    action = {
      PhonoButton(
        text = stringResource(R.string.requests_try_again),
        onClick = onRetry,
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Replay,
        modifier = Modifier.testTag(RequestsTags.RETRY),
      )
    },
  )
}

/** One group: its head (icon, words, italic count, rule) and its cards. */
private fun LazyListScope.group(
  group: RequestGroup,
  state: RequestsUiState,
  actions: RequestsActions,
  onRowAction: (Request, RequestAction) -> Unit,
) {
  item(key = "group:" + group.kind.name) {
    Kicker(
      text = stringResource(group.kind.label),
      count = group.requests.size.toString(),
      icon = group.kind.icon,
      modifier = Modifier.padding(top = PhonoSpacing.s12).testTag(RequestsTags.group(group.kind.name)),
    )
  }
  items(group.requests, key = { "request:" + it.id.value }) { request ->
    Box(Modifier.testTag(RequestsTags.request(request.id))) {
      RequestRow(
        state = RequestRowState(request, status = state.status, capabilities = state.capabilities, now = state.now),
        onAction = { onRowAction(request, it) },
        onOpen = { actions.onOpen(request) },
        onWatch = { actions.onWatch(request) },
      )
    }
  }
}

/**
 * "Started on your computer" (spec F-20 item 1, F-20-AC8): the browser's jobs, collapsed by default, read-only, a
 * failed one with "Hide on this phone" only. Its count is the group's own; Home never counts them.
 */
private fun LazyListScope.browser(state: RequestsUiState, actions: RequestsActions) {
  if (state.browser.isEmpty()) return
  item(key = "browser") {
    val open = state.browserExpanded
    val label = stringResource(R.string.requests_group_browser)
    val toggleLabel = stringResource(if (open) R.string.requests_browser_hide_label else R.string.requests_browser_show_label)
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s12).testTag(RequestsTags.BROWSER),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Kicker(text = label, count = state.browser.size.toString(), icon = PhonoIcons.SiteComputer, modifier = Modifier.weight(1f))
      PhonoButton(
        text = stringResource(if (open) R.string.requests_browser_hide_group else R.string.requests_browser_show),
        onClick = { actions.onBrowserExpanded(!open) },
        kind = PhonoButtonKind.QUIET,
        trailingIcon = if (open) PhonoIcons.ChevronUp else PhonoIcons.ChevronDown,
        modifier = Modifier
          .semantics { contentDescription = toggleLabel }
          .testTag(RequestsTags.BROWSER_TOGGLE),
      )
    }
  }
  if (!state.browserExpanded) return
  item(key = "browser-note") {
    PhonoText(stringResource(R.string.requests_browser_note), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
  }
  items(state.browser, key = { "job:" + it.id.value }) { job -> BrowserJobRow(job, actions) }
}

@Composable
private fun BrowserJobRow(job: ComputerJob, actions: RequestsActions) {
  val failed = RequestsRules.failed(job)
  PaperCard(modifier = Modifier.testTag(RequestsTags.browserJob(job.id))) {
    PhonoText(
      job.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.requests_browser_untitled),
      style = MaterialTheme.typography.titleMedium,
      color = PhonoTheme.colors.ink,
    )
    StatusLine(
      text = when {
        failed -> stringResource(R.string.requests_browser_failed)
        job.status == RemoteStatus.RUNNING -> stringResource(R.string.requests_browser_running, job.percent.coerceIn(0, 100))
        else -> stringResource(R.string.requests_browser_queued)
      },
      tone = if (failed) StatusTone.CRITICAL else StatusTone.ACTIVE,
      icon = when {
        failed -> PhonoIcons.StatusAttention
        job.status == RemoteStatus.RUNNING -> PhonoIcons.StatusAnalyzing
        else -> PhonoIcons.StatusWaiting
      },
    )
    if (failed) {
      PhonoButton(
        text = stringResource(R.string.requests_browser_hide),
        onClick = { actions.onHide(job.id) },
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(RequestsTags.hide(job.id)),
      )
    }
  }
  Box(Modifier.height(PhonoSpacing.s2))
}
