package com.mapineda48.phonotrainer.feature.requests.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoDialog
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTextField
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ConnectionLine
import com.mapineda48.phonotrainer.core.ui.component.RequestRow
import com.mapineda48.phonotrainer.core.ui.component.RequestRowCopy
import com.mapineda48.phonotrainer.core.ui.component.RequestRowState
import com.mapineda48.phonotrainer.core.ui.component.ShareBridge
import com.mapineda48.phonotrainer.core.ui.component.ShareBridgeState
import com.mapineda48.phonotrainer.feature.requests.R
import com.mapineda48.phonotrainer.feature.requests.RequestsTags
import java.util.Locale

/** What "Add a clip"'s controls ask for; the route turns each into a ViewModel call, a navigation or the system. */
internal class AddClipActions(
  val onBack: () -> Unit = {},
  val onLink: (String) -> Unit = {},
  val onPaste: () -> Unit = {},
  val onChooseFiles: () -> Unit = {},
  val onClearFiles: () -> Unit = {},
  val onAudioOnly: (Boolean) -> Unit = {},
  val onSeparate: (Boolean) -> Unit = {},
  val onSubmit: () -> Unit = {},
  val onAnalyzeAgain: () -> Unit = {},
  val onOpenDuplicate: (Duplicate) -> Unit = {},
  val onSeeRequests: () -> Unit = {},
  val onAnother: () -> Unit = {},
  val onFileAction: (RequestId, RequestAction) -> Unit = { _, _ -> },
  val onOpenRequest: (RequestId) -> Unit = {},
  val onNotNow: () -> Unit = {},
  val onNotifyMe: () -> Unit = {},
)

/**
 * "Add a clip" (spec F-19, design 10.2 P-05): the lede; "Paste a link" (the field, "Paste", the recognised site or
 * "That doesn't look like a link."); "Choose a file" (the system picker; then the chosen files with name, kind,
 * duration and size); the options as switch rows; the legal line for links; the bridge and connection line of the
 * share sheet (design 9.22); and "Analyze" (connected) or "Add to queue". After the tap: the share sheet's messages
 * (spec 8.4 item 8), each file's own copy ("Preparing the file… 40 %", then "Added"), and "See requests".
 * [focusLink] puts the focus in the link field (`AddClipStart.PASTE`); the clipboard is read only on "Paste".
 */
@Composable
internal fun AddClipScreen(
  state: AddClipUiState,
  actions: AddClipActions,
  modifier: Modifier = Modifier,
  focusLink: Boolean = false,
) {
  val title = stringResource(R.string.add_title)
  val scroll = rememberScrollState()
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .imePadding(),
  ) {
    PhonoTopBar(title = title, onBack = actions.onBack, backLabel = stringResource(R.string.add_back), scrolled = scroll.canScrollBackward)
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scroll)
        .padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
    ) {
      PhonoText(stringResource(R.string.add_lede), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
      when (val phase = state.phase) {
        is AddPhase.LinkAdded -> LinkDone(state, phase, actions)
        is AddPhase.FilesAdded -> FilesDone(state, phase, actions)
        else -> Form(state, phase, actions, focusLink)
      }
    }
  }
  if (state.question == NotificationQuestion.ASKING) {
    PhonoDialog(
      title = stringResource(R.string.add_notify_title),
      body = stringResource(R.string.add_notify_body),
      confirmLabel = stringResource(R.string.add_notify_me),
      onConfirm = actions.onNotifyMe,
      dismissLabel = stringResource(R.string.add_not_now),
      onDismiss = actions.onNotNow,
      modifier = Modifier.testTag(RequestsTags.ADD_QUESTION),
    )
  }
}

@Composable
private fun Heading(text: String) {
  PhonoText(
    text,
    style = MaterialTheme.typography.titleLarge,
    color = PhonoTheme.colors.ink,
    modifier = Modifier.padding(top = PhonoSpacing.s4).semantics { heading() },
  )
}

