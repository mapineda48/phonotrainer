package com.mapineda48.phonotrainer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.ui.AvailabilityChip
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PosterArt
import com.mapineda48.phonotrainer.core.ui.R as UiR
import com.mapineda48.phonotrainer.core.ui.StatusLine
import com.mapineda48.phonotrainer.core.ui.StatusTone
import com.mapineda48.phonotrainer.core.ui.VideoAvailabilityChip
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ClipCardCopy
import com.mapineda48.phonotrainer.core.ui.component.RequestRow
import com.mapineda48.phonotrainer.core.ui.component.RequestRowState
import com.mapineda48.phonotrainer.core.ui.extension.ClipReductionMeters
import com.mapineda48.phonotrainer.core.ui.extension.ClipTranscriptTab

/** What the Clip page's controls ask for; the route turns each into a ViewModel call or a navigation. */
internal class ClipActions(
  val onBack: () -> Unit = {},
  val onSeeAll: () -> Unit = {},
  val onWatch: () -> Unit = {},
  val onFromStart: () -> Unit = {},
  val onShort: (ShortId) -> Unit = {},
  val onChange: (ChangeLabel) -> Unit = {},
  val onTab: (ClipTab) -> Unit = {},
  val onKeep: (Boolean) -> Unit = {},
  val onDownload: () -> Unit = {},
  val onDownloadVideo: () -> Unit = {},
  val onRemoveVideo: () -> Unit = {},
  val onRemoveFromPhone: () -> Unit = {},
  val onOpenLink: (String) -> Unit = {},
  val onRequestAction: (Request, RequestAction) -> Unit = { _, _ -> },
  val onOpenRequest: (RequestId) -> Unit = {},
  val onOpenWord: (WordRef) -> Unit = {},
  val onOpenSegment: (Int) -> Unit = {},
)

/** The v1-complete slots of the Clip page (work-plan 4.4); absent in v1-core, where their fallbacks show. */
internal class ClipSlots(val transcript: ClipTranscriptTab? = null, val meters: ClipReductionMeters? = null)

/**
 * The Clip page (spec F-05 v1-core, design 10.2 P-02, render `16`): the hero (poster, the drum for audio clips, the
 * Back button on an overlay plate), the header with its notices and "Watch · short 4" / "From the start", the tabs
 * Shorts, Transcript (only when its v1-complete slot is bound) and Summary, whose last block is "On this phone"
 * (design 9.26, v1-core part). v1-core has no "On your computer" block.
 */
@Composable
internal fun ClipScreen(state: ClipUiState, actions: ClipActions, modifier: Modifier = Modifier, slots: ClipSlots = ClipSlots()) {
  when (val presence = state.presence) {
    ClipPresence.Loading -> ClipFrame(actions.onBack, modifier) {
      LoadingGate(loading = true) {}
    }
    ClipPresence.Gone -> ClipFrame(actions.onBack, modifier) {
      EmptyState(
        illustration = null,
        title = stringResource(R.string.clip_page_gone),
        body = stringResource(R.string.clip_page_gone_body),
        modifier = Modifier.testTag(LibraryTags.CLIP_GONE),
        actions = listOf(EmptyStateAction(stringResource(R.string.clip_page_see_all), actions.onSeeAll)),
      )
    }
    is ClipPresence.Known -> ClipPage(state, presence, actions, slots, modifier)
  }
}

/** A plain frame with the top bar's Back, for the states without the clip's hero. */
@Composable
private fun ClipFrame(onBack: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    PhonoTopBar(title = "", onBack = onBack, backLabel = stringResource(R.string.clip_page_back))
    content()
  }
}

