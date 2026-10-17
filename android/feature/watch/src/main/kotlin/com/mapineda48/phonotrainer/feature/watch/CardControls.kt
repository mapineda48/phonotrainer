package com.mapineda48.phonotrainer.feature.watch

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.ui.ShortProgress
import com.mapineda48.phonotrainer.core.ui.formatClock

/** One action of the rail or the bar (design 9.6). */
internal data class CardAction(
  val tag: String,
  @DrawableRes val icon: Int,
  val label: String,
  val description: String,
  val toggled: Boolean,
  val onClick: () -> Unit,
)

/** "1", "0.75", "0.5": a speed without trailing zeros. */
internal fun speedText(speed: Float): String {
  val rounded = Math.round(speed * 100) / 100f
  return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
}

/** The six actions of a short (F-07 item 6): Replay, Speed, Loop, Shadow, Save, More. */
@Composable
internal fun cardActions(
  speed: Float,
  loop: LoopMode,
  saved: Boolean,
  onReplay: () -> Unit,
  onSpeed: () -> Unit,
  onLoop: () -> Unit,
  onShadow: () -> Unit,
  onSave: () -> Unit,
  onMore: () -> Unit,
): List<CardAction> {
  val speedValue = speedText(speed)
  val loopLabel = stringResource(
    when (loop) {
      LoopMode.OFF -> R.string.watch_loop_off
      LoopMode.PHRASE -> R.string.watch_loop_phrase
      LoopMode.SHORT -> R.string.watch_loop_short
    },
  )
  return listOf(
    CardAction(WatchTags.REPLAY, PhonoIcons.Replay, stringResource(R.string.watch_replay), stringResource(R.string.watch_replay_description), false, onReplay),
    CardAction(
      WatchTags.SPEED,
      PhonoIcons.Speed,
      stringResource(R.string.watch_speed_value, speedValue),
      stringResource(R.string.watch_speed_description, speedValue),
      kotlin.math.abs(speed - 1f) > 0.01f,
      onSpeed,
    ),
    CardAction(WatchTags.LOOP, if (loop == LoopMode.PHRASE) PhonoIcons.LoopPhrase else PhonoIcons.Loop, loopLabel, loopLabel, loop != LoopMode.OFF, onLoop),
    CardAction(WatchTags.SHADOW, PhonoIcons.Shadow, stringResource(R.string.watch_shadow), stringResource(R.string.watch_shadow), false, onShadow),
    CardAction(
      WatchTags.SAVE,
      if (saved) PhonoIcons.Saved else PhonoIcons.Save,
      stringResource(if (saved) R.string.watch_saved else R.string.watch_save),
      stringResource(if (saved) R.string.watch_saved else R.string.watch_save),
      saved,
      onSave,
    ),
    CardAction(WatchTags.MORE, PhonoIcons.MoreHorizontal, stringResource(R.string.watch_more), stringResource(R.string.watch_more_description), false, onMore),
  )
}

/** The rail on the thumb side (design 9.6): 44 dp circles in 64 × 60 dp targets, a word under each. */
@Composable
internal fun ActionRail(actions: List<CardAction>, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.width(PhonoSizes.railItem.width).testTag(WatchTags.RAIL),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    actions.forEach { action -> RailItem(action) }
  }
}

@Composable
private fun RailItem(action: CardAction) {
  val colors = PhonoTheme.colors
  Column(
    modifier = Modifier
      .focusRing(RoundedCornerShape(PhonoRadius.medium))
      .width(PhonoSizes.railItem.width)
      .heightIn(min = PhonoSizes.railItem.height)
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .clickable(role = Role.Button, onClick = action.onClick)
      .testTag(action.tag)
      .clearAndSetSemantics {
        contentDescription = action.description
        role = Role.Button
        if (action.tag == WatchTags.SAVE) stateDescription = action.label
      }
      .padding(vertical = PhonoSpacing.s2),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(if (action.toggled) colors.ink else colors.sheet)
        .then(if (action.toggled) Modifier else Modifier.border(PhonoSizes.ring, colors.lineStrong, CircleShape)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(action.icon), null, tint = if (action.toggled) colors.onInk else colors.ink, modifier = Modifier.size(22.dp))
    }
    PhonoText(action.label, style = MaterialTheme.typography.labelMedium, color = colors.ink, textAlign = TextAlign.Center, maxLines = 2)
  }
}

/**
 * The large-text action bar (design 9.6, render `27`): two rows of three labelled cells, at least 64 dp tall, icon and
 * word side by side, under the progress; the captions get the full width.
 */
