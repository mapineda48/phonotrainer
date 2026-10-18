package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.texture.Seeds
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTraceStyle
import com.mapineda48.phonotrainer.core.designsystem.trace.TraceSample
import com.mapineda48.phonotrainer.core.designsystem.trace.drawTrace
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlin.math.exp
import kotlin.math.sin

/**
 * The generated audio-only visual, "the drum" (spec F-06 item 7, design 7.3, architecture 11.6): the clip's paper and
 * four drum rules, a spectrogram-like halftone whose bands sway with the clip's hash and whose envelope follows the
 * current phrase's words, the current phrase's pitch line in semitones around the clip's median (played part an ink
 * ribbon, the rest dotted, the nib and the needle at the playhead), and the word strip with the word being said in
 * bold. Seeded by FNV-1a 32 of the job id, so each audio clip looks different and the same every time; only playback
 * moves it (reduced motion changes nothing else). Its words are in the chips and captions, so it is decorative here.
 */
@Composable
internal fun AudioOnlyVisual(jobId: JobId, content: ShortContent?, playhead: State<Long>, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val seed = remember(jobId) { Seeds.fnv1a32(jobId.value) }
  val measurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.bodySmall
  val phrase by remember(content) { derivedStateOf { content?.phraseAt(playhead.value) } }
  Canvas(modifier.clearAndSetSemantics { }) {
    drawRect(colors.drumPaper(seed))
    val strip = 44.dp.toPx()
    val area = Size(size.width, (size.height - strip).coerceAtLeast(0f))
    drawRules(colors, area)
    val current = phrase
    val pitch = current?.let { p -> content?.pitch?.firstOrNull { it.segment == p.segment } }
    val position = playhead.value
    if (current != null) drawHalftone(colors, seed, area, current)
    if (current != null) {
      val span = (current.endMs - current.startMs).coerceAtLeast(1L).toFloat()
      val progress = ((position - current.startMs) / span).coerceIn(0f, 1f)
      val top = area.height * PITCH_TOP
      val bottom = area.height * (1f - PITCH_BOTTOM)
      val samples = if (pitch != null && pitch.measurable) {
        drumSamples(pitch, current.startMs, current.endMs)
      } else {
        listOf(TraceSample(0f, 0.5f), TraceSample(1f, 0.5f))
      }
      inset(left = 0f, top = top, right = 0f, bottom = size.height - bottom) {
        drawPitch(samples, if (pitch?.measurable == true) progress else 0f, colors)
      }
      val x = progress * area.width
      drawLine(colors.ink, Offset(x, area.height * NEEDLE_FROM), Offset(x, area.height), 2.dp.toPx())
    }
    drawRect(colors.sheet, topLeft = Offset(0f, area.height), size = Size(size.width, strip))
    current?.let { drawWordStrip(measurer, labelStyle, colors, it, position, area.height, strip) }
  }
}

private const val PITCH_TOP = 0.18f
private const val PITCH_BOTTOM = 0.26f
private const val NEEDLE_FROM = 0.70f
private const val RULE_ALPHA = 0.13f
private const val GAP_MS = 60L

private fun DrawScope.drawRules(colors: PhonoColors, area: Size) {
  for (i in 1..4) {
    val y = area.height * i / 5f
    drawLine(colors.ink.copy(alpha = RULE_ALPHA), Offset(0f, y), Offset(area.width, y), 1.dp.toPx())
  }
}

/**
 * A halftone of three formant-like bands: their centres and sway come from [seed]; the envelope is the phrase's word
 * timings (a column under a word is dense, a pause between words is sparse), so it changes per phrase and is the same
 * every time for the same phrase. Cells of 8 dp; never under the word strip.
 */