@Composable
private fun ClipPage(
  state: ClipUiState,
  presence: ClipPresence.Known,
  actions: ClipActions,
  slots: ClipSlots,
  modifier: Modifier,
) {
  val detail = presence.detail
  val clip = detail.summary
  var confirmRemove by rememberSaveable { mutableStateOf(false) }
  val tabs = listOfNotNull(ClipTab.SHORTS, ClipTab.TRANSCRIPT.takeIf { slots.transcript != null }, ClipTab.SUMMARY)
  val tab = state.tab.takeIf { it in tabs } ?: ClipTab.SHORTS
  LazyColumn(
    state = rememberLazyListState(),
    modifier = modifier
      .fillMaxSize()
      .paneTitle(clip.title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
      .testTag(LibraryTags.CLIP_LIST),
    contentPadding = PaddingValues(bottom = PhonoSpacing.s24),
  ) {
    item(key = "hero") { Hero(state, detail, actions.onBack) }
    if (presence.deleted) {
      item(key = "deleted") {
        Notice(
          body = stringResource(R.string.clip_page_deleted),
          kind = NoticeKind.CAUTION,
          live = true,
          modifier = Modifier.padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s12)
            .testTag(LibraryTags.CLIP_DELETED_BANNER),
        )
      }
    }
    item(key = "header") { ClipHeader(state, detail, actions) }
    if (ClipRules.processing(detail)) {
      item(key = "processing") { Processing(state.request, actions) }
    } else {
      item(key = "tabs") { ClipTabs(tabs, tab, actions.onTab) }
      when (tab) {
        ClipTab.SHORTS -> shortsTab(state, actions)
        ClipTab.TRANSCRIPT -> item(key = "transcript") {
          slots.transcript?.Tab(clip.id, onOpenWord = actions.onOpenWord, onOpenShort = actions.onOpenSegment)
        }
        ClipTab.SUMMARY -> summaryTab(state, detail, actions, slots.meters, onRemove = { confirmRemove = true })
      }
    }
  }
  if (confirmRemove) {
    RemoveFromPhoneDialog(
      title = clip.title,
      onConfirm = {
        confirmRemove = false
        actions.onRemoveFromPhone()
      },
      onDismiss = { confirmRemove = false },
    )
  }
}

/** The poster (200 dp), the poster fallback or the drum, edge to edge, with Back on an overlay plate. */
@Composable
private fun Hero(state: ClipUiState, detail: ClipDetail, onBack: () -> Unit) {
  val clip = detail.summary
  Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
    PosterArt(
      poster = state.poster,
      jobId = clip.id,
      audioOnly = clip.video == VideoAvailability.AudioOnlyClip,
      modifier = Modifier.fillMaxSize(),
    )
    Box(
      Modifier
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(PhonoSpacing.s8)
        .clip(CircleShape)
        .background(PhonoTheme.colors.overlayPlate),
    ) {
      PhonoIconButton(
        icon = PhonoIcons.Back,
        contentDescription = stringResource(R.string.clip_page_back),
        onClick = onBack,
        modifier = Modifier.testTag(LibraryTags.CLIP_BACK),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipHeader(state: ClipUiState, detail: ClipDetail, actions: ClipActions) {
  val clip = detail.summary
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  Column(
    modifier = Modifier.padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, top = PhonoSpacing.s16),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    PhonoText(
      clip.title,
      style = MaterialTheme.typography.headlineLarge,
      color = colors.ink,
      modifier = Modifier.semantics { heading() }.testTag(LibraryTags.CLIP_TITLE),
    )
    val audioOnly = clip.video == VideoAvailability.AudioOnlyClip
    val meta = listOfNotNull(
      (detail.durationS ?: clip.durationS)?.let { ClipCardCopy.duration(it, resources) },
      detail.segments?.let { resources.getQuantityString(R.plurals.clip_page_phrases, it, ClipCardCopy.number(it)) },
      clip.words?.let { resources.getQuantityString(UiR.plurals.clip_words, it, ClipCardCopy.number(it)) },
      stringResource(if (audioOnly) R.string.clip_page_kind_audio else R.string.clip_page_kind_video),
      LibraryTime.added(clip.addedAt, state.now, state.zone, resources),
    ).joinToString(ClipCardCopy.DOT)
    PhonoText(meta, style = MaterialTheme.typography.bodyMedium, color = colors.ink2, modifier = Modifier.testTag(LibraryTags.CLIP_META))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Pill { AvailabilityChip(clip.availability) }
      if (!audioOnly) Pill { VideoAvailabilityChip(clip.video) }
      ClipCardCopy.kept(clip, resources)?.let { Pill { StatusLine(it, icon = PhonoIcons.Keep, style = MaterialTheme.typography.bodySmall) } }
      ClipCardCopy.origin(clip.origin, resources)?.let {
        Pill { StatusLine(it, icon = PhonoIcons.Info, style = MaterialTheme.typography.bodySmall) }
      }
    }
    ClipRules.notices(detail, state.wanted).forEach { HeaderNotice(it, detail, state.connected, actions.onDownload) }
    WatchButtons(state, detail, actions)
  }
}

/** A galley pill around a status line (the header's availability badges, render `16`). */
@Composable
private fun Pill(content: @Composable () -> Unit) {
  Box(
    Modifier
      .clip(CircleShape)
      .background(PhonoTheme.colors.galley)
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6),
  ) { content() }
}

