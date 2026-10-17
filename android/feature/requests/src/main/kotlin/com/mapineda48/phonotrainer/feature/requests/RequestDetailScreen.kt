package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.StatusLine
import com.mapineda48.phonotrainer.core.ui.StatusTone
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.RequestActionGrid
import com.mapineda48.phonotrainer.core.ui.component.RequestRowCopy
import com.mapineda48.phonotrainer.core.ui.component.RequestRowStages
import com.mapineda48.phonotrainer.core.ui.component.RequestRowState
import com.mapineda48.phonotrainer.core.ui.component.RequestRowTags
import com.mapineda48.phonotrainer.core.ui.component.RequestRowWords
import com.mapineda48.phonotrainer.core.ui.component.RequestStageState

/** What the request detail's controls ask for; [onAction] receives an action already confirmed when it needed it. */
internal class DetailActions(
  val onBack: () -> Unit = {},
  val onSeeRequests: () -> Unit = {},
  val onAction: (RequestAction) -> Unit = {},
  val onWatch: () -> Unit = {},
  val onLinkExpanded: (Boolean) -> Unit = {},
  val onDetailsExpanded: (Boolean) -> Unit = {},
)

/**
 * The request detail (spec F-20 item 2, design 10.2 P-04, render `18`): top bar "Request" and the lede; a failure's
 * alert card on top, with "Show details" (the computer's cleaned message, never escape codes, SH-AC9); the source card
 * with the milestone timeline; the request's state and its actions; and, once the computer has it, "What your computer
 * is doing": the estimate, the first-run note and the six stages with their status words and teaching lines.
 */
@Composable
internal fun RequestDetailScreen(state: RequestDetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.detail_title)
  val scroll = rememberScrollState()
  var pending by rememberSaveable { mutableStateOf<String?>(null) }
  val request = state.request
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    PhonoTopBar(title = title, onBack = actions.onBack, backLabel = stringResource(R.string.detail_back), scrolled = scroll.canScrollBackward)
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scroll)
        .padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
    ) {
      PhonoText(stringResource(R.string.detail_lede), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
      when {
        state.load == Load.LOADING -> Box(Modifier.testTag(RequestsTags.DETAIL_LOADING)) {
          LoadingGate(loading = true, label = stringResource(R.string.detail_loading)) {}
        }
        request == null -> Removed(actions)
        else -> Detail(state, request, actions) { action ->
          when (RequestsRules.route(action)) {
            ActionRoute.CONFIRM_CANCEL, ActionRoute.CONFIRM_REMOVE -> pending = action.name
            ActionRoute.DETAILS -> actions.onDetailsExpanded(!state.detailsExpanded)
            else -> actions.onAction(action)
          }
        }
      }
    }
  }
  val confirming = request?.let { r -> RequestAction.entries.firstOrNull { it.name == pending }?.takeIf { it in r.actions } }
  if (request != null && confirming != null) {
    val close = { pending = null }
    val confirm = {
      pending = null
      actions.onAction(confirming)
    }
    when (confirming) {
      RequestAction.CANCEL -> CancelDialog(request, state.status is ComputerStatus.Connected, confirm, close)
      else -> RemoveDialog(request, confirm, close)
    }
  }
}

/** The request is gone (removed here or on another screen): say so, and the way back to Requests. */
@Composable
private fun Removed(actions: DetailActions) {
  Notice(
    body = stringResource(R.string.detail_removed_body),
    lead = stringResource(R.string.detail_removed_title),
    modifier = Modifier.testTag(RequestsTags.DETAIL_REMOVED),
    action = {
      PhonoButton(text = stringResource(R.string.detail_see_requests), onClick = actions.onSeeRequests, kind = PhonoButtonKind.SECONDARY)
    },
  )
}

@Composable
private fun Detail(state: RequestDetailUiState, request: Request, actions: DetailActions, onAction: (RequestAction) -> Unit) {
  val resources = LocalResources.current
  val row = RequestRowState(request, status = state.status, capabilities = state.capabilities, now = state.now)
  val words = RequestRowCopy.words(row, state.now, resources)
  if (words.alert) StateCard(row, words, state, actions, onAction)
  SourceCard(state, request, words, actions.onLinkExpanded)
  if (!words.alert) StateCard(row, words, state, actions, onAction)
  if (RequestDetailRules.showsStages(request, words.alert)) Stages(request)
}

