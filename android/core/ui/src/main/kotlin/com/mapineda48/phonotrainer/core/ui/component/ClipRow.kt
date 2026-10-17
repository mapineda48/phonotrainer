package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.ui.AvailabilityChip
import com.mapineda48.phonotrainer.core.ui.PosterArt
import com.mapineda48.phonotrainer.core.ui.R

/**
 * A Library row (design 9.18, render `15`; WP-16, API frozen at M2, work-plan 4.5): the poster or audio art (104 × 66
 * dp), the title, the meta line "4 min 21 s · 691 words · 14 changes", the tags (source icon + word, availability icon
 * + words, the "Kept" pin), "You've seen 12 of 15 shorts", the muted "Older rules" chip and the "From a computer you
 * forgot" / "From another library" tag; and the "More actions" button (ellipsis-vertical).
 *
 * The row opens the Clip page ([onOpen]); a long press is only a shortcut for [onMore]. TalkBack reads the row as one
 * sentence, the same words it shows ([ClipCardCopy.rowSentence]), with "Watch" and "More actions" as actions. The
 * caller's menu goes in [moreMenu], composed next to the button so that a dropdown anchors to it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ClipRow(
  state: ClipCardState,
  onWatch: () -> Unit,
  onOpen: () -> Unit,
  onMore: () -> Unit,
  modifier: Modifier = Modifier,
  moreMenu: @Composable () -> Unit = {},
) {
  val clip = state.clip
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val sentence = ClipCardCopy.rowSentence(clip, resources)
  val openLabel = stringResource(R.string.clip_open)
  val watchLabel = stringResource(R.string.clip_watch)
  val moreShort = stringResource(R.string.clip_more_short)
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Row(
      modifier = Modifier
        .weight(1f)
        .combinedClickable(onClick = onOpen, onLongClick = onMore)
        .testTag(ClipCardTags.OPEN)
        .clearAndSetSemantics {
          contentDescription = sentence
          role = Role.Button
          onClick(openLabel) { onOpen(); true }
          onLongClick(moreShort) { onMore(); true }
          customActions = listOf(
            CustomAccessibilityAction(watchLabel) { onWatch(); true },
            CustomAccessibilityAction(moreShort) { onMore(); true },
          )
        }
        .padding(vertical = PhonoSpacing.s12),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      PosterArt(
        poster = state.poster,
        jobId = clip.id,
        audioOnly = clip.video == VideoAvailability.AudioOnlyClip,
        modifier = Modifier.size(POSTER_WIDTH, POSTER_HEIGHT),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        PhonoText(clip.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        ClipCardCopy.rowMeta(clip, resources).takeIf { it.isNotBlank() }?.let {
          PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
          verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
        ) {
          ClipTag(ClipCardCopy.source(clip.source, resources), ClipCardCopy.sourceIcon(clip.source))
          AvailabilityChip(clip.availability)
          ClipCardCopy.kept(clip, resources)?.let { ClipTag(it, PhonoIcons.Keep) }
        }
        ClipCardCopy.seen(clip, resources)?.let {
          PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
        ClipCardCopy.olderRules(clip, resources)?.let { PhonoBadge(it, icon = PhonoIcons.Info) }
        ClipCardCopy.origin(clip.origin, resources)?.let { ClipTag(it, PhonoIcons.Info) }
      }
    }
    Box(Modifier.padding(top = PhonoSpacing.s4)) {
      PhonoIconButton(
        icon = PhonoIcons.MoreVertical,
        contentDescription = stringResource(R.string.clip_more, clip.title),
        onClick = onMore,
        modifier = Modifier.testTag(ClipCardTags.MORE),
      )
      moreMenu()
    }
  }
}

private val POSTER_WIDTH = 104.dp
private val POSTER_HEIGHT = 66.dp

@PreviewLightDark
@Composable
private fun ClipRowPreview() {
  PhonoPreviewSurface {
    Column {
      ClipCardSamples.rows.forEach { ClipRow(it, onWatch = {}, onOpen = {}, onMore = {}) }
    }
  }
}
