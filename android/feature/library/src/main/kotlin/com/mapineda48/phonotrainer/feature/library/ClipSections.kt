package com.mapineda48.phonotrainer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.R as UiR
import com.mapineda48.phonotrainer.core.ui.component.ClipCardCopy
import com.mapineda48.phonotrainer.core.ui.formatClock
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/**
 * One short of the Shorts tab (spec F-05 item 2, render `16`): the italic numeral, the spotlight chip with its word in
 * its mark ("Linking in “pick it up”") or "No labelled change here: listen to the rhythm.", the time range and the
 * count of marked words, and "Seen" (check + word) or "Next". Tapping it opens the feed In order at this short. TalkBack
 * reads the row as one sentence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortRow(row: ShortRowState, onOpen: () -> Unit) {
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val short = row.short
  val range = stringResource(R.string.clip_page_short_range, formatClock(short.playStartMs), formatClock(short.playEndMs))
  val marked = resources.getQuantityString(R.plurals.clip_page_short_marked, short.markedWords, short.markedWords)
  val spotlight = when {
    row.changeName == null -> stringResource(R.string.clip_page_short_no_change)
    row.words == null -> row.changeName
    else -> row.changeName + " " + stringResource(R.string.clip_page_short_in, row.words)
  }
  val status = when {
    row.seen -> stringResource(R.string.clip_page_short_seen)
    row.next -> stringResource(R.string.clip_page_short_next)
    else -> null
  }
  val sentence = listOfNotNull(
    stringResource(R.string.clip_page_short_number, short.ordinal),
    spotlight,
    range + ClipCardCopy.DOT + marked,
    status,
  ).joinToString(", ")
  val watch = stringResource(R.string.clip_page_short_watch)
  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = PhonoSizes.minTarget)
        .clickable(onClick = onOpen)
        .testTag(LibraryTags.short(short.id))
        .clearAndSetSemantics {
          contentDescription = sentence
          role = Role.Button
          onClick(watch) { onOpen(); true }
        }
        .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s12),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      PhonoText(
        short.ordinal.toString(),
        style = PhonoTheme.type.accentItalic,
        color = colors.ink,
        modifier = Modifier.widthIn(min = NUMERAL_WIDTH),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        if (row.changeName != null && row.family != null) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
            itemVerticalAlignment = Alignment.CenterVertically,
          ) {
            SpotlightChip(row.changeName, row.family)
            row.words?.let { words ->
              PhonoText(stringResource(R.string.clip_page_short_in_prefix), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
              MarkedWordSpan(
                text = stringResource(R.string.clip_page_short_quote, words),
                family = row.family,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
          }
        } else {
          PhonoText(spotlight, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
        }
        PhonoText(range + ClipCardCopy.DOT + marked, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      if (status != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
          if (row.seen) Icon(painterResource(PhonoIcons.StepDone), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
          PhonoText(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = if (row.seen) colors.ink else colors.ink2,
          )
        }
      }
    }
    Hairline()
  }
}

/** The spotlight's change: its family's tint, ring and icon with the change's name (design 9.1); not a control here. */
@Composable
private fun SpotlightChip(name: String, family: Family) {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier
      .clip(CircleShape)
      .background(colors.family.tintOf(family))
      .border(PhonoSizes.ring, colors.family.of(family), CircleShape)
      .padding(horizontal = PhonoSpacing.s10, vertical = PhonoSpacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(MarkLines.iconOf(family)), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(name, style = MaterialTheme.typography.labelMedium, color = colors.ink)
  }
}

/**
 * A row of "Changes in this clip" (spec F-05 item 4): the family icon, the change's name and practice badge, its count;
 * tapping it opens the feed in that change limited to this clip (F-05-AC1).
 */
@Composable
internal fun ChangeRow(change: ChangeRowState, onOpen: () -> Unit) {
  val colors = PhonoTheme.colors
  val advice = stringResource(
    if (change.advice == Advice.PRODUCE) UiR.string.ui_advice_produce else UiR.string.ui_advice_understand,
  )
  val sentence = stringResource(R.string.clip_page_change_description, change.name, change.count, advice)
  val watch = stringResource(R.string.clip_page_change_watch)
  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = PhonoSizes.row)
        .clickable(onClick = onOpen)
        .testTag(LibraryTags.change(change.label))
        .clearAndSetSemantics {
          contentDescription = sentence
          role = Role.Button
          onClick(watch) { onOpen(); true }
        }
        .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      Icon(
        painterResource(MarkLines.iconOf(change.family)),
        null,
        tint = colors.family.of(change.family),
        modifier = Modifier.size(PhonoSizes.iconBar),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        PhonoText(change.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        PracticeBadge(change.advice)
      }
      PhonoText(
        ClipCardCopy.number(change.count),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = colors.ink,
      )
      Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
    Hairline()
  }
}