private fun DrawScope.drawHalftone(colors: PhonoColors, seed: Int, area: Size, phrase: CaptionPhrase) {
  val cell = 8.dp.toPx()
  val cols = (area.width / cell).toInt().coerceAtLeast(1)
  val rows = (area.height / cell).toInt().coerceAtLeast(1)
  val span = (phrase.endMs - phrase.startMs).coerceAtLeast(1L).toFloat()
  val centres = FloatArray(3) { i -> 0.25f + 0.22f * i + ((seed ushr (i * 5)) and 7) / 70f }
  val sway = FloatArray(3) { i -> 1.5f + ((seed ushr (i * 7 + 3)) and 15) / 6f }
  for (c in 0 until cols) {
    val t = phrase.startMs + (c + 0.5f) / cols * span
    val gate = if (phrase.words.any { t >= it.startMs && t <= it.endMs }) 1f else 0.3f
    val x = c / cols.toFloat()
    for (r in 0 until rows) {
      val y = r / rows.toFloat()
      var energy = 0f
      for (i in 0 until 3) {
        val centre = centres[i] + 0.05f * sin(x * sway[i] * 6.28f + i)
        val d = (y - centre) / 0.09f
        energy += exp(-d * d)
      }
      val radius = cell * 0.42f * gate * energy.coerceAtMost(1f)
      if (radius > 0.6f) drawCircle(colors.halftone, radius, Offset((c + 0.5f) * cell, (r + 0.5f) * cell))
    }
  }
}

/** The phrase's pitch as trace samples over its own time span: semitones (±12 st) mapped top to bottom; gaps break. */
internal fun drumSamples(pitch: PhrasePitch, startMs: Long, endMs: Long): List<TraceSample> {
  val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
  val out = ArrayList<TraceSample>(pitch.timesMs.size + 4)
  for (i in pitch.timesMs.indices) {
    val t = pitch.timesMs[i]
    if (i > 0 && t - pitch.timesMs[i - 1] > GAP_MS) out += TraceSample(((t - 1 - startMs) / span).coerceIn(0f, 1f), null)
    val y = 0.5f - pitch.semitones[i].coerceIn(-12f, 12f) / 24f
    out += TraceSample(((t - startMs) / span).coerceIn(0f, 1f), y, weight = 0.5f)
  }
  return out
}

private fun DrawScope.drawPitch(samples: List<TraceSample>, progress: Float, colors: PhonoColors) {
  drawTrace(
    samples = samples,
    progress = progress,
    ticks = emptyList(),
    pin = null,
    pinNow = false,
    style = StylusTraceStyle.Drum,
    ink = colors.ink,
    muted = colors.inkMuted,
    pinFill = colors.sheet,
    showNib = true,
  )
}

/**
 * The word strip (design 7.3 item 5): each word centred under its time span; the word being said bold ink with a 2 dp
 * underline; when labels collide the current word stays and the labels farthest from it go first.
 */
private fun DrawScope.drawWordStrip(
  measurer: TextMeasurer,
  style: TextStyle,
  colors: PhonoColors,
  phrase: CaptionPhrase,
  position: Long,
  top: Float,
  height: Float,
) {
  val span = (phrase.endMs - phrase.startMs).coerceAtLeast(1L).toFloat()
  val playing = FeedLogic.playingWord(phrase, position)
  val gap = 6.dp.toPx()
  data class Label(val index: Int, val centre: Float, val width: Float, val layout: TextLayoutResult)
  val labels = phrase.words.mapIndexed { i, word ->
    val bold = i == playing
    val layout = measurer.measure(
      word.text,
      style.copy(
        color = if (bold) colors.ink else colors.ink2,
        fontWeight = if (bold) FontWeight.Bold else style.fontWeight,
        textDecoration = if (bold) TextDecoration.Underline else null,
      ),
    )
    val centre = ((word.startMs + word.endMs) / 2f - phrase.startMs) / span * size.width
    Label(i, centre, layout.size.width.toFloat(), layout)
  }
  // Keep the current word, then the nearest ones, while they fit without overlapping.
  val anchor = playing ?: (labels.size / 2)
  val order = labels.sortedBy { kotlin.math.abs(it.index - anchor) }
  val kept = ArrayList<Label>()
  for (label in order) {
    val left = (label.centre - label.width / 2).coerceIn(0f, (size.width - label.width).coerceAtLeast(0f))
    val right = left + label.width
    val clash = kept.any { k ->
      val kl = (k.centre - k.width / 2).coerceIn(0f, (size.width - k.width).coerceAtLeast(0f))
      left < kl + k.width + gap && kl < right + gap
    }
    if (!clash) kept += label
  }
  for (label in kept) {
    val left = (label.centre - label.width / 2).coerceIn(0f, (size.width - label.width).coerceAtLeast(0f))
    drawText(label.layout, topLeft = Offset(left, top + (height - label.layout.size.height) / 2f))
  }
}
