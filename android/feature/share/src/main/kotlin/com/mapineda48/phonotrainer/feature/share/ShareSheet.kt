package com.mapineda48.phonotrainer.feature.share

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.brand.PhonoBrandArt
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoDialog
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SheetHandle
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.SheetShape
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.StatusLine
import com.mapineda48.phonotrainer.core.ui.StatusTone
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ConnectionLine
import com.mapineda48.phonotrainer.core.ui.component.FailureContext
import com.mapineda48.phonotrainer.core.ui.component.RequestFailureCopy
import com.mapineda48.phonotrainer.core.ui.component.ShareBridge
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind

/** What the sheet's controls ask for (the route wires them to [ShareViewModel] and the activity). */
@Immutable
data class ShareActions(
  val onAudioOnly: (Boolean) -> Unit = {},
  val onSubmit: () -> Unit = {},
  val onAnalyzeAgain: () -> Unit = {},
  /** "Cancel" and "Close": the sheet goes, nothing more is created. */
  val onClose: () -> Unit = {},
  /** Back and a tap on the scrim (a copy asks first). */
  val onBack: () -> Unit = {},
  val onOpenApp: () -> Unit = {},
  val onSeeRequests: () -> Unit = {},
  val onOpenDuplicate: (Duplicate) -> Unit = {},
  val onTryAudioOnly: () -> Unit = {},
  val onRemove: () -> Unit = {},
  val onKeepAdding: () -> Unit = {},
  val onStop: () -> Unit = {},
  val onNotNow: () -> Unit = {},
  val onNotifyMe: () -> Unit = {},
)

/**
 * The share confirmation sheet (spec 8.4, design 9.22): over the sharing app, a scrim and a bottom sheet with 28 dp top
 * corners that handles the navigation-bar inset itself (and never runs under the status bar) and scrolls its content
 * at large font sizes, the buttons always in view. Closing by itself is [ShareViewModel]'s (it follows the activity
 * even while it is in the background).
 */
@Composable
fun ShareSheet(state: ShareUiState, actions: ShareActions, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val title = stringResource(R.string.share_title)
  BackHandler(enabled = state.phase !is SharePhase.Closed) { actions.onBack() }
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Box(
      Modifier
        .fillMaxSize()
        .testTag(ShareTags.SCRIM)
        .background(colors.scrim)
        .clearAndSetSemantics { }
        .pointerInput(actions) { detectTapGestures { actions.onBack() } },
    )
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
      visibleState = shown,
      enter = slideInVertically(PhonoTheme.motion.enter()) { it / 3 } + fadeIn(PhonoTheme.motion.enter()),
      modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 24.dp),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .testTag(ShareTags.SHEET)
          .paneTitle(title)
          .clip(SheetShape)
          .background(colors.sheet)
          .pointerInput(Unit) { detectTapGestures { } }
          .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
          .padding(horizontal = PhonoSpacing.gutter),
      ) {
        SheetHandle(Modifier.align(Alignment.CenterHorizontally))
        Column(
          modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
        ) {
          Header(title)
          SheetBody(state, actions)
        }
        Buttons(state, actions, Modifier.padding(top = PhonoSpacing.s16, bottom = PhonoSpacing.s16))
      }
    }
  }
  Dialogs(state, actions)
}

@Composable
private fun Header(title: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    LauncherTile()
    PhonoText(
      title,
      style = MaterialTheme.typography.headlineLarge,
      color = PhonoTheme.colors.ink,
      modifier = Modifier.testTag(ShareTags.TITLE).semantics { heading() },
    )
  }
}

/** The launcher tile at 40 dp (design 9.22): the adaptive icon's layers, its 72 dp safe zone filling the tile. */
@Composable
private fun LauncherTile() {
  Box(
    Modifier.size(40.dp).clip(RoundedCornerShape(PhonoRadius.sort)).clearAndSetSemantics { },
    contentAlignment = Alignment.Center,
  ) {
    Image(painterResource(PhonoBrandArt.LauncherBackground), null, Modifier.requiredSize(60.dp), contentScale = ContentScale.Crop)
    Image(painterResource(PhonoBrandArt.LauncherForeground), null, Modifier.requiredSize(60.dp))
  }
}

