package com.mapineda48.phonotrainer.feature.watch

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.ChipKind
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoChip
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.ui.FamilyMark
import com.mapineda48.phonotrainer.core.ui.MarksLegend
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import com.mapineda48.phonotrainer.core.ui.component.ShadowingOptions
import com.mapineda48.phonotrainer.core.ui.component.ShadowingPanel

/**
 * The mode picker (spec F-06 item 3, design 9.12, render `09`): "What the feed shows": the Mix with today's set's
 * progress, Saved with its count, "One change" by family with each change's short count (spec D-48: the number its
 * feed plays; a zero is muted and opens Learn), and "One clip, in order" with where the learner left each clip. Only
 * playable shorts are counted, so the counts hold offline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModePickerSheet(
  picker: PickerState,
  mode: ModeName?,
  currentClip: JobId?,
  onPick: (FeedStart) -> Unit,
  onLearn: (ChangeLabel) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = PhonoTheme.colors
  val title = stringResource(R.string.watch_picker_title)
  PhonoSheet(onDismiss = onDismiss, title = title, modifier = Modifier.testTag(WatchTags.PICKER_SHEET)) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      PhonoText(title, style = MaterialTheme.typography.headlineSmall, color = colors.ink, modifier = Modifier.semantics { heading() })
      PhonoText(stringResource(R.string.watch_picker_lede), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      val today = picker.today
      PickerRow(
        title = stringResource(R.string.watch_mode_with_detail, stringResource(R.string.watch_mode_mix), stringResource(R.string.watch_mode_today)),
        detail = if (today != null) {
          pluralStringResource(R.plurals.watch_picker_today_progress, today.second, today.first, today.second)
        } else {
          stringResource(R.string.watch_picker_today_none)
        },
        icon = PhonoIcons.ModeMix,
        selected = mode is ModeName.Mix,
        onClick = { onPick(FeedStart.TodaySet) },
        modifier = Modifier.testTag(WatchTags.PICKER_MIX),
      )
      PickerRow(
        title = stringResource(R.string.watch_mode_saved),
        detail = pluralStringResource(R.plurals.watch_picker_saved_count, picker.savedCount, picker.savedCount),
        icon = PhonoIcons.Saved,
        selected = mode == ModeName.Saved,
        onClick = { onPick(FeedStart.SavedMode) },
        modifier = Modifier.testTag(WatchTags.PICKER_SAVED),
      )
      Kicker(stringResource(R.string.watch_picker_one_change), modifier = Modifier.padding(top = PhonoSpacing.s8))
      picker.families.forEach { family ->
        FamilyMark(family.family, modifier = Modifier.padding(top = PhonoSpacing.s4))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
          family.changes.forEach { change ->
            val name = change.name.replaceFirstChar { it.uppercase() }
            val selected = mode is ModeName.Change && mode.label == change.label
            if (change.shorts > 0) {
              PhonoChip(
                pluralStringResource(R.plurals.watch_picker_change, change.shorts, name, change.shorts),
                kind = if (selected) ChipKind.SOLID else ChipKind.OUTLINE,
                onClick = { onPick(FeedStart.ChangeMode(change.label, onlyClip = null)) },
                modifier = Modifier.testTag(WatchTags.pickerChange(change.label.value)),
              )
            } else {
              PhonoChip(
                stringResource(R.string.watch_picker_change_zero, name),
                kind = ChipKind.ZERO,
                onClick = { onLearn(change.label) },
                modifier = Modifier.testTag(WatchTags.pickerChange(change.label.value)),
              )
            }
          }
        }
      }
      Kicker(stringResource(R.string.watch_picker_one_clip), modifier = Modifier.padding(top = PhonoSpacing.s8))
      if (picker.clips.isEmpty()) {
        PhonoText(stringResource(R.string.watch_picker_no_clips), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      picker.clips.forEach { clip ->
        PickerRow(
          title = clip.title,
          detail = clip.leftAt?.let { stringResource(R.string.watch_picker_left_at, it) } ?: stringResource(R.string.watch_picker_not_started),
          icon = PhonoIcons.ModeInOrder,
          selected = mode is ModeName.InOrder && currentClip == clip.jobId,
          onClick = { onPick(FeedStart.InOrder(clip.jobId, firstSegment = null, fromStart = false)) },
          modifier = Modifier.testTag(WatchTags.pickerClip(clip.jobId.value)),
        )
      }
    }
  }
}

/** A 56 dp option row: selected = ink fill with a check (design 9.12). */
@Composable
private fun PickerRow(
  title: String,
  detail: String?,
  @DrawableRes icon: Int,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val ink = if (selected) colors.onInk else colors.ink
  Row(
    modifier = modifier
      .fillMaxWidth()
      .focusRing(shape)
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .background(if (selected) colors.ink else colors.galley)
      .clickable(role = Role.RadioButton, onClick = onClick)
      .semantics(mergeDescendants = true) { this.selected = selected }
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Icon(painterResource(icon), null, tint = ink, modifier = Modifier.size(PhonoSizes.iconButton))
    Column(Modifier.weight(1f)) {
      PhonoText(title, style = MaterialTheme.typography.titleSmall, color = ink)
      detail?.let { PhonoText(it, style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onInk else colors.ink2) }
    }
    if (selected) Icon(painterResource(PhonoIcons.StepDone), stringResource(R.string.watch_picker_selected), tint = ink)
  }
}

/**
 * The short's "More" sheet (spec F-07 item 9, design 9.12): "Listen to" (when the clip has a dialogue track), the
 * caption mode, "Listen first", the v1-complete slots (shown only when their module is present, work-plan 4.4), "Open
 * the clip page", "Download the video" (when absent), "Leave this clip out of the Mix" and "What do the marks mean?".
 */