/** The source card (render `18`): the clip's title, the site and link ("Show it all"), or the file; the timeline. */
@Composable
private fun SourceCard(state: RequestDetailUiState, request: Request, words: RequestRowWords, onLinkExpanded: (Boolean) -> Unit) {
  val colors = PhonoTheme.colors
  PaperCard(modifier = Modifier.testTag(RequestsTags.DETAIL_SOURCE)) {
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      Icon(painterResource(words.sourceIcon), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconBar))
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        PhonoText(
          request.title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = colors.ink,
          modifier = Modifier.semantics { heading() },
        )
        PhonoText(sourceLine(state, request), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        val link = request.link
        if (request.kind == RequestKind.LINK && link != null && RequestRowCopy.shortLink(link) != bareLink(link)) {
          LinkButton(
            text = stringResource(if (state.linkExpanded) R.string.detail_show_less else R.string.detail_show_all),
            onClick = { onLinkExpanded(!state.linkExpanded) },
            modifier = Modifier.testTag(RequestsTags.DETAIL_LINK_TOGGLE),
          )
        }
      }
    }
    Timeline(state, request)
  }
}

private fun bareLink(link: String): String = link.trim().substringAfter("://").removePrefix("www.")

/** "YouTube · youtube.com/watch?v=…Qx7" (or the whole link when "Show it all" is on); a file's name and size. */
@Composable
private fun sourceLine(state: RequestDetailUiState, request: Request): String {
  val resources = LocalResources.current
  return when (request.kind) {
    RequestKind.LINK -> listOfNotNull(
      stringResource(RequestRowCopy.siteWord(request.site)),
      request.link?.let { if (state.linkExpanded) it.trim() else RequestRowCopy.shortLink(it) },
    ).joinToString(" · ")
    RequestKind.FILE -> listOfNotNull(
      request.fileName?.takeIf { it.isNotBlank() && it != request.title },
      stringResource(if (RequestRowCopy.isVideo(request) == false) R.string.add_file_kind_audio else R.string.add_file_kind_video),
      request.fileBytes?.takeIf { it > 0 }?.let { RequestRowCopy.bytes(it, resources) },
    ).joinToString(" · ")
  }
}

/** The milestone timeline (render `18`): dots on a 2 dp ink spine, each milestone with its time. */
@Composable
private fun Timeline(state: RequestDetailUiState, request: Request) {
  val rows = RequestDetailRules.timeline(request)
  Column(Modifier.fillMaxWidth().padding(top = PhonoSpacing.s8).testTag(RequestsTags.DETAIL_TIMELINE)) {
    rows.forEachIndexed { index, row ->
      MilestoneLine(row, request, state, first = index == 0, last = index == rows.lastIndex)
    }
  }
}

@Composable
private fun MilestoneLine(row: MilestoneRow, request: Request, state: RequestDetailUiState, first: Boolean, last: Boolean) {
  val colors = PhonoTheme.colors
  val label = if (row.milestone == Milestone.ANALYZING && row.state == StepState.CURRENT && request.progress?.percent != null) {
    stringResource(R.string.detail_milestone_analyzing_percent, request.progress?.percent?.coerceIn(0, 100) ?: 0)
  } else {
    stringResource(row.milestone.label)
  }
  val time = row.at?.let { RequestDetailRules.time(it, state.now, state.zone) }
  val timeWords = when (row.state) {
    StepState.DONE -> time
    StepState.CURRENT -> time?.let { stringResource(R.string.detail_since, it) }
    else -> null
  }
  val spoken = when (row.state) {
    StepState.DONE -> if (time != null) stringResource(R.string.detail_milestone_done, label, time) else label
    StepState.CURRENT -> stringResource(R.string.detail_milestone_current, label)
    else -> stringResource(R.string.detail_milestone_next, label)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .heightIn(min = PhonoSizes.minTarget)
      .semantics(mergeDescendants = true) { contentDescription = spoken }
      .testTag(RequestsTags.milestone(row.milestone.name)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s14),
  ) {
    Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
      Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).width(2.dp).background(if (first) colors.sheet else colors.ink))
        Box(Modifier.weight(1f).width(2.dp).background(if (last) colors.sheet else colors.ink))
      }
      Dot(row)
    }
    PhonoText(
      label,
      style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (row.state == StepState.NEXT) FontWeight.Normal else FontWeight.Bold),
      color = colors.ink,
      modifier = Modifier.weight(1f),
    )
    timeWords?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
  }
}