@Composable
private fun ColumnScope.SheetBody(state: ShareUiState, actions: ShareActions) {
  val phase = state.phase
  val showBridge = phase !is SharePhase.Problem && phase !is SharePhase.DuplicateFound && phase !is SharePhase.Reading
  if (state.summary !is SharedSummary.Reading) SharedWell(state.summary)
  if (showBridge) {
    ShareBridge(state.bridge)
    ConnectionLine(state.status, checking = state.checking)
  }
  when (phase) {
    SharePhase.Reading, SharePhase.Closed -> Unit
    SharePhase.Confirm, SharePhase.Saving -> Options(state, actions)
    is SharePhase.Preparing -> Preparing(phase)
    is SharePhase.CopyFailed -> CopyFailedNotice(state, phase)
    is SharePhase.Done -> Done(phase)
    is SharePhase.Problem -> ProblemNotice(state, phase.problem)
    is SharePhase.DuplicateFound -> Notice(
      body = phase.duplicate.title.orEmpty(),
      lead = stringResource(R.string.share_duplicate),
      kind = NoticeKind.CAUTION,
      live = true,
      modifier = Modifier.testTag(ShareTags.NOTICE).oneNode(),
    )
  }
}

/** A notice is read as one node: its bold lead and its body together. */
private fun Modifier.oneNode(): Modifier = semantics(mergeDescendants = true) { }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedWell(summary: SharedSummary) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  var full by rememberSaveable { mutableStateOf(false) }
  val (icon, title, line) = when (summary) {
    is SharedSummary.Link -> Triple(siteIcon(summary.site), ShareCopy.siteTitle(summary.site, resources), null)
    is SharedSummary.OneFile -> Triple(
      if (summary.kind == SharedKind.VIDEO) PhonoIcons.SitePhoneFile else PhonoIcons.SitePhoneAudio,
      summary.name ?: stringResource(if (summary.kind == SharedKind.VIDEO) R.string.share_kind_video else R.string.share_kind_audio),
      ShareCopy.oneFileLine(summary.kind, summary.bytes, summary.durationMs, resources),
    )
    is SharedSummary.ManyFiles -> Triple(
      if (summary.videos > 0) PhonoIcons.SitePhoneFile else PhonoIcons.SitePhoneAudio,
      ShareCopy.manyFilesLine(summary.count, summary.videos, summary.audios, summary.bytes, resources),
      null,
    )
    SharedSummary.Reading -> return
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(ShareTags.SHARED)
      .oneNode()
      .clip(RoundedCornerShape(PhonoRadius.large))
      .background(colors.galley)
      .padding(PhonoSpacing.s12),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(44.dp).clip(RoundedCornerShape(PhonoRadius.medium)).background(colors.sheet),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
      PhonoText(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = colors.ink)
      line?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
      if (summary is SharedSummary.Link) {
        FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
          PhonoText(
            ShareFormat.link(summary.sent, full),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink2,
          )
          if (ShareFormat.isShortened(summary.sent)) {
            PhonoText(" · ", style = MaterialTheme.typography.bodyMedium, color = colors.ink2, modifier = Modifier.clearAndSetSemantics { })
            LinkButton(
              text = stringResource(if (full) R.string.share_show_less else R.string.share_show_all),
              onClick = { full = !full },
              modifier = Modifier.testTag(ShareTags.SHOW_ALL),
            )
          }
        }
      }
    }
  }
}

private fun siteIcon(site: Site): Int = when (site) {
  Site.YOUTUBE, Site.YOUTUBE_SHORTS -> PhonoIcons.SiteYoutube
  Site.X -> PhonoIcons.SiteX
  Site.OTHER -> PhonoIcons.SiteOther
}

@Composable
private fun Options(state: ShareUiState, actions: ShareActions) {
  val colors = PhonoTheme.colors
  Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
  if (state.audioOnlyOffered) {
    PhonoSwitch(
      label = stringResource(R.string.share_audio_only),
      checked = state.audioOnly,
      onCheckedChange = actions.onAudioOnly,
      description = stringResource(R.string.share_audio_only_body),
      enabled = state.phase is SharePhase.Confirm,
      modifier = Modifier.testTag(ShareTags.AUDIO_ONLY),
    )
  }
  if (state.largeFile) {
    StatusLine(
      stringResource(R.string.share_large_file),
      modifier = Modifier.testTag(ShareTags.LARGE_FILE),
      icon = PhonoIcons.Info,
    )
  }
  if (state.isLink && (state.summary as SharedSummary.Link).site == Site.OTHER) {
    Notice(stringResource(R.string.share_other_site), modifier = Modifier.testTag(ShareTags.SITE_NOTE).oneNode())
  }
  Row(Modifier.testTag(ShareTags.TIP), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Icon(painterResource(PhonoIcons.Tip), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconButton))
    PhonoText(stringResource(R.string.share_tip), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  }
  if (state.isLink) {
    PhonoText(
      stringResource(R.string.share_legal),
      style = MaterialTheme.typography.bodySmall,
      color = colors.ink2,
      modifier = Modifier.testTag(ShareTags.LEGAL),
    )
  }
}