@Composable
internal fun MoreSheet(
  track: Track,
  showTrack: Boolean,
  captions: CaptionMode,
  listenFirst: Boolean,
  canPhraseDetails: Boolean,
  canRecord: Boolean,
  canDownloadVideo: Boolean,
  excluded: Boolean,
  onTrack: (Track) -> Unit,
  onCaptions: (CaptionMode) -> Unit,
  onListenFirst: (Boolean) -> Unit,
  onPhraseDetails: () -> Unit,
  onRecord: () -> Unit,
  onOpenClip: () -> Unit,
  onDownloadVideo: () -> Unit,
  onExcluded: (Boolean) -> Unit,
  onMarks: () -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = PhonoTheme.colors
  val title = stringResource(R.string.watch_more_title)
  PhonoSheet(onDismiss = onDismiss, title = title, modifier = Modifier.testTag(WatchTags.MORE_SHEET)) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    ) {
      PhonoText(title, style = MaterialTheme.typography.headlineSmall, color = colors.ink, modifier = Modifier.semantics { heading() })
      if (showTrack) {
        SheetLabel(stringResource(R.string.watch_listen_to), PhonoIcons.Track)
        SegmentedControl(
          options = listOf(SegmentedOption(stringResource(R.string.watch_track_mix)), SegmentedOption(stringResource(R.string.watch_track_dialogue))),
          selectedIndex = if (track == Track.MIX) 0 else 1,
          onSelect = { onTrack(if (it == 0) Track.MIX else Track.DIALOGUE) },
          modifier = Modifier.fillMaxWidth().testTag(WatchTags.MORE_TRACK),
        )
      }
      SheetLabel(stringResource(R.string.watch_captions), PhonoIcons.Captions)
      val modes = listOf(CaptionMode.MARKS, CaptionMode.PLAIN, CaptionMode.IPA, CaptionMode.OFF)
      SegmentedControl(
        options = listOf(
          SegmentedOption(stringResource(R.string.watch_captions_marks)),
          SegmentedOption(stringResource(R.string.watch_captions_plain)),
          SegmentedOption(stringResource(R.string.watch_captions_ipa)),
          SegmentedOption(stringResource(R.string.watch_captions_off_option)),
        ),
        selectedIndex = modes.indexOf(captions),
        onSelect = { onCaptions(modes[it]) },
        modifier = Modifier.fillMaxWidth().testTag(WatchTags.MORE_CAPTIONS),
      )
      PhonoSwitch(
        label = stringResource(R.string.watch_listen_first),
        description = stringResource(R.string.watch_listen_first_description),
        checked = listenFirst,
        onCheckedChange = onListenFirst,
        modifier = Modifier.testTag(WatchTags.MORE_LISTEN_FIRST),
      )
      if (canPhraseDetails) MoreRow(stringResource(R.string.watch_phrase_details), PhonoIcons.PhraseDetails, onPhraseDetails, WatchTags.MORE_PHRASE_DETAILS)
      if (canRecord) MoreRow(stringResource(R.string.watch_record), PhonoIcons.Record, onRecord, WatchTags.MORE_RECORD)
      MoreRow(stringResource(R.string.watch_open_clip_page), PhonoIcons.ClipPage, onOpenClip, WatchTags.MORE_CLIP_PAGE)
      if (canDownloadVideo) MoreRow(stringResource(R.string.watch_download_video), PhonoIcons.AvailVideo, onDownloadVideo, WatchTags.MORE_DOWNLOAD_VIDEO)
      PhonoSwitch(
        label = stringResource(R.string.watch_leave_out),
        description = stringResource(R.string.watch_leave_out_description),
        checked = excluded,
        onCheckedChange = onExcluded,
        modifier = Modifier.testTag(WatchTags.MORE_LEAVE_OUT),
      )
      MoreRow(stringResource(R.string.watch_marks_meaning), PhonoIcons.Legend, onMarks, WatchTags.MORE_MARKS)
    }
  }
}

@Composable
private fun SheetLabel(text: String, @DrawableRes icon: Int) {
  val colors = PhonoTheme.colors
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    PhonoText(text, style = MaterialTheme.typography.titleSmall, color = colors.ink)
  }
}

@Composable
private fun MoreRow(text: String, @DrawableRes icon: Int, onClick: () -> Unit, tag: String) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .focusRing(shape)
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = PhonoSpacing.s8)
      .testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
    PhonoText(text, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
    Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconButton))
  }
}

/** "What do the marks mean?": the legend of design 9.12 (WP-03's `MarksLegend`). */
@Composable
internal fun LegendSheet(onDismiss: () -> Unit) {
  PhonoSheet(onDismiss = onDismiss, title = stringResource(R.string.watch_marks_meaning), modifier = Modifier.testTag(WatchTags.LEGEND_SHEET)) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24)) { MarksLegend() }
  }
}

/** The rail's "Shadow" (F-12 on the current phrase): WP-18's shadowing panel in a sheet. */
@Composable
internal fun ShadowSheet(state: ShadowingState, onStart: (ShadowingOptions) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
  val colors = PhonoTheme.colors
  val options = ShadowingOptions()
  PhonoSheet(onDismiss = onDismiss, title = stringResource(R.string.watch_shadow_title), modifier = Modifier.testTag(WatchTags.SHADOW_SHEET)) {
    Column(Modifier.padding(bottom = PhonoSpacing.s24), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
      PhonoText(
        stringResource(R.string.watch_shadow_title),
        style = MaterialTheme.typography.headlineSmall,
        color = colors.ink,
        modifier = Modifier.semantics { heading() },
      )
      PhonoText(stringResource(R.string.watch_shadow_lede), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      ShadowingPanel(state = state, onStart = { onStart(options) }, onStop = onStop, options = options)
    }
  }
}