/** A milestone's dot: done = ink disc + check; current = ring + loader; next = outline with the place icon. */
@Composable
private fun Dot(row: MilestoneRow) {
  val colors = PhonoTheme.colors
  val (fill, ring, icon) = when (row.state) {
    StepState.DONE -> Triple(colors.ink, null, PhonoIcons.StepDone)
    StepState.CURRENT -> Triple(colors.sheet, colors.ink, PhonoIcons.StatusAnalyzing)
    else -> Triple(colors.sheet, colors.outline, row.milestone.icon)
  }
  Box(
    modifier = Modifier
      .size(32.dp)
      .clip(CircleShape)
      .background(fill)
      .then(if (ring != null) Modifier.border(PhonoSizes.ring, ring, CircleShape) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(icon), null, tint = if (row.state == StepState.DONE) colors.onInk else colors.ink, modifier = Modifier.size(16.dp))
  }
}

/**
 * The request's state and its actions: the alert card of a failure (title, body, "Show details") or a plain card
 * (state line, reasons, bar), with the actions grid of the request card.
 */
@Composable
private fun StateCard(
  row: RequestRowState,
  words: RequestRowWords,
  state: RequestDetailUiState,
  actions: DetailActions,
  onAction: (RequestAction) -> Unit,
) {
  val colors = PhonoTheme.colors
  val request = row.request
  PaperCard(
    modifier = Modifier.testTag(RequestsTags.DETAIL_CARD),
    variant = if (words.alert) CardVariant.ALERT else CardVariant.PLAIN,
  ) {
    StatusLine(
      text = words.state,
      tone = if (words.stateIcon == PhonoIcons.StatusAttention) StatusTone.CRITICAL else StatusTone.ACTIVE,
      icon = words.stateIcon,
      style = PhonoTheme.type.requestState,
      modifier = Modifier.testTag(RequestRowTags.STATE),
    )
    words.body?.let { PhonoText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.testTag(RequestRowTags.BODY)) }
    words.reasons.forEach { reason ->
      Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8), modifier = Modifier.testTag(RequestRowTags.REASON)) {
        Icon(painterResource(reason.icon), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconInline))
        PhonoText(reason.text, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
    }
    words.progress?.let { PhonoProgressBar(progress = it, modifier = Modifier.testTag(RequestRowTags.PROGRESS)) }
    RequestActionGrid(
      state = row,
      actions = words.actions,
      alert = words.alert,
      onAction = onAction,
      onWatch = actions.onWatch.takeIf { request.state == RequestState.ON_PHONE },
    )
    if (words.alert) Details(state, request, offered = RequestAction.SHOW_DETAILS in words.actions, actions)
  }
}

/**
 * "Show details" (spec F-20 item 2, 8.6 rules): the computer's cleaned message (server-contract 8.3: no escape codes, no
 * paths; SH-AC9, SH-AC17) and the downloader's version when the computer gave it. Always reachable on a failure; when
 * the failure's actions list "Show details" its button toggles it, otherwise this quiet toggle does.
 */
@Composable
private fun Details(state: RequestDetailUiState, request: Request, offered: Boolean, actions: DetailActions) {
  val colors = PhonoTheme.colors
  val error = request.lastError
  if (!offered) {
    LinkButton(
      text = stringResource(if (state.detailsExpanded) R.string.detail_hide_details else R.string.detail_show_details),
      onClick = { actions.onDetailsExpanded(!state.detailsExpanded) },
      modifier = Modifier.testTag(RequestsTags.DETAIL_DETAILS_TOGGLE),
    )
  }
  if (!state.detailsExpanded) return
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      .background(colors.galley)
      .padding(PhonoSpacing.s12)
      .semantics(mergeDescendants = true) { }
      .testTag(RequestsTags.DETAIL_DETAILS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    PhonoText(
      error?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_details_none),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink,
    )
    error?.downloaderVersion?.takeIf { it.isNotBlank() }?.let {
      PhonoText(stringResource(R.string.detail_downloader, it), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
    }
  }
}