@Composable
private fun Preparing(phase: SharePhase.Preparing) {
  val percent = phase.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
  val label = when {
    percent == null -> stringResource(R.string.share_preparing_unknown)
    phase.ids.size > 1 -> stringResource(R.string.share_preparing_files, percent)
    else -> stringResource(R.string.share_preparing, percent)
  }
  PhonoProgressBar(
    progress = phase.fraction,
    label = label,
    modifier = Modifier.testTag(ShareTags.PREPARING).semantics { liveRegion = LiveRegionMode.Polite },
  )
}

@Composable
private fun CopyFailedNotice(state: ShareUiState, phase: SharePhase.CopyFailed) {
  val copy = RequestFailureCopy.of(
    phase.error,
    FailureContext(RequestKind.FILE, null, phase.request.actions, state.maxLinkDurationS),
    LocalResources.current,
  )
  Notice(copy.body, lead = copy.title, kind = NoticeKind.CAUTION, live = true, modifier = Modifier.testTag(ShareTags.NOTICE).oneNode())
}

@Composable
private fun Done(phase: SharePhase.Done) {
  val resources = LocalResources.current
  val (text, icon) = when (phase.message) {
    DoneMessage.ADDED -> R.string.share_done_added to PhonoIcons.StatusWaiting
    DoneMessage.SENDING -> R.string.share_done_sending to PhonoIcons.StatusSending
    DoneMessage.SENT -> R.string.share_done_sent to PhonoIcons.StatusReady
    DoneMessage.REFUSED -> R.string.share_done_refused to PhonoIcons.StatusAttention
  }
  StatusLine(
    stringResource(text),
    modifier = Modifier.testTag(ShareTags.DONE).semantics { liveRegion = LiveRegionMode.Polite },
    tone = if (phase.message == DoneMessage.SENT) StatusTone.GOOD else StatusTone.NEUTRAL,
    icon = icon,
    style = PhonoTheme.type.requestState,
  )
  if (phase.refusals.isNotEmpty()) {
    Notice(
      ShareCopy.partial(phase.ids.size, phase.total, phase.refusals, resources),
      kind = NoticeKind.CAUTION,
      modifier = Modifier.testTag(ShareTags.PARTIAL).oneNode(),
    )
  }
}

@Composable
private fun ProblemNotice(state: ShareUiState, problem: ShareProblem) {
  val resources = LocalResources.current
  val text = when (problem) {
    ShareProblem.NoLink -> stringResource(R.string.share_no_link)
    ShareProblem.NothingReadable -> stringResource(R.string.share_nothing_readable)
    ShareProblem.RecordOutOfSpace -> stringResource(R.string.share_record_out_of_space)
    is ShareProblem.Refused -> if (problem.total == 1 && problem.refusals.size == 1) {
      val video = (state.summary as? SharedSummary.OneFile)?.kind != SharedKind.AUDIO
      ShareCopy.refusal(problem.refusals.single(), video, state.maxUploadBytes, resources)
    } else {
      ShareCopy.partial(0, problem.total, problem.refusals, resources)
    }
  }
  Notice(text, kind = NoticeKind.CAUTION, live = true, modifier = Modifier.testTag(ShareTags.NOTICE).oneNode())
}