@Composable
internal fun ActionBar(actions: List<CardAction>, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().testTag(WatchTags.ACTION_BAR), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    actions.chunked(3).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        row.forEach { action ->
          Row(
            modifier = Modifier
              .weight(1f)
              .focusRing(RoundedCornerShape(PhonoRadius.medium))
              .heightIn(min = 64.dp)
              .clip(RoundedCornerShape(PhonoRadius.medium))
              .background(if (action.toggled) colors.ink else colors.sheet)
              .border(PhonoSizes.ring, if (action.toggled) colors.ink else colors.lineStrong, RoundedCornerShape(PhonoRadius.medium))
              .clickable(role = Role.Button, onClick = action.onClick)
              .testTag(action.tag)
              .clearAndSetSemantics {
                contentDescription = action.description
                role = Role.Button
                if (action.tag == WatchTags.SAVE) stateDescription = action.label
              }
              .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
          ) {
            val tint = if (action.toggled) colors.onInk else colors.ink
            Icon(painterResource(action.icon), null, tint = tint, modifier = Modifier.size(PhonoSizes.iconButton))
            PhonoText(action.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 2)
          }
        }
      }
    }
  }
}

/**
 * The progress row (design 9.7): the 48 dp play/pause disc, the stylus trace of the short (ticks at phrase starts, the
 * spotlight's pin, the nib) as a slider with "Back 2 s" / "Forward 2 s", and the elapsed time.
 */
@Composable
internal fun ProgressRow(
  content: ShortContent?,
  playhead: State<Long>,
  playing: Boolean,
  onTogglePlay: () -> Unit,
  onSeek: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val label = stringResource(if (playing) R.string.watch_pause else R.string.watch_play)
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Box(
      modifier = Modifier
        .focusRing(CircleShape)
        .size(PhonoSizes.minTarget)
        .clip(CircleShape)
        .background(colors.ink)
        .clickable(role = Role.Button, onClick = onTogglePlay)
        .semantics { contentDescription = label }
        .testTag(WatchTags.PLAY_PAUSE),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(if (playing) PhonoIcons.Pause else PhonoIcons.Play), null, tint = colors.onInk)
    }
    val start = content?.playStartMs ?: 0L
    val length = content?.lengthMs ?: 1L
    val elapsed by remember(content) {
      derivedStateOf { ((playhead.value - start).coerceIn(0L, length) / PROGRESS_STEP_MS) * PROGRESS_STEP_MS }
    }
    val pin = content?.pin
    val spot = content?.spotlight
    val pinNow by remember(content) { derivedStateOf { spot != null && playhead.value in spot.startMs..spot.endMs } }
    ShortProgress(
      progress = elapsed / length.toFloat(),
      samples = content?.trace.orEmpty(),
      ticks = content?.ticks.orEmpty(),
      pin = pin,
      pinNow = pinNow,
      positionMs = elapsed,
      durationMs = length,
      onSeek = onSeek,
      modifier = Modifier.weight(1f).testTag(WatchTags.PROGRESS),
    )
  }
}

/** The clip line (design 9.7): the clip title (opens the Clip page) and "Short 4 of 15 · 0:42–0:58"; then the track
 *  line when the other track plays (D-47). */
@Composable
internal fun ClipLine(
  page: FeedPage.Short,
  title: String?,
  trackFallback: TrackFallback?,
  onOpenClip: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val shown = title ?: stringResource(R.string.watch_clip_untitled)
  val openLabel = stringResource(R.string.watch_open_clip, shown)
  Column(modifier.fillMaxWidth().testTag(WatchTags.CLIP_LINE), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
    Row(
      modifier = Modifier
        .heightIn(min = PhonoSizes.minTarget)
        .clip(RoundedCornerShape(PhonoRadius.small))
        .clickable(role = Role.Button, onClick = onOpenClip)
        .testTag(WatchTags.CLIP_TITLE)
        .clearAndSetSemantics {
          contentDescription = openLabel
          role = Role.Button
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      PhonoText(shown, style = MaterialTheme.typography.titleSmall, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
      Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
    PhonoText(
      stringResource(
        R.string.watch_clip_line,
        page.info.ordinal,
        page.info.ofTotal,
        formatClock(page.info.playStartMs),
        formatClock(page.info.playEndMs),
      ),
      style = MaterialTheme.typography.bodySmall,
      color = colors.ink2,
    )
    trackFallback?.let { TrackLine(it) }
  }
}

/** The quiet track line under the clip line (design 9.8, spec D-47). */
@Composable
internal fun TrackLine(fallback: TrackFallback, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier.testTag(WatchTags.TRACK_LINE),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(PhonoIcons.Track), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(
      stringResource(
        when (fallback) {
          TrackFallback.DIALOGUE_NOT_ON_PHONE -> R.string.watch_track_dialogue_missing
          TrackFallback.MIX_NOT_ON_PHONE -> R.string.watch_track_mix_missing
          TrackFallback.NO_DIALOGUE_TRACK -> R.string.watch_track_no_dialogue
        },
      ),
      style = MaterialTheme.typography.bodySmall,
      color = colors.ink2,
    )
  }
}