/** The form: the link, the files, the options, the bridge and the primary. */
@Composable
private fun Form(state: AddClipUiState, phase: AddPhase, actions: AddClipActions, focusLink: Boolean) {
  if (!state.filesMode) LinkSection(state, actions, focusLink)
  FileSection(state, actions)
  if (state.parsed != null || state.filesMode) Options(state, actions)
  if (state.parsed != null && !state.filesMode) {
    PhonoText(
      stringResource(R.string.add_legal),
      style = MaterialTheme.typography.bodySmall,
      color = PhonoTheme.colors.ink2,
      modifier = Modifier.testTag(RequestsTags.ADD_LEGAL),
    )
  }
  Bridge(state)
  when (phase) {
    is AddPhase.DuplicateFound -> DuplicateNotice(phase.duplicate, actions)
    is AddPhase.Problem -> ProblemNotice(state, phase.problem)
    else -> Unit
  }
  if (phase !is AddPhase.DuplicateFound) {
    PhonoButton(
      text = stringResource(if (state.analyzeNow) R.string.add_analyze else R.string.add_to_queue),
      onClick = actions.onSubmit,
      enabled = state.canSubmit,
      busy = phase == AddPhase.Saving,
      large = true,
      fillWidth = true,
      leadingIcon = if (state.analyzeNow) PhonoIcons.StatusSending else PhonoIcons.Add,
      modifier = Modifier.testTag(RequestsTags.ADD_SUBMIT),
    )
    if (!state.analyzeNow) {
      PhonoText(
        stringResource(R.string.add_queued_note),
        style = MaterialTheme.typography.bodyMedium,
        color = PhonoTheme.colors.ink2,
        modifier = Modifier.testTag(RequestsTags.ADD_QUEUED_NOTE),
      )
    }
  }
}

/** "Paste a link" (spec F-19): the field, "Paste" (the clipboard is read on this tap only), the site or the error. */
@Composable
private fun LinkSection(state: AddClipUiState, actions: AddClipActions, focusLink: Boolean) {
  Heading(stringResource(R.string.add_link_heading))
  val focus = remember { FocusRequester() }
  if (focusLink) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  // The tag names the field's container; the editable node inside it is the one with SetText.
  Column(Modifier.testTag(RequestsTags.ADD_LINK)) {
    PhonoTextField(
      value = state.link,
      onValueChange = actions.onLink,
      label = stringResource(R.string.add_link_label),
      placeholder = stringResource(R.string.add_link_placeholder),
      leadingIcon = PhonoIcons.Link,
      error = if (state.linkError) stringResource(R.string.add_not_a_link) else null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
      modifier = Modifier.focusRequester(focus),
    )
  }
  Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    PhonoButton(
      text = stringResource(R.string.add_paste),
      onClick = actions.onPaste,
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Paste,
      modifier = Modifier.testTag(RequestsTags.ADD_PASTE),
    )
  }
  state.parsed?.let { parsed ->
    Row(
      modifier = Modifier.testTag(RequestsTags.ADD_SITE).semantics(mergeDescendants = true) { },
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Icon(painterResource(siteIcon(parsed.site)), null, tint = PhonoTheme.colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconButton))
      PhonoText(stringResource(siteWords(parsed.site)), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = PhonoTheme.colors.ink)
    }
  }
}

private fun siteWords(site: Site): Int = when (site) {
  Site.YOUTUBE -> R.string.add_site_youtube
  Site.YOUTUBE_SHORTS -> R.string.add_site_shorts
  Site.X -> R.string.add_site_x
  Site.OTHER -> R.string.add_site_other
}

private fun siteIcon(site: Site): Int = when (site) {
  Site.YOUTUBE, Site.YOUTUBE_SHORTS -> PhonoIcons.SiteYoutube
  Site.X -> PhonoIcons.SiteX
  Site.OTHER -> PhonoIcons.SiteOther
}