@Composable
private fun Buttons(state: ShareUiState, actions: ShareActions, modifier: Modifier = Modifier) {
  when (val phase = state.phase) {
    SharePhase.Reading, SharePhase.Closed -> ButtonRow(modifier) {
      Secondary(R.string.share_cancel, ShareTags.CANCEL, actions.onClose)
    }
    SharePhase.Confirm, SharePhase.Saving -> ButtonRow(modifier) {
      Secondary(R.string.share_cancel, ShareTags.CANCEL, actions.onClose, enabled = phase == SharePhase.Confirm)
      PhonoButton(
        text = stringResource(if (state.analyzeNow) R.string.share_analyze else R.string.share_add_to_queue),
        onClick = actions.onSubmit,
        modifier = Modifier.weight(1f).testTag(ShareTags.PRIMARY),
        leadingIcon = if (state.analyzeNow) PhonoIcons.StatusSending else PhonoIcons.Add,
        enabled = phase == SharePhase.Confirm,
        busy = phase == SharePhase.Saving,
        large = true,
      )
    }
    is SharePhase.Preparing -> ButtonRow(modifier) {
      Secondary(R.string.share_cancel, ShareTags.CANCEL, actions.onBack)
    }
    is SharePhase.CopyFailed -> {
      val offered = phase.request.actions
      Column(modifier, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        ButtonRow(Modifier) {
          if (RequestAction.REMOVE in offered) {
            PhonoButton(
              stringResource(R.string.share_remove),
              actions.onRemove,
              Modifier.weight(1f).testTag(ShareTags.REMOVE),
              kind = PhonoButtonKind.DANGER,
              large = true,
            )
          }
          if (RequestAction.TRY_AUDIO_ONLY in offered) {
            PhonoButton(
              stringResource(R.string.share_try_audio_only),
              actions.onTryAudioOnly,
              Modifier.weight(1f).testTag(ShareTags.TRY_AUDIO_ONLY),
              leadingIcon = PhonoIcons.AudioOnly,
              large = true,
            )
          }
        }
        ButtonRow(Modifier) { Secondary(R.string.share_close, ShareTags.CLOSE, actions.onClose) }
      }
    }
    is SharePhase.Done -> ButtonRow(modifier) {
      Secondary(R.string.share_open_app, ShareTags.OPEN_APP, actions.onOpenApp)
      Secondary(R.string.share_see_requests, ShareTags.SEE_REQUESTS, actions.onSeeRequests)
    }
    is SharePhase.Problem -> ButtonRow(modifier) {
      Secondary(R.string.share_close, ShareTags.CLOSE, actions.onClose)
      if ((phase.problem as? ShareProblem.Refused)?.tryAudioOnly == true) {
        PhonoButton(
          stringResource(R.string.share_try_audio_only),
          actions.onTryAudioOnly,
          Modifier.weight(1f).testTag(ShareTags.TRY_AUDIO_ONLY),
          leadingIcon = PhonoIcons.AudioOnly,
          large = true,
        )
      }
    }
    is SharePhase.DuplicateFound -> ButtonRow(modifier) {
      Secondary(R.string.share_open_it, ShareTags.OPEN_IT, { actions.onOpenDuplicate(phase.duplicate) })
      PhonoButton(
        stringResource(R.string.share_analyze_again),
        actions.onAnalyzeAgain,
        Modifier.weight(1f).testTag(ShareTags.ANALYZE_AGAIN),
        large = true,
      )
    }
  }
}

@Composable
private fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

@Composable
private fun RowScope.Secondary(text: Int, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
  PhonoButton(
    stringResource(text),
    onClick,
    Modifier.weight(1f).testTag(tag),
    kind = PhonoButtonKind.SECONDARY,
    enabled = enabled,
    large = true,
  )
}

@Composable
private fun Dialogs(state: ShareUiState, actions: ShareActions) {
  if (state.confirmStop) {
    val video = (state.phase as? SharePhase.Preparing)?.anyVideo ?: true
    PhonoDialog(
      title = stringResource(if (video) R.string.share_stop_title_video else R.string.share_stop_title_file),
      body = stringResource(R.string.share_stop_body),
      confirmLabel = stringResource(R.string.share_stop),
      onConfirm = actions.onStop,
      dismissLabel = stringResource(R.string.share_keep_adding),
      onDismiss = actions.onKeepAdding,
      destructive = true,
    )
  }
  if (state.question == NotificationQuestion.ASKING) {
    PhonoDialog(
      title = stringResource(R.string.share_notify_title),
      body = stringResource(R.string.share_notify_body),
      confirmLabel = stringResource(R.string.share_notify_me),
      onConfirm = actions.onNotifyMe,
      dismissLabel = stringResource(R.string.share_not_now),
      onDismiss = actions.onNotNow,
    )
  }
}