@Composable
private fun HeaderNotice(notice: ClipNotice, detail: ClipDetail, connected: Boolean, onDownload: () -> Unit) {
  val resources = LocalResources.current
  val tag = Modifier.testTag(LibraryTags.clipNotice(notice))
  when (notice) {
    ClipNotice.NOT_HERE -> Notice(
      body = stringResource(if (connected) R.string.clip_page_not_here else R.string.clip_page_not_here_offline),
      modifier = tag,
      action = {
        PhonoButton(
          text = stringResource(if (connected) R.string.clip_page_download else R.string.clip_page_download_when_connected),
          onClick = onDownload,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.StatusDownloading,
          modifier = Modifier.testTag(LibraryTags.CLIP_DOWNLOAD),
        )
      },
    )
    ClipNotice.WANTED -> Notice(stringResource(R.string.clip_page_wanted), modifier = tag, live = true)
    ClipNotice.NEEDS_AGAIN -> Notice(stringResource(R.string.clip_page_needs_again), modifier = tag, kind = NoticeKind.CAUTION)
    ClipNotice.PAUSED -> Notice(ClipCardCopy.availability(detail.summary.availability, resources), modifier = tag)
    ClipNotice.NO_AUDIO -> Notice(stringResource(R.string.clip_page_no_audio), modifier = tag)
    ClipNotice.VIDEO_NOT_AVAILABLE -> Notice(stringResource(R.string.clip_page_video_not_available), modifier = tag)
    ClipNotice.VIDEO_CANNOT_PLAY -> Notice(stringResource(R.string.clip_page_video_cannot_play), modifier = tag)
    ClipNotice.OLDER_RULES -> Notice(stringResource(R.string.clip_page_older_rules), modifier = tag)
  }
}