/** "Choose a file" (spec F-19): the picker (video and audio, up to 10), then the chosen files. */
@Composable
private fun FileSection(state: AddClipUiState, actions: AddClipActions) {
  Heading(stringResource(R.string.add_file_heading))
  if (state.filesMode) {
    Column(Modifier.testTag(RequestsTags.ADD_FILES), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      state.files.forEachIndexed { index, file -> ChosenFile(file, Modifier.testTag(RequestsTags.file(index))) }
    }
  }
  @OptIn(ExperimentalLayoutApi::class)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    PhonoButton(
      text = stringResource(if (state.filesMode) R.string.add_choose_other else R.string.add_choose_file),
      onClick = actions.onChooseFiles,
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.ChooseFile,
      busy = state.reading,
      enabled = !state.reading,
      modifier = Modifier.testTag(RequestsTags.ADD_CHOOSE),
    )
    if (state.filesMode) {
      PhonoButton(
        text = stringResource(R.string.add_clear_files),
        onClick = actions.onClearFiles,
        kind = PhonoButtonKind.QUIET,
        leadingIcon = PhonoIcons.Link,
        modifier = Modifier.testTag(RequestsTags.ADD_CLEAR_FILES),
      )
    }
  }
}

/** A chosen file: its icon, name, and "Video · 0:42 · 48 MB". */
@Composable
private fun ChosenFile(file: PickedFile, modifier: Modifier = Modifier) {
  val resources = LocalResources.current
  val colors = PhonoTheme.colors
  val meta = listOfNotNull(
    file.kind?.let { stringResource(if (it == PickedKind.VIDEO) R.string.add_file_kind_video else R.string.add_file_kind_audio) },
    file.durationMs?.let(::duration),
    file.bytes?.let { RequestRowCopy.bytes(it, resources) },
  ).joinToString(" · ")
  Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Icon(
      painterResource(if (file.kind == PickedKind.AUDIO) PhonoIcons.SitePhoneAudio else PhonoIcons.SitePhoneFile),
      null,
      tint = colors.ink,
      modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconBar),
    )
    Column {
      PhonoText(file.name ?: stringResource(R.string.add_file_unnamed), style = MaterialTheme.typography.titleMedium, color = colors.ink)
      if (meta.isNotEmpty()) PhonoText(meta, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    }
  }
}

/** "0:42", "12:05", "1:02:03". */
internal fun duration(ms: Long): String {
  val seconds = (ms / 1000).coerceAtLeast(0)
  val h = seconds / 3600
  val m = seconds % 3600 / 60
  val s = seconds % 60
  return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** The options (spec F-19): "Audio only" (links; videos), "Separate the dialogue" (files), the large-file note. */
@Composable
private fun Options(state: AddClipUiState, actions: AddClipActions) {
  Heading(stringResource(R.string.add_options_heading))
  if (state.audioOnlyOffered) {
    PhonoSwitch(
      label = stringResource(R.string.add_audio_only),
      description = stringResource(R.string.add_audio_only_body),
      checked = state.audioOnly,
      onCheckedChange = actions.onAudioOnly,
      modifier = Modifier.testTag(RequestsTags.ADD_AUDIO_ONLY),
    )
  }
  if (state.filesMode) {
    PhonoSwitch(
      label = stringResource(R.string.add_separate),
      description = stringResource(R.string.add_separate_body),
      checked = state.separateDialogue,
      onCheckedChange = actions.onSeparate,
      modifier = Modifier.testTag(RequestsTags.ADD_SEPARATE),
    )
  }
  if (state.largeFile) Notice(stringResource(R.string.add_large_file), modifier = Modifier.testTag(RequestsTags.ADD_LARGE))
}

/** The bridge and the connection line of the share sheet (design 9.22, P-05). */
@Composable
private fun Bridge(state: AddClipUiState) {
  ShareBridge(ShareBridgeState(state.status))
  ConnectionLine(state.status)
}

/** "You already added this link." with "Open it" / "Analyze again anyway" (spec 7.7, SH-AC8). */
@Composable
private fun DuplicateNotice(duplicate: Duplicate, actions: AddClipActions) {
  Notice(
    body = stringResource(R.string.add_duplicate),
    kind = NoticeKind.CAUTION,
    live = true,
    modifier = Modifier.testTag(RequestsTags.ADD_DUPLICATE),
    action = {
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        PhonoButton(
          text = stringResource(R.string.add_open_it),
          onClick = { actions.onOpenDuplicate(duplicate) },
          kind = PhonoButtonKind.SECONDARY,
          modifier = Modifier.testTag(RequestsTags.ADD_OPEN_IT),
        )
        PhonoButton(
          text = stringResource(R.string.add_analyze_again),
          onClick = actions.onAnalyzeAgain,
          kind = PhonoButtonKind.QUIET,
          modifier = Modifier.testTag(RequestsTags.ADD_AGAIN),
        )
      }
    },
  )
}

