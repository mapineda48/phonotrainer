package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.MouthMode
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer

// The mouth slots of Learn (work-plan 4.4): WP-29's `MouthRenderer` draws them in v1-complete; in v1-core nothing
// binds it and each slot says, in words, the sound's name and what the mouth does.

/** Each phone of the demo plays this long at normal speed, and [SLOW_FACTOR] times longer at "Slow". */
private const val PHONE_MS = 220L
private const val SLOW_FACTOR = 3L

/** The symbol sheet's movement: from rest to the sound in this long. */
private const val SYMBOL_MS = 600L

/**
 * "The mouth" of a phenomenon page (spec F-15 item 2, design 9.25, web `MouthDemo`): the word with its dictionary form
 * and what is said. With [mouth] (WP-29): the drawing, "What is said | Dictionary form", "Slow | Normal" and "Play the
 * movement". Without it (v1-core): the sound that changes, in each form, by its name and what the mouth does, and
 * "See it in the IPA chart".
 */
@Composable
internal fun MouthDemoCard(demo: MouthDemoUi, mouth: MouthRenderer?, onIpaChart: (String) -> Unit, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  PaperCard(modifier = modifier.fillMaxWidth().testTag(LearnTags.PHENOMENON_MOUTH)) {
    PhonoText(
      stringResource(R.string.phenomenon_mouth_title),
      style = MaterialTheme.typography.headlineSmall,
      color = colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    IpaText(
      stringResource(
        R.string.phenomenon_mouth_forms,
        demo.word,
        "/" + demo.dictionary.joinToString("") + "/",
        "[" + demo.said.joinToString("") + "]",
      ),
      style = MaterialTheme.typography.bodyLarge,
    )
    if (mouth != null) {
      DemoDrawing(demo, mouth)
    } else {
      DemoSound(stringResource(R.string.phenomenon_mouth_from), demo.from)
      val to = demo.to
      if (to != null) {
        DemoSound(stringResource(R.string.phenomenon_mouth_to), to)
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
          PhonoText(stringResource(R.string.phenomenon_mouth_to), style = MaterialTheme.typography.labelLarge, color = colors.ink2)
          PhonoText(stringResource(R.string.phenomenon_mouth_dropped), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        }
      }
    }
    ChartLink(
      symbol = demo.to?.symbol ?: demo.from.symbol,
      onIpaChart = onIpaChart,
      modifier = Modifier.testTag(LearnTags.PHENOMENON_MOUTH_CHART),
    )
  }
}

/** One sound of the demo in words: which form, "[ʌ] Open-mid back unrounded vowel" and what the mouth does. */
@Composable
private fun DemoSound(form: String, sound: SoundUi) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    PhonoText(form, style = MaterialTheme.typography.labelLarge, color = colors.ink2)
    IpaText(
      sound.name?.let { stringResource(R.string.phenomenon_sound, sound.symbol, it) } ?: "[${sound.symbol}]",
      style = MaterialTheme.typography.titleMedium,
    )
    sound.cue?.let { IpaText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
  }
}

/** WP-29's demo: the drawing over the chosen form at the chosen speed, still on the sound that changes. */
@Composable
private fun DemoDrawing(demo: MouthDemoUi, mouth: MouthRenderer) {
  var form by rememberSaveable { mutableIntStateOf(SAID) }
  var slow by rememberSaveable { mutableStateOf(true) }
  val phones = if (form == SAID) demo.said else demo.dictionary
  val changed = if (form == SAID) demo.to?.symbol else demo.from.symbol
  val step = if (slow) PHONE_MS * SLOW_FACTOR else PHONE_MS
  val timed = remember(phones, step) {
    phones.mapIndexed { index, symbol -> TimedPhone(symbol, index * step / MS, (index + 1) * step / MS) }
  }
  val at = changed?.let { phones.indexOf(it) }?.takeIf { it >= 0 } ?: (phones.size - 1).coerceAtLeast(0)
  MouthMovement(
    mouth = mouth,
    phones = timed,
    stillMs = at * step + step / 2,
    description = stringResource(R.string.phenomenon_mouth_view, "“${demo.word}”"),
  )
  SegmentedControl(
    options = listOf(
      SegmentedOption(stringResource(R.string.phenomenon_mouth_said)),
      SegmentedOption(stringResource(R.string.phenomenon_mouth_dictionary)),
    ),
    selectedIndex = form,
    onSelect = { form = it },
    modifier = Modifier.fillMaxWidth(),
  )
  SegmentedControl(
    options = listOf(
      SegmentedOption(stringResource(R.string.phenomenon_mouth_slow)),
      SegmentedOption(stringResource(R.string.phenomenon_mouth_normal)),
    ),
    selectedIndex = if (slow) 0 else 1,
    onSelect = { slow = it == 0 },
    modifier = Modifier.fillMaxWidth(),
  )
  PhonoText(stringResource(R.string.phenomenon_mouth_hint), style = MaterialTheme.typography.bodySmall, color = PhonoTheme.colors.ink2)
}

private const val SAID = 0
private const val MS = 1000.0

/**
 * WP-29's drawing of [phones] (a still at [stillMs]) with "Play the movement", which runs it once from the start and
 * comes back to the still. With reduced motion the drawing stays still.
 */
@Composable
internal fun MouthMovement(
  mouth: MouthRenderer,
  phones: List<TimedPhone>,
  stillMs: Long,
  description: String,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val reduced = LocalReducedMotion.current
  val track = remember(mouth, phones) { mouth.track(phones, MouthMode.STEP) }
  val end = ((phones.lastOrNull()?.endS ?: 0.0) * MS).toLong()
  var at by remember(phones, stillMs) { mutableLongStateOf(stillMs) }
  var runs by remember(phones) { mutableIntStateOf(0) }
  LaunchedEffect(runs, phones) {
    if (runs == 0 || reduced) return@LaunchedEffect
    val start = withFrameMillis { it }
    var elapsed = 0L
    while (elapsed < end) {
      elapsed = withFrameMillis { it } - start
      at = elapsed.coerceAtMost(end)
    }
    at = stillMs
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    val shape = RoundedCornerShape(PhonoRadius.sort)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(DrawingHeight)
        .clip(shape)
        .background(colors.tract.air)
        .border(PhonoSizes.hairline, colors.hairline, shape)
        .semantics { contentDescription = description }
        .testTag(LearnTags.PHENOMENON_MOUTH_DRAWING),
    ) {
      mouth.Mouth(track.frameAt(at), Modifier.fillMaxSize())
    }
    PhonoButton(
      text = stringResource(R.string.phenomenon_mouth_play),
      onClick = { runs++ },
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Play,
      modifier = Modifier.testTag(LearnTags.PHENOMENON_MOUTH_PLAY),
    )
  }
}

private val DrawingHeight = 132.dp

/** The symbol sheet's mouth (spec F-16): from rest to [symbol], still on the sound. */
@Composable
internal fun SymbolMouth(symbol: String, name: String?, mouth: MouthRenderer, modifier: Modifier = Modifier) {
  val phones = remember(symbol) { listOf(TimedPhone(symbol, 0.0, SYMBOL_MS / MS)) }
  MouthMovement(
    mouth = mouth,
    phones = phones,
    stillMs = SYMBOL_MS,
    description = stringResource(R.string.phenomenon_mouth_view, name?.let { "[$symbol] $it" } ?: "[$symbol]"),
    modifier = modifier.testTag(LearnTags.IPA_SHEET_MOUTH),
  )
}