/** "Watch · short 4" (resumes) and "From the start"; while the audio comes, "Watch" waits with the reason. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchButtons(state: ClipUiState, detail: ClipDetail, actions: ClipActions) {
  val playable = ClipRules.playable(detail)
  val percent = ClipRules.audioPercent(detail)
  if (!playable && percent == null) return
  val resume = state.resume?.ordinal?.takeIf { it > 1 }
  val watch = resume?.let { stringResource(R.string.clip_page_watch_short, it) } ?: stringResource(R.string.clip_page_watch)
  FlowRow(
    modifier = Modifier.padding(top = PhonoSpacing.s6),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoButton(
      text = watch,
      onClick = actions.onWatch,
      enabled = playable,
      leadingIcon = PhonoIcons.Play,
      large = true,
      modifier = Modifier.testTag(LibraryTags.CLIP_WATCH),
    )
    if (playable) {
      PhonoButton(
        text = stringResource(R.string.clip_page_from_start),
        onClick = actions.onFromStart,
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Replay,
        large = true,
        modifier = Modifier.testTag(LibraryTags.CLIP_FROM_START),
      )
    }
  }
  if (!playable && percent != null) {
    PhonoText(
      stringResource(R.string.clip_page_getting_audio, percent),
      style = MaterialTheme.typography.bodyMedium,
      color = PhonoTheme.colors.ink2,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LibraryTags.CLIP_PLAY_NOTE),
    )
  }
}

/** Still analyzed on the computer: its state and this phone's request; the page turns into the clip when ready. */
@Composable
private fun Processing(request: Request?, actions: ClipActions) {
  Column(
    modifier = Modifier.padding(PhonoSpacing.gutter).testTag(LibraryTags.CLIP_PROCESSING),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    StatusLine(
      text = stringResource(R.string.clip_page_processing),
      tone = StatusTone.ACTIVE,
      style = PhonoTheme.type.requestState,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    PhonoText(stringResource(R.string.clip_page_processing_body), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
    request?.let { own ->
      RequestRow(
        state = RequestRowState(own),
        onAction = { actions.onRequestAction(own, it) },
        onOpen = { actions.onOpenRequest(own.id) },
      )
    }
  }
}

/** The tab row (design 10.2 P-02): selected = bold words and a 3 dp ink bar; a hairline under the row. */
@Composable
private fun ClipTabs(tabs: List<ClipTab>, selected: ClipTab, onTab: (ClipTab) -> Unit) {
  val colors = PhonoTheme.colors
  Column(Modifier.padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s12)) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
      tabs.forEach { tab ->
        val isSelected = tab == selected
        Column(
          modifier = Modifier
            .weight(1f)
            .heightIn(min = PhonoSizes.minTarget)
            .selectable(selected = isSelected, role = Role.Tab, onClick = { onTab(tab) })
            .testTag(LibraryTags.tab(tab)),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          PhonoText(
            stringResource(tab.label()),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
            color = if (isSelected) colors.ink else colors.ink2,
            modifier = Modifier.padding(vertical = PhonoSpacing.s12),
          )
          Box(Modifier.fillMaxWidth().height(TAB_BAR).background(if (isSelected) colors.ink else Color.Transparent))
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
  }
}

private fun ClipTab.label(): Int = when (this) {
  ClipTab.SHORTS -> R.string.clip_page_tab_shorts
  ClipTab.TRANSCRIPT -> R.string.clip_page_tab_transcript
  ClipTab.SUMMARY -> R.string.clip_page_tab_summary
}

/** The Shorts tab (spec F-05 item 2). */
private fun LazyListScope.shortsTab(state: ClipUiState, actions: ClipActions) {
  when (val shorts = state.shorts) {
    ShortsState.Loading -> item(key = "shorts-loading") {
      Box(Modifier.padding(horizontal = PhonoSpacing.gutter).testTag(LibraryTags.SHORTS_LOADING)) {
        LoadingGate(loading = true, label = stringResource(R.string.clip_page_opening)) {}
      }
    }
    ShortsState.NotHere -> item(key = "shorts-not-here") {
      TabNote(stringResource(R.string.clip_page_comes_with_clip), Modifier.testTag(LibraryTags.SHORTS_NOT_HERE))
    }
    is ShortsState.Ready -> if (shorts.rows.isEmpty()) {
      item(key = "shorts-none") { TabNote(stringResource(R.string.clip_page_no_shorts)) }
    } else {
      items(shorts.rows, key = { "short:" + it.short.id.firstSegment }) { row ->
        ShortRow(row, onOpen = { actions.onShort(row.short.id) })
      }
    }
  }
}

@Composable
internal fun TabNote(text: String, modifier: Modifier = Modifier) {
  PhonoText(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = PhonoTheme.colors.ink2,
    modifier = modifier.padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s16),
  )
}

/** The Summary tab (spec F-05 item 4) and the "On this phone" block (item 5). */
private fun LazyListScope.summaryTab(
  state: ClipUiState,
  detail: ClipDetail,
  actions: ClipActions,
  meters: ClipReductionMeters?,
  onRemove: () -> Unit,
) {
  item(key = "changes-head") { SectionHead(stringResource(R.string.clip_page_changes)) }
  if (state.changes.isEmpty()) {
    item(key = "changes-none") {
      TabNote(stringResource(R.string.clip_page_changes_none), Modifier.testTag(LibraryTags.CHANGES_NONE))
    }
  } else {
    items(state.changes, key = { "change:" + it.label.value }) { change ->
      ChangeRow(change, onOpen = { actions.onChange(change.label) })
    }
  }
  if (meters != null) {
    item(key = "meters") { meters.Meters(detail.summary.id, Modifier.padding(PhonoSpacing.gutter)) }
  }
  item(key = "about") { AboutClip(detail, state.speechModel, actions.onOpenLink) }
  item(key = "on-phone") {
    OnThisPhone(
      detail = detail,
      onKeep = actions.onKeep,
      onDownloadVideo = actions.onDownloadVideo,
      onRemoveVideo = actions.onRemoveVideo,
      onRemove = onRemove,
    )
  }
}

/** A Summary section's head (design 9.1 group head). */
@Composable
internal fun SectionHead(text: String, modifier: Modifier = Modifier) {
  Kicker(
    text = text,
    modifier = modifier.padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, top = PhonoSpacing.s20, bottom = PhonoSpacing.s8),
  )
}

private val HERO_HEIGHT = 200.dp
private val TAB_BAR = 3.dp