/** "About this clip" (spec F-05 item 4): engine, speech model, dialogue separation, rules version and the source (a link opens only on a tap). */
@Composable
internal fun AboutClip(detail: ClipDetail, speechModel: String?, onOpenLink: (String) -> Unit) {
  val clip = detail.summary
  val source = clip.source
  Column(Modifier.testTag(LibraryTags.ABOUT)) {
    SectionHead(stringResource(R.string.clip_page_about))
    Column(
      Modifier.padding(horizontal = PhonoSpacing.gutter),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      ClipRules.engineLabel(detail.phoneEngine)?.let { Fact(stringResource(R.string.clip_page_about_engine), it) }
      speechModel?.let { Fact(stringResource(R.string.clip_page_about_speech), it) }
      detail.dialogueSeparation?.let {
        Fact(
          stringResource(R.string.clip_page_about_separation),
          stringResource(if (it) R.string.clip_page_about_separation_on else R.string.clip_page_about_separation_off),
        )
      }
      detail.rulesVersion?.let { Fact(stringResource(R.string.clip_page_about_rules), it.toString()) }
      val link = source.link
      when {
        link != null && ClipRules.openable(link) -> Column {
          PhonoText(stringResource(R.string.clip_page_about_source), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
          LinkButton(text = link, onClick = { onOpenLink(link) }, modifier = Modifier.testTag(LibraryTags.SOURCE_LINK))
        }
        link != null -> Fact(stringResource(R.string.clip_page_about_source), link)
        source.fileName != null -> Fact(stringResource(R.string.clip_page_about_file), source.fileName.orEmpty())
        else -> Fact(stringResource(R.string.clip_page_about_source), ClipCardCopy.source(source, LocalResources.current))
      }
    }
  }
}

/** A label and its value, one line for TalkBack. */
@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    PhonoText(label, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2, modifier = Modifier.weight(1f))
    PhonoText(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = PhonoTheme.colors.ink)
  }
}

/**
 * The "On this phone" block (spec F-05 item 5, design 9.26 v1-core part): its place head, the size rows, "Keep on this
 * phone" as a switch row, then full-width secondary buttons: "Download the video" or "Remove the video", and "Remove
 * from this phone…" (secondary, not danger: the clip can be downloaded again), which asks first.
 */
@Composable
internal fun OnThisPhone(
  detail: ClipDetail,
  onKeep: (Boolean) -> Unit,
  onDownloadVideo: () -> Unit,
  onRemoveVideo: () -> Unit,
  onRemove: () -> Unit,
) {
  val clip = detail.summary
  val resources = LocalResources.current
  PhonoCard(Modifier.padding(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, top = PhonoSpacing.s20).testTag(LibraryTags.ON_PHONE)) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      Kicker(stringResource(R.string.clip_page_on_phone), icon = PhonoIcons.AvailOnPhone)
      val pack = detail.studyPackBytes?.takeIf { it > 0 }
      val video = detail.videoBytes?.takeIf { it > 0 }
      pack?.let {
        Fact(stringResource(R.string.clip_page_size_pack), ClipCardCopy.bytes(it, resources), Modifier.testTag(LibraryTags.SIZE_PACK))
      }
      video?.let {
        Fact(stringResource(R.string.clip_page_size_video), ClipCardCopy.bytes(it, resources), Modifier.testTag(LibraryTags.SIZE_VIDEO))
      }
      if (pack == null && video == null) {
        PhonoText(stringResource(R.string.clip_page_nothing_here), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
      }
      PhonoSwitch(
        label = stringResource(R.string.clip_page_keep),
        checked = clip.kept,
        onCheckedChange = onKeep,
        description = stringResource(R.string.clip_page_keep_description),
        modifier = Modifier.testTag(LibraryTags.KEEP),
      )
      if (clip.availability == ClipAvailability.OnThisPhone && clip.video == VideoAvailability.NotOnThisPhone) {
        PhonoButton(
          text = stringResource(R.string.clip_page_download_video),
          onClick = onDownloadVideo,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.StatusDownloading,
          fillWidth = true,
          modifier = Modifier.testTag(LibraryTags.DOWNLOAD_VIDEO),
        )
      }
      if (LibraryRules.videoRemovable(clip)) {
        PhonoButton(
          text = stringResource(R.string.clip_page_remove_video),
          onClick = onRemoveVideo,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.AvailVideo,
          fillWidth = true,
          modifier = Modifier.testTag(LibraryTags.REMOVE_VIDEO),
        )
      }
      if (LibraryRules.removable(clip)) {
        PhonoButton(
          text = stringResource(R.string.clip_page_remove_from_phone),
          onClick = onRemove,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.Remove,
          fillWidth = true,
          modifier = Modifier.testTag(LibraryTags.REMOVE_FROM_PHONE),
        )
      }
    }
  }
}

@Composable
private fun Hairline() {
  Box(
    Modifier
      .padding(horizontal = PhonoSpacing.gutter)
      .fillMaxWidth()
      .height(PhonoSizes.hairline)
      .background(PhonoTheme.colors.hairline),
  )
}

private val NUMERAL_WIDTH = 28.dp