/** Nothing was added: out of space for the record, or every file refused with its reason (spec 8.3, 7.7). */
@Composable
private fun ProblemNotice(state: AddClipUiState, problem: AddProblem) {
  val body = when (problem) {
    AddProblem.OutOfSpace -> stringResource(R.string.add_out_of_space)
    is AddProblem.Refused -> refusalsText(problem.refusals, problem.total, added = 0, state = state)
  }
  Notice(body = body, kind = NoticeKind.CAUTION, live = true, modifier = Modifier.testTag(RequestsTags.ADD_PROBLEM))
}

/** The reasons files were not added: one file's own sentence, or "2 of 3 added." and each named reason. */
@Composable
private fun refusalsText(refusals: List<FileRefusal>, total: Int, added: Int, state: AddClipUiState): String {
  val resources = LocalResources.current
  val reasons = refusals.map { refusal -> refusal.displayName to reasonText(refusal, state) }
  if (total <= 1 && reasons.size == 1) return reasons.single().second
  val head = pluralStringResource(R.plurals.add_partial, total, added, total)
  val lines = reasons.map { (name, text) -> if (name.isNullOrBlank()) text else resources.getString(R.string.add_refused_named, name, text) }
  return (listOf(head) + lines.distinct()).joinToString("\n")
}

@Composable
private fun reasonText(refusal: FileRefusal, state: AddClipUiState): String {
  val resources = LocalResources.current
  val megabytes = RequestRowCopy.megabytes(refusal.neededBytes ?: 0)
  return when (refusal.reason) {
    FileRefusalReason.UNSUPPORTED_TYPE -> stringResource(R.string.add_unsupported)
    FileRefusalReason.TOO_LARGE -> stringResource(R.string.add_too_large, RequestRowCopy.bytes(state.maxUploadBytes, resources))
    FileRefusalReason.NO_SPACE -> {
      val video = state.files.firstOrNull { it.name == refusal.displayName }?.kind == PickedKind.VIDEO
      stringResource(if (video) R.string.add_no_space_video else R.string.add_no_space_file, megabytes)
    }
    FileRefusalReason.UNREADABLE -> stringResource(R.string.add_unreadable)
    FileRefusalReason.OWN_PROVIDER -> stringResource(R.string.add_own_provider)
    FileRefusalReason.TOO_MANY -> stringResource(R.string.add_too_many)
  }
}

/** A link was stored (spec 8.4 item 8): the current message, the bridge, "See requests" and "Add another clip". */
@Composable
private fun LinkDone(state: AddClipUiState, phase: AddPhase.LinkAdded, actions: AddClipActions) {
  val words = when (phase.message) {
    DoneMessage.ADDED -> R.string.add_done_added
    DoneMessage.SENDING -> R.string.add_done_sending
    DoneMessage.SENT -> R.string.add_done_sent
    DoneMessage.REFUSED -> R.string.add_done_refused
  }
  DoneMessageText(stringResource(words))
  Bridge(state)
  DoneButtons(actions)
}

