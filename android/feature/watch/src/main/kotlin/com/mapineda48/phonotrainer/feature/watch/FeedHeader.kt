package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.ChipKind
import com.mapineda48.phonotrainer.core.designsystem.component.MenuButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoChip
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular

/**
 * The feed header (spec F-07 item 1, design 9.2): the mode picker ("Mix · Today's set ▾"), the position ("3 of 8" with
 * the bead row in a set, "4 / 15" otherwise, "Question 2 of 5", "Done"), the "On this phone" chip when the computer
 * is not reachable (the picker shortens to the mode's name), and "Previous short" / "Next short", the visible
 * equivalents of the swipe (F-06 item 4).
 */
@Composable
internal fun FeedHeader(
  mode: ModeName?,
  position: HeaderPosition,
  offline: Boolean,
  canPrevious: Boolean,
  canNext: Boolean,
  onPicker: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val largeText = LocalDensity.current.fontScale >= LARGE_TEXT
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    val (name, detail) = modeWords(mode)
    val label = stringResource(R.string.watch_mode_picker_label, listOfNotNull(name, detail).joinToString(" · "))
    MenuButton(
      text = name,
      onClick = onPicker,
      icon = if (mode is ModeName.InOrder) PhonoIcons.ModeInOrder else PhonoIcons.ModeMix,
      secondaryText = detail?.takeIf { !offline && !largeText }?.let { " · $it" },
      modifier = Modifier
        .weight(1f, fill = false)
        .semantics { contentDescription = label }
        .testTag(WatchTags.MODE_PICKER),
    )
    if (offline) {
      PhonoChip(
        stringResource(R.string.watch_on_this_phone),
        kind = ChipKind.MUTED,
        icon = PhonoIcons.AvailOnPhone,
        modifier = Modifier.testTag(WatchTags.ON_THIS_PHONE),
      )
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
      PositionWords(position, showBeads = !largeText)
      PhonoIconButton(
        PhonoIcons.ChevronUp,
        stringResource(R.string.watch_previous_short),
        onPrevious,
        enabled = canPrevious,
        modifier = Modifier.testTag(WatchTags.PREVIOUS),
      )
      PhonoIconButton(
        PhonoIcons.ChevronDown,
        stringResource(R.string.watch_next_short),
        onNext,
        enabled = canNext,
        modifier = Modifier.testTag(WatchTags.NEXT),
      )
    }
  }
}

/** The mode's name and its detail ("Mix", "Today's set"). */
@Composable
internal fun modeWords(mode: ModeName?): Pair<String, String?> = when (mode) {
  is ModeName.Mix -> stringResource(R.string.watch_mode_mix) to
    stringResource(if (mode.today) R.string.watch_mode_today else R.string.watch_mode_another_set)
  is ModeName.InOrder -> (mode.title ?: stringResource(R.string.watch_mode_in_order)) to
    stringResource(R.string.watch_mode_in_order).takeIf { mode.title != null }
  is ModeName.Change -> mode.name.replaceFirstChar { it.uppercase() } to null
  ModeName.Saved -> stringResource(R.string.watch_mode_saved) to null
  null -> stringResource(R.string.watch_mode_mix) to null
}

@Composable
private fun PositionWords(position: HeaderPosition, showBeads: Boolean) {
  val colors = PhonoTheme.colors
  val text = when (position) {
    is HeaderPosition.InSet -> stringResource(R.string.watch_position_set, position.number, position.of)
    is HeaderPosition.Ordinal -> stringResource(R.string.watch_position_ordinal, position.number, position.of)
    is HeaderPosition.Question -> stringResource(R.string.watch_position_question, position.number, position.of)
    is HeaderPosition.Done -> stringResource(R.string.watch_position_done)
    HeaderPosition.None -> return
  }
  val beads = when (position) {
    is HeaderPosition.InSet -> position.beads
    is HeaderPosition.Done -> position.beads
    else -> null
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = PhonoSpacing.s4)) {
    PhonoText(text, style = MaterialTheme.typography.titleSmall.tabular(), color = colors.ink, modifier = Modifier.testTag(WatchTags.POSITION))
    if (showBeads && beads != null) Beads(beads)
  }
}

/** The bead row of a set (design 9.2): done = filled dot, current = a 16 dp ink capsule, to come = rings. */
@Composable
private fun Beads(progress: SetProgress) {
  val colors = PhonoTheme.colors
  val dot = 6.dp
  val gap = 4.dp
  val capsule = 16.dp
  val width = dot * (progress.total - 1).coerceAtLeast(0) + gap * (progress.total - 1).coerceAtLeast(0) + capsule
  Canvas(Modifier.size(width, dot).clearAndSetSemantics { }.testTag(WatchTags.BEADS)) {
    var x = 0f
    val r = dot.toPx() / 2
    for (i in 0 until progress.total) {
      val isCurrent = !progress.finished && i == progress.current
      if (isCurrent) {
        drawRoundRect(colors.ink, Offset(x, 0f), Size(capsule.toPx(), dot.toPx()), CornerRadius(r))
        x += capsule.toPx() + gap.toPx()
      } else {
        val center = Offset(x + r, r)
        if (progress.finished || i < progress.done) drawCircle(colors.ink, r, center) else drawCircle(colors.ink, r - 0.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        x += dot.toPx() + gap.toPx()
      }
    }
  }
}
