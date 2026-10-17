package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.paperDepth
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.ui.AvailabilityChip
import com.mapineda48.phonotrainer.core.ui.PosterArt
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.familyName
import com.mapineda48.phonotrainer.core.ui.formatClock
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines
import java.io.File

/** One family's count of changes in a clip, for the shelf card's family icons ("✂ 14"). */
@Immutable
data class FamilyCount(val family: Family, val count: Int)

/**
 * What a clip card or row shows (design 9.18): the clip, its local [poster] when the phone has one (`ClipMedia.poster`;
 * otherwise `PosterArt` draws the poster fallback or the audio art), and, for the shelf card, its changes per family.
 */
@Immutable
data class ClipCardState(
  val clip: ClipSummary,
  val poster: File? = null,
  val families: List<FamilyCount> = emptyList(),
)

/** Test tags of the clip card and row. */
object ClipCardTags {
  /** "Watch" (a custom accessibility action of the card and the row; the Library's menu has the visible item). */
  const val WATCH = "ui.clip.watch"

  /** The card or row's main target: it opens the Clip page. */
  const val OPEN = "ui.clip.open"

  /** The row's "More actions" button. */
  const val MORE = "ui.clip.more"
}

/**
 * The Home shelf card (design 9.18, render `02`; WP-16, API frozen at M2, work-plan 4.5): 176 dp wide, the poster at
 * 16:10 with the length on an overlay plate (or the audio art, or the poster fallback), the title on two lines,
 * "4 min 21 s · 12 shorts", the family icons with their counts and the availability icon and words. The whole card
 * opens the Clip page ([onOpen]); a long press is a shortcut for [onMore]; TalkBack reads it as one sentence and
 * offers "Watch" and "More actions" as actions.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ClipCard(
  state: ClipCardState,
  onWatch: () -> Unit,
  onOpen: () -> Unit,
  onMore: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val clip = state.clip
  val colors = PhonoTheme.colors
  val resources = LocalResources.current
  val families = Family.entries.associateWith { familyName(it) }
  val sentence = ClipCardCopy.cardSentence(state, families, resources)
  val openLabel = stringResource(R.string.clip_open)
  val watchLabel = stringResource(R.string.clip_watch)
  val moreLabel = stringResource(R.string.clip_more_short)
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Column(
    modifier = modifier
      .width(CARD_WIDTH)
      .paperDepth(colors, shape)
      .clip(shape)
      .background(colors.sheet)
      .combinedClickable(onClick = onOpen, onLongClick = onMore)
      .testTag(ClipCardTags.OPEN)
      .clearAndSetSemantics {
        contentDescription = sentence
        role = Role.Button
        onClick(openLabel) { onOpen(); true }
        onLongClick(moreLabel) { onMore(); true }
        customActions = listOf(
          CustomAccessibilityAction(watchLabel) { onWatch(); true },
          CustomAccessibilityAction(moreLabel) { onMore(); true },
        )
      },
  ) {
    Box(Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO)) {
      PosterArt(
        poster = state.poster,
        jobId = clip.id,
        audioOnly = clip.video == VideoAvailability.AudioOnlyClip,
        modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO),
        title = clip.title,
        chip = clip.durationS?.let { formatClock((it * 1_000).toLong()) },
      )
    }
    Column(
      modifier = Modifier.padding(PhonoSpacing.s10),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      PhonoText(
        clip.title,
        style = MaterialTheme.typography.titleSmall,
        color = colors.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      ClipCardCopy.cardMeta(clip, resources).takeIf { it.isNotBlank() }?.let {
        PhonoText(it, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
      }
      val counted = state.families.filter { it.count > 0 }
      if (counted.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
          counted.forEach { count ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
              Icon(
                painterResource(MarkLines.iconOf(count.family)),
                null,
                tint = colors.family.of(count.family),
                modifier = Modifier.size(PhonoSizes.iconInline),
              )
              PhonoText(ClipCardCopy.number(count.count), style = MaterialTheme.typography.labelMedium, color = colors.ink)
            }
          }
        }
      }
      AvailabilityChip(clip.availability)
      ClipCardCopy.origin(clip.origin, resources)?.let {
        ClipTag(it, PhonoIcons.Info)
      }
    }
  }
}

/** A small icon + words line of a card or row (source, "Kept", origin). The icon is decorative. */
@Composable
internal fun ClipTag(text: String, icon: Int, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    PhonoText(text, style = MaterialTheme.typography.bodySmall, color = colors.ink)
  }
}

private val CARD_WIDTH = 176.dp
private const val POSTER_RATIO = 16f / 10f

@PreviewLightDark
@Composable
private fun ClipCardPreview() {
  PhonoPreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      ClipCard(ClipCardSamples.shelf, onWatch = {}, onOpen = {}, onMore = {})
      ClipCard(ClipCardSamples.audioShelf, onWatch = {}, onOpen = {}, onMore = {})
    }
  }
}