/** Files were stored: each file with its own copy (F-19-AC3), the files not added, the bridge and the buttons. */
@Composable
private fun FilesDone(state: AddClipUiState, phase: AddPhase.FilesAdded, actions: AddClipActions) {
  val preparing = phase.files.any { !it.added && !it.failed }
  DoneMessageText(stringResource(if (preparing) R.string.add_file_preparing_unknown else R.string.add_done_added))
  phase.files.forEach { file -> AddedFileRow(state, file, actions) }
  if (phase.refusals.isNotEmpty()) {
    Notice(
      body = refusalsText(phase.refusals, phase.total, added = phase.files.size, state = state),
      kind = NoticeKind.CAUTION,
      modifier = Modifier.testTag(RequestsTags.ADD_PROBLEM),
    )
  }
  Bridge(state)
  DoneButtons(actions)
}

@Composable
private fun DoneMessageText(text: String) {
  PhonoText(
    text,
    style = MaterialTheme.typography.headlineMedium,
    color = PhonoTheme.colors.ink,
    modifier = Modifier
      .testTag(RequestsTags.ADD_DONE)
      .semantics { liveRegion = LiveRegionMode.Polite },
  )
}

/**
 * One added file (F-19-AC3): its name and size with "Preparing the file… 40 %" and the bar while its copy runs,
 * "Added" once it is complete, or its request's failure card ("Try audio only" while the picked file can be read).
 */
@Composable
private fun AddedFileRow(state: AddClipUiState, file: AddedFile, actions: AddClipActions) {
  val resources = LocalResources.current
  val request = file.request
  if (file.failed && request != null) {
    RequestRow(
      state = RequestRowState(request, status = state.status, capabilities = state.capabilities),
      onAction = { actions.onFileAction(file.id, it) },
      onOpen = { actions.onOpenRequest(file.id) },
      modifier = Modifier.testTag(RequestsTags.added(file.id)),
    )
    return
  }
  val progress = file.progress
  val fraction = progress?.totalBytes?.takeIf { it > 0 }?.let { (progress.copiedBytes.toFloat() / it).coerceIn(0f, 1f) }
  val status = when {
    file.added -> stringResource(R.string.add_file_added)
    fraction != null -> stringResource(R.string.add_file_preparing, (fraction * 100).toInt())
    else -> stringResource(R.string.add_file_preparing_unknown)
  }
  PaperCard(modifier = Modifier.testTag(RequestsTags.added(file.id))) {
    PhonoText(file.name ?: stringResource(R.string.add_file_unnamed), style = MaterialTheme.typography.titleMedium, color = PhonoTheme.colors.ink)
    PhonoText(
      listOfNotNull(file.bytes?.let { RequestRowCopy.bytes(it, resources) }, status).joinToString(" · "),
      style = MaterialTheme.typography.bodyMedium,
      color = PhonoTheme.colors.ink2,
    )
    if (!file.added) PhonoProgressBar(progress = fraction)
  }
}

/** "See requests" (primary) and "Add another clip". */
@Composable
private fun DoneButtons(actions: AddClipActions) {
  @OptIn(ExperimentalLayoutApi::class)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    PhonoButton(
      text = stringResource(R.string.add_see_requests),
      onClick = actions.onSeeRequests,
      kind = PhonoButtonKind.PRIMARY,
      modifier = Modifier.testTag(RequestsTags.ADD_SEE_REQUESTS),
    )
    PhonoButton(
      text = stringResource(R.string.add_another),
      onClick = actions.onAnother,
      kind = PhonoButtonKind.QUIET,
      leadingIcon = PhonoIcons.Add,
      modifier = Modifier.testTag(RequestsTags.ADD_ANOTHER),
    )
  }
}
