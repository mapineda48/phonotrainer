package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R
import java.math.BigDecimal
import kotlin.math.tan

/** What the shadowing loop repeats (spec F-12): the word (with the next one when there is one) or the whole phrase. */
enum class ShadowingTarget { WORD, PHRASE }

/**
 * The panel's options (spec F-12): how many slow rounds, the slow speed of the listening rounds and, since WP-18, the
 * choices and notes around them. Every addition has a default, so `ShadowingOptions()` (the rail's "Shadow") still
 * means "the current phrase at 0.75×, no choices shown".
 */
@Immutable
data class ShadowingOptions(
  val rounds: Int = 3,
  val slowSpeed: Float = 0.75f,
  /** The target choice ("The word and the next" / "The whole phrase"); null offers none (the rail's current phrase). */
  val target: ShadowingTarget? = null,
  /** The word option's words: "The word and the next" when a next word follows, otherwise "The word". */
  val wordAndNext: Boolean = true,
  /** Why shadowing cannot start (no audio on this phone); "Start shadowing" is disabled and this is said under it. */
  val unavailableReason: String? = null,
  /** The change is "Recognize only": the panel adds that shadowing it trains the ear (spec F-12). */
  val recognizeOnly: Boolean = false,
)

/** The slow speeds the panel offers (spec F-12: 0.5× or 0.75×; 1× is a plan branch the screen never reaches). */
val ShadowingSpeeds: List<Float> = listOf(0.5f, 0.75f)

/** Test tags of the shadowing panel. */
object ShadowingTags {
  const val START = "ui.shadowing.start"
  const val STOP = "ui.shadowing.stop"
  const val INTRO = "ui.shadowing.intro"
  const val RECOGNIZE_ONLY = "ui.shadowing.recognize_only"
  const val TARGET = "ui.shadowing.target"
  const val SPEED = "ui.shadowing.speed"
  const val REASON = "ui.shadowing.reason"
  const val RUNNING = "ui.shadowing.running"
  const val LINE = "ui.shadowing.line"
  const val PHASES = "ui.shadowing.phases"
}

/**
 * Shadowing (spec F-12, design 9.11 step 5; API frozen at M2, work-plan 4.5): lesson step 5 and the rail's "Shadow".
 *
 * Idle: the web's intro line ([showIntro]), the "Recognize only" note, the target and slow-speed choices (only when
 * [onOptionsChange] is given; the target choice only when [ShadowingOptions.target] is set) and "Start shadowing",
 * disabled with its reason when [ShadowingOptions.unavailableReason] says so. Running: a card with the live line
 * ("Round 2 of 4: your turn — say it now"), the 8-phase bar (listen = square cell, your turn = rounded cell; done = ink,
 * current = ink hatch, to come = outline) and "Stop"; the choices stay visible and disabled. The runner announces each
 * phase politely (F-12-AC4), so the line here is not a live region (it would be read twice); "your turn" adds a short
 * haptic tick, which follows the system's haptics setting and is never the only cue.
 */
@Composable
fun ShadowingPanel(
  state: ShadowingState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  options: ShadowingOptions,
  modifier: Modifier = Modifier,
  onOptionsChange: ((ShadowingOptions) -> Unit)? = null,
  showIntro: Boolean = onOptionsChange != null,
) {
  val colors = PhonoTheme.colors
  val running = state as? ShadowingState.Running
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    if (showIntro) {
      PhonoText(
        stringResource(R.string.shadowing_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
        modifier = Modifier.testTag(ShadowingTags.INTRO),
      )
    }
    if (options.recognizeOnly) {
      Notice(
        stringResource(R.string.shadowing_recognize_only),
        kind = NoticeKind.TIP,
        modifier = Modifier.testTag(ShadowingTags.RECOGNIZE_ONLY),
      )
    }
    if (onOptionsChange != null) {
      Choices(options, enabled = running == null, onOptionsChange = onOptionsChange)
    }
    if (running == null) {
      PhonoButton(
        text = stringResource(R.string.shadowing_start),
        onClick = onStart,
        modifier = Modifier.testTag(ShadowingTags.START),
        enabled = options.unavailableReason == null,
        leadingIcon = PhonoIcons.Shadow,
      )
      options.unavailableReason?.let { reason ->
        PhonoText(
          reason,
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(ShadowingTags.REASON),
        )
      }
    } else {
      RunningCard(running, onStop)
    }
  }
}

@Composable
private fun Choices(options: ShadowingOptions, enabled: Boolean, onOptionsChange: (ShadowingOptions) -> Unit) {
  options.target?.let { target ->
    val word = stringResource(
      if (options.wordAndNext) R.string.shadowing_target_word_and_next else R.string.shadowing_target_word,
    )
    SegmentedControl(
      options = listOf(SegmentedOption(word), SegmentedOption(stringResource(R.string.shadowing_target_phrase))),
      selectedIndex = if (target == ShadowingTarget.WORD) 0 else 1,
      onSelect = { index ->
        onOptionsChange(options.copy(target = if (index == 0) ShadowingTarget.WORD else ShadowingTarget.PHRASE))
      },
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().testTag(ShadowingTags.TARGET),
    )
  }
  val selected = ShadowingSpeeds.indices.minBy { kotlin.math.abs(ShadowingSpeeds[it] - options.slowSpeed) }
  SegmentedControl(
    options = ShadowingSpeeds.map { SegmentedOption(stringResource(R.string.shadowing_speed_option, speedWords(it))) },
    selectedIndex = selected,
    onSelect = { onOptionsChange(options.copy(slowSpeed = ShadowingSpeeds[it])) },
    enabled = enabled,
    modifier = Modifier.fillMaxWidth().testTag(ShadowingTags.SPEED),
  )
}