/**
 * "What your computer is doing" (spec F-20 item 2, render `18`): the estimate, the first-run note while it is on the
 * computer, and the stages as rows: a node, the full name, the status word and detail ("in progress · phrase 3 of
 * 10"), and the teaching line; the current row on galley with a 3 dp ink bar.
 */
@Composable
private fun Stages(request: Request) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val stages = RequestRowStages.of(request, resources)
  Column(Modifier.fillMaxWidth().testTag(RequestsTags.DETAIL_DOING), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    PhonoText(
      stringResource(R.string.detail_doing),
      style = MaterialTheme.typography.headlineMedium,
      color = colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    if (RequestDetailRules.onComputer(request)) {
      // The clip's duration is not in Request yet (CR-WP22-1): a link reads the "once it's downloaded" line.
      val minutes = RequestDetailRules.estimateMinutes(null)
      when {
        minutes != null -> PhonoText(
          stringResource(R.string.detail_estimate, resources.getQuantityString(R.plurals.detail_minutes, minutes, minutes)),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(RequestsTags.DETAIL_ESTIMATE),
        )
        RequestDetailRules.linkEstimate(request, null) -> PhonoText(
          stringResource(R.string.detail_estimate_link),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(RequestsTags.DETAIL_ESTIMATE),
        )
      }
      Notice(body = stringResource(R.string.detail_first_run), modifier = Modifier.testTag(RequestsTags.DETAIL_FIRST_RUN))
    }
    stages.forEachIndexed { index, stage -> StageRow(stage, index + 1) }
  }
}

@Composable
private fun StageRow(stage: RequestStageState, number: Int) {
  val colors = PhonoTheme.colors
  val current = stage.state == StepState.CURRENT
  val name = stringResource(RequestRowStages.name(stage.stage))
  val status = listOfNotNull(stringResource(RequestRowStages.statusWord(stage.state)), stage.detail).joinToString(" · ")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .clip(MaterialTheme.shapes.medium)
      .background(if (current) colors.galley else colors.page)
      .semantics(mergeDescendants = true) { }
      .testTag(RequestsTags.stage(stage.stage)),
  ) {
    Box(Modifier.width(3.dp).fillMaxHeight().background(if (current) colors.ink else colors.page))
    Row(
      modifier = Modifier.padding(PhonoSpacing.s10),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      StageNode(stage.state, number)
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        PhonoText(
          stringResource(R.string.detail_stage_row, name, status),
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (stage.state == StepState.NEXT) FontWeight.Normal else FontWeight.Bold),
          color = colors.ink,
        )
        if (stage.state != StepState.DONE) {
          PhonoText(stringResource(RequestRowStages.teaching(stage.stage)), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
      }
    }
  }
}

/** A stage's node (design 9.19): done = ink disc + check; current = ring + loader; next = outline with its number. */
@Composable
private fun StageNode(state: StepState, number: Int) {
  val colors = PhonoTheme.colors
  val done = state == StepState.DONE
  Box(
    modifier = Modifier
      .size(32.dp)
      .clip(CircleShape)
      .background(if (done) colors.ink else colors.sheet)
      .then(if (done) Modifier else Modifier.border(PhonoSizes.ring, if (state == StepState.CURRENT) colors.ink else colors.outline, CircleShape)),
    contentAlignment = Alignment.Center,
  ) {
    when (state) {
      StepState.DONE -> Icon(painterResource(PhonoIcons.StepDone), null, tint = colors.onInk, modifier = Modifier.size(16.dp))
      StepState.CURRENT -> Icon(painterResource(PhonoIcons.StatusAnalyzing), null, tint = colors.ink, modifier = Modifier.size(16.dp))
      StepState.SKIPPED -> Icon(painterResource(PhonoIcons.StepSkipped), null, tint = colors.ink, modifier = Modifier.size(16.dp))
      StepState.FAILED -> Icon(painterResource(PhonoIcons.Close), null, tint = colors.ink, modifier = Modifier.size(16.dp))
      StepState.CANCELLED -> Icon(painterResource(PhonoIcons.StatusCancelled), null, tint = colors.ink, modifier = Modifier.size(16.dp))
      StepState.NEXT -> PhonoText("$number", style = MaterialTheme.typography.labelMedium, color = colors.ink)
    }
  }
}
