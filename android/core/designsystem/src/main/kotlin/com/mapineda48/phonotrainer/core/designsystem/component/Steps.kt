package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * Draws the trace between node centres at [centerY] (design 1.2: solid = done, dotted = not yet). [doneUntil] is the
 * fraction of the width drawn solid; the rest is a dotted pencil hairline.
 */
private fun Modifier.routeTrace(doneUntil: Float, centerY: Dp, ink: Color, muted: Color, inset: Float): Modifier =
  drawBehind {
    val y = centerY.toPx()
    val start = size.width * inset
    val end = size.width * (1f - inset)
    val split = start + (end - start) * doneUntil.coerceIn(0f, 1f)
    if (split > start) drawLine(ink, Offset(start, y), Offset(split, y), 2.5.dp.toPx(), StrokeCap.Round)
    if (end > split) {
      drawLine(
        muted, Offset(split, y), Offset(end, y), 1.dp.toPx(), StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx())),
      )
    }
  }

/**
 * The route line of a waiting request (design 9.19, 9.22): 44 dp nodes ("Phone", "Computer", "Ready") joined by the
 * trace, solid with the nib for the part done and dotted for the rest. The node at [current] is ink-filled. The whole
 * line is one node for TalkBack, read as [description].
 */
@Composable
fun RouteLine(
  nodes: List<RouteNode>,
  current: Int,
  description: String,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val n = nodes.size.coerceAtLeast(1)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clearAndSetSemantics { contentDescription = description }
      .routeTrace(current.toFloat() / (n - 1).coerceAtLeast(1), 22.dp, colors.ink, colors.inkMuted, 0.5f / n),
  ) {
    nodes.forEachIndexed { index, node ->
      Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        val here = index == current
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (here) colors.ink else colors.sheet)
            .border(PhonoSizes.ring, if (index <= current) colors.ink else colors.outline, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            painterResource(node.icon),
            null,
            tint = if (here) colors.onInk else colors.ink,
            modifier = Modifier.size(PhonoSizes.iconButton),
          )
        }
        PhonoText(
          node.label,
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (here) FontWeight.Bold else FontWeight.Medium),
          color = colors.ink,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = PhonoSpacing.s6),
        )
      }
    }
  }
}

/** A node of the [RouteLine]. */
data class RouteNode(val label: String, val icon: Int)

/** The state of one stage of the analysis stepper (design 9.19). */
enum class StepState { DONE, CURRENT, NEXT, SKIPPED, FAILED, CANCELLED }

/** One stage: its short name ("Words") and its state. */
data class StepperStep(val label: String, val state: StepState)

/**
 * The stepper of a request on the computer (design 9.19): six nodes on a trace, the six stage names under them (the
 * current one bold), and the [teaching] line in a notice well under the row. Done = ink disc and check; current = a
 * 36 dp ring and the loader, turning while motion is allowed; next = an outline node with its number; skipped = the
 * skip icon; failed = a critical ring and a cross; cancelled = the slash. One TalkBack node, read as [description].
 */
@Composable
fun Stepper(
  steps: List<StepperStep>,
  description: String,
  modifier: Modifier = Modifier,
  teaching: String? = null,
) {
  val colors = PhonoTheme.colors
  val reduced = PhonoTheme.motion.reduced
  val doneCount = steps.indexOfFirst { it.state != StepState.DONE && it.state != StepState.SKIPPED }
    .let { if (it < 0) steps.size else it }
  val n = steps.size.coerceAtLeast(1)
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics { contentDescription = description }
        .routeTrace(doneCount.toFloat() / (n - 1).coerceAtLeast(1), 18.dp, colors.ink, colors.inkMuted, 0.5f / n),
    ) {
      steps.forEachIndexed { index, step ->
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            StepNode(step.state, index + 1, reduced)
          }
          PhonoText(
            step.label,
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = if (step.state == StepState.CURRENT) FontWeight.Bold else FontWeight.Medium,
            ),
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = PhonoSpacing.s6),
          )
        }
      }
    }
    teaching?.let { Notice(it) }
  }
}

@Composable
private fun StepNode(state: StepState, number: Int, reduced: Boolean) {
  val colors = PhonoTheme.colors
  when (state) {
    StepState.DONE -> NodeDisc(28.dp, colors.ink, null) {
      Icon(painterResource(PhonoIcons.StepDone), null, tint = colors.onInk, modifier = Modifier.size(16.dp))
    }
    StepState.CURRENT -> NodeDisc(36.dp, colors.sheet, colors.ink) {
      val angle = if (reduced) {
        0f
      } else {
        val spin by rememberInfiniteTransition(label = "loader").animateFloat(
          0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
        )
        spin
      }
      Icon(painterResource(PhonoIcons.StatusAnalyzing), null, tint = colors.ink, modifier = Modifier.size(18.dp).rotate(angle))
    }
    StepState.NEXT -> NodeDisc(28.dp, colors.sheet, colors.outline) {
      PhonoText("$number", style = MaterialTheme.typography.labelMedium, color = colors.ink)
    }
    StepState.SKIPPED -> NodeDisc(28.dp, colors.sheet, colors.outline) {
      Icon(painterResource(PhonoIcons.StepSkipped), null, tint = colors.ink, modifier = Modifier.size(16.dp))
    }
    StepState.FAILED -> NodeDisc(28.dp, colors.sheet, colors.critical) {
      Icon(painterResource(PhonoIcons.Close), null, tint = colors.ink, modifier = Modifier.size(16.dp))
    }
    StepState.CANCELLED -> NodeDisc(28.dp, colors.sheet, colors.outline) {
      Icon(painterResource(PhonoIcons.StatusCancelled), null, tint = colors.ink, modifier = Modifier.size(16.dp))
    }
  }
}

@Composable
private fun NodeDisc(size: Dp, fill: Color, ring: Color?, content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(fill)
      .then(if (ring != null) Modifier.border(PhonoSizes.ring, ring, CircleShape) else Modifier),
    contentAlignment = Alignment.Center,
  ) { content() }
}

@PreviewLightDark
@Composable
private fun StepsPreview() {
  PhonoPreviewSurface {
    RouteLine(
      nodes = listOf(
        RouteNode("Phone", PhonoIcons.AvailOnPhone),
        RouteNode("Computer", PhonoIcons.AvailOnComputer),
        RouteNode("Ready", PhonoIcons.StatusReady),
      ),
      current = 0,
      description = "Waiting on this phone. Next: your computer.",
    )
    Stepper(
      steps = listOf(
        StepperStep("Download", StepState.DONE),
        StepperStep("Audio", StepState.DONE),
        StepperStep("Dialogue", StepState.SKIPPED),
        StepperStep("Words", StepState.DONE),
        StepperStep("Phones", StepState.CURRENT),
        StepperStep("Save", StepState.NEXT),
      ),
      description = "Step 5 of 6, Phones.",
      teaching = "Step 5 of 6 · phrase 3 of 10. The phone recognizer listens to each word.",
    )
    Stepper(
      steps = listOf(
        StepperStep("Download", StepState.DONE),
        StepperStep("Audio", StepState.FAILED),
        StepperStep("Dialogue", StepState.CANCELLED),
      ),
      description = "Failed at Audio.",
    )
  }
}