@Composable
private fun RunningCard(state: ShadowingState.Running, onStop: () -> Unit) {
  val colors = PhonoTheme.colors
  val haptics = LocalHapticFeedback.current
  val phase = phaseIndex(state)
  LaunchedEffect(phase) {
    if (state.phase == ShadowingPhase.YOUR_TURN) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
  }
  PhonoCard(modifier = Modifier.fillMaxWidth().testTag(ShadowingTags.RUNNING)) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
        Icon(painterResource(PhonoIcons.Shadow), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
        PhonoText(
          liveLine(state),
          style = MaterialTheme.typography.titleMedium,
          color = colors.ink,
          modifier = Modifier.weight(1f).testTag(ShadowingTags.LINE),
        )
      }
      PhaseBar(current = phase, total = state.of * 2)
      PhonoText(
        stringResource(R.string.shadowing_plan_line, state.of),
        style = MaterialTheme.typography.bodySmall,
        color = colors.ink2,
      )
      PhonoButton(
        text = stringResource(R.string.shadowing_stop),
        onClick = onStop,
        modifier = Modifier.testTag(ShadowingTags.STOP),
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.Stop,
      )
    }
  }
}

/** The line of the phase on screen, in the runner's announced words (the web's `describePhase`). */
@Composable
private fun liveLine(state: ShadowingState.Running): String = when {
  state.phase == ShadowingPhase.YOUR_TURN -> stringResource(R.string.shadowing_your_turn, state.round, state.of)
  state.speed >= 1f -> stringResource(R.string.shadowing_listen_full, state.round, state.of)
  else -> stringResource(R.string.shadowing_listen, state.round, state.of, speedWords(state.speed))
}

/** The 8-phase bar: one cell per phase, read by TalkBack as "Phase 4 of 8". */
@Composable
private fun PhaseBar(current: Int, total: Int) {
  val colors = PhonoTheme.colors
  val description = stringResource(R.string.shadowing_phases, current + 1, total)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(ShadowingTags.PHASES)
      .clearAndSetSemantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo((current + 1).toFloat(), 0f..total.toFloat(), steps = total - 1)
      },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    repeat(total) { index ->
      val shape = if (index % 2 == 0) ListenCell else TurnCell
      val cell = Modifier.weight(1f).height(PhaseCellHeight)
      when {
        index < current -> Box(cell.clip(shape).background(colors.ink))
        index == current -> Box(cell.hatched(colors.ink, shape).border(PhonoSizes.ring, colors.ink, shape))
        else -> Box(cell.border(PhonoSizes.ring, colors.outline, shape))
      }
    }
  }
}

/** Fills the shape with ink lines leaning at 35° (the current phase, design 9.11). */
private fun Modifier.hatched(color: Color, shape: Shape): Modifier = drawWithCache {
  val outline = shape.createOutline(size, layoutDirection, this)
  val clip = Path().apply { addOutline(outline) }
  val gap = HatchGap.toPx()
  val stroke = HatchStroke.toPx()
  val lean = size.height * tan(Math.toRadians(HATCH_DEGREES)).toFloat()
  onDrawBehind {
    clipPath(clip) {
      var x = -lean
      while (x < size.width + lean) {
        drawLine(color, Offset(x, size.height), Offset(x + lean, 0f), stroke)
        x += gap
      }
    }
  }
}

/** The phase on screen as an index of the plan: (round − 1) × 2, plus 1 for "your turn". */
internal fun phaseIndex(state: ShadowingState.Running): Int =
  (state.round - 1) * 2 + if (state.phase == ShadowingPhase.YOUR_TURN) 1 else 0

/** 0.75 → "0.75", 0.5 → "0.5" (the web writes `${rate}×`). */
internal fun speedWords(speed: Float): String = BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()

private val ListenCell = RoundedCornerShape(2.dp)
private val TurnCell = CircleShape
private val PhaseCellHeight = 12.dp
private val HatchGap = 5.dp
private val HatchStroke = 1.5.dp
private const val HATCH_DEGREES = 35.0

@PreviewLightDark
@Composable
private fun ShadowingPanelPreview() {
  PhonoPreviewSurface {
    ShadowingPanel(
      ShadowingState.Idle,
      onStart = {},
      onStop = {},
      options = ShadowingOptions(target = ShadowingTarget.WORD),
      onOptionsChange = {},
    )
    ShadowingPanel(
      ShadowingState.Running(round = 2, of = 4, phase = ShadowingPhase.YOUR_TURN, speed = 0.75f),
      onStart = {},
      onStop = {},
      options = ShadowingOptions(target = ShadowingTarget.WORD),
      onOptionsChange = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun ShadowingPanelUnavailablePreview() {
  PhonoPreviewSurface {
    ShadowingPanel(
      ShadowingState.Idle,
      onStart = {},
      onStop = {},
      options = ShadowingOptions(
        target = ShadowingTarget.PHRASE,
        recognizeOnly = true,
        unavailableReason = "This clip came without its audio: you can read it, but not listen to it.",
      ),
      onOptionsChange = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun ShadowingPanelRailPreview() {
  PhonoPreviewSurface {
    ShadowingPanel(ShadowingState.Idle, onStart = {}, onStop = {}, options = ShadowingOptions())
  }
}
