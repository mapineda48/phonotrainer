package com.mapineda48.phonotrainer.core.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.cropMarks
import com.mapineda48.phonotrainer.core.designsystem.texture.HalftoneArea
import com.mapineda48.phonotrainer.core.designsystem.texture.Seeds
import com.mapineda48.phonotrainer.core.designsystem.texture.halftone
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTrace
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTraceStyle
import com.mapineda48.phonotrainer.core.designsystem.trace.TraceSample
import com.mapineda48.phonotrainer.core.designsystem.trace.drawTrace
import com.mapineda48.phonotrainer.core.designsystem.trace.sampleContour
import com.mapineda48.phonotrainer.core.domain.model.JobId
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "0:45": minutes and seconds of [ms] (tabular in the UI). */
fun formatClock(ms: Long): String {
  val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
  return "%d:%02d".format(total / 60L, total % 60L)
}

/**
 * A short's progress (design 9.7) over the stylus trace: the pitch contour of the short ([samples]; flat when there is
 * none), played as a solid ribbon up to [progress] and dotted after it, phrase boundaries as [ticks], the spotlight's
 * [pin] (filled while [pinNow]) and the nib; then the elapsed time when [positionMs] is known. It is a slider for
 * TalkBack: "Position 0:45 of 0:58", with "Back 2 s" and "Forward 2 s". [onSeek] (0 to 1) makes it draggable and
 * tappable; releasing near a phrase start snaps to it. Dragging never marks the short as seen (the caller's rule).
 */
@Composable
fun ShortProgress(
  progress: Float,
  modifier: Modifier = Modifier,
  samples: List<TraceSample> = emptyList(),
  ticks: List<Float> = emptyList(),
  pin: Float? = null,
  pinNow: Boolean = false,
  positionMs: Long? = null,
  durationMs: Long? = null,
  onSeek: ((Float) -> Unit)? = null,
) {
  val colors = PhonoTheme.colors
  val label = stringResource(R.string.ui_short_progress)
  val state = if (positionMs != null && durationMs != null) {
    stringResource(R.string.ui_short_position, formatClock(positionMs), formatClock(durationMs))
  } else {
    null
  }
  val back = stringResource(R.string.ui_short_back)
  val forward = stringResource(R.string.ui_short_forward)
  var drag by remember { mutableStateOf<Float?>(null) }
  val shown = (drag ?: progress).coerceIn(0f, 1f)
  val step = if (durationMs != null && durationMs > 0) STEP_MS.toFloat() / durationMs else DEFAULT_STEP
  Row(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = PhonoSizes.minTarget)
      .semantics(mergeDescendants = true) {
        contentDescription = label
        state?.let { stateDescription = it }
        progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
        if (onSeek != null) {
          setProgress { target -> onSeek(target.coerceIn(0f, 1f)); true }
          customActions = listOf(
            CustomAccessibilityAction(back) { onSeek((progress - step).coerceIn(0f, 1f)); true },
            CustomAccessibilityAction(forward) { onSeek((progress + step).coerceIn(0f, 1f)); true },
          )
        }
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    val gestures = if (onSeek == null) {
      Modifier
    } else {
      Modifier
        .pointerInput(onSeek) { detectTapGestures { onSeek(snap((it.x / size.width).coerceIn(0f, 1f), ticks)) } }
        .pointerInput(onSeek, ticks) {
          detectHorizontalDragGestures(
            onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
            onDragEnd = {
              drag?.let { onSeek(snap(it, ticks)) }
              drag = null
            },
            onDragCancel = { drag = null },
            onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) },
          )
        }
    }
    StylusTrace(
      samples = samples,
      progress = shown,
      ticks = ticks,
      pin = pin,
      pinNow = pinNow,
      style = StylusTraceStyle.Progress,
      modifier = Modifier.weight(1f).height(TRACE_HEIGHT).then(gestures),
    )
    if (positionMs != null) {
      PhonoText(
        formatClock(positionMs),
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = colors.ink2,
        modifier = Modifier.clearAndSetSemantics { },
      )
    }
  }
}

private val TRACE_HEIGHT = 36.dp
private const val STEP_MS = 2_000L
private const val DEFAULT_STEP = 0.05f
private const val SNAP = 0.02f

/** [x] moved onto the nearest phrase start within 2 % of the width, else [x] itself. */
internal fun snap(x: Float, ticks: List<Float>): Float =
  ticks.minByOrNull { abs(it - x) }?.takeIf { abs(it - x) <= SNAP } ?: x

/**
 * A clip's picture (design 7.4, architecture 11.6), the one composable that shows it everywhere (Home shelf and Keep
 * watching, Library rows, the Clip page hero, the short's media area): the [poster] when the phone has one; else, for
 * a video clip, the poster fallback (the clip's paper, its seeded halftone, crop marks, the [title] on an opaque paper
 * plate, the [chip] on an overlay plate); else, for an audio clip, the audio art (drum rules and a finished trace
 * ending in a blot). Everything is seeded by the job id, so a clip looks the same every time. Decorative unless
 * [contentDescription] is given: the row next to it says the title.
 */
@Composable
fun PosterArt(
  poster: File?,
  jobId: JobId,
  audioOnly: Boolean,
  modifier: Modifier = Modifier,
  title: String? = null,
  chip: String? = null,
  contentDescription: String? = null,
) {
  val seed = remember(jobId) { Seeds.fnv1a32(jobId.value) }
  val bitmap by produceState<ImageBitmap?>(null, poster?.path, poster?.lastModified()) {
    value = poster?.let { file -> withContext(Dispatchers.IO) { decodePoster(file) } }
  }
  val semantics = if (contentDescription == null) {
    Modifier.clearAndSetSemantics { }
  } else {
    Modifier.semantics { this.contentDescription = contentDescription }
  }
  Box(modifier.clip(RoundedCornerShape(PhonoRadius.small)).then(semantics)) {
    val image = bitmap
    when {
      image != null -> Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      audioOnly -> AudioArt(seed, Modifier.fillMaxSize())
      else -> PosterFallback(seed, title, Modifier.fillMaxSize())
    }
    chip?.let {
      PhonoText(
        it,
        style = MaterialTheme.typography.labelMedium,
        color = PhonoTheme.colors.ink,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(PhonoSpacing.s6)
          .clip(CircleShape)
          .background(PhonoTheme.colors.overlayPlate)
          .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s2),
      )
    }
  }
}

@Composable
private fun PosterFallback(seed: Int, title: String?, modifier: Modifier) {
  val colors = PhonoTheme.colors
  Box(
    modifier
      .background(colors.posterPaper(seed))
      .halftone(seed, colors.halftoneStrong, cell = 7.dp, area = HalftoneArea.CORNER_TOP_END),
  ) {
    Box(Modifier.fillMaxSize().padding(14.dp).cropMarks(colors.ink))
    title?.let {
      PhonoText(
        it,
        style = PhonoTheme.type.posterTitle,
        color = colors.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(PhonoSpacing.s8)
          .clip(RoundedCornerShape(PhonoRadius.extraSmall))
          .background(colors.sheet)
          .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s4),
      )
    }
  }
}

@Composable
private fun AudioArt(seed: Int, modifier: Modifier) {
  val colors = PhonoTheme.colors
  val contour = remember(seed) { sampleContour(points = 40, seed = Math.floorMod(seed, 97)).map { it.copy(y = it.y ?: 0.5f) } }
  Canvas(modifier.background(colors.drumPaper(seed))) {
    val rules = 4
    for (i in 1..rules) {
      val y = size.height * i / (rules + 1)
      drawLine(colors.ink.copy(alpha = DRUM_RULE_ALPHA), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    val inset = size.width * 0.08f
    val left = inset
    val area = size.width - 2 * inset
    inset(left = left, top = 0f, right = inset, bottom = 0f) {
      drawTrace(contour, 1f, emptyList(), null, false, StylusTraceStyle.Progress, colors.ink, colors.inkMuted, colors.sheet, false)
    }
    // The blot where the trace ends.
    val end = contour.last()
    val pad = StylusTraceStyle.Progress.padding.toPx()
    drawCircle(colors.ink, 5.dp.toPx(), Offset(left + area + 4.dp.toPx(), pad + (end.y ?: 0.5f) * (size.height - 2 * pad)))
  }
}

private const val DRUM_RULE_ALPHA = 0.13f

/** Decodes [file] downsampled to at most [maxPx] on its longer side; null when it is not a picture. */
internal fun decodePoster(file: File, maxPx: Int = 1080): ImageBitmap? {
  if (!file.isFile) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.path, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

@PreviewLightDark
@Composable
private fun MediaPreview() {
  PhonoPreviewSurface {
    ShortProgress(
      progress = 0.62f,
      samples = sampleContour(),
      ticks = listOf(0.3f, 0.7f),
      pin = 0.4f,
      pinNow = true,
      positionMs = 45_000,
      durationMs = 58_000,
      onSeek = {},
    )
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      PosterArt(
        poster = null,
        jobId = JobId("20261004-133519-a77c"),
        audioOnly = false,
        title = "A walk in the park",
        chip = "4:21",
        modifier = Modifier.width(176.dp).aspectRatio(16f / 10f),
      )
      PosterArt(
        poster = null,
        jobId = JobId("20261005-101010-b001"),
        audioOnly = true,
        chip = "Audio only",
        modifier = Modifier.width(176.dp).aspectRatio(16f / 10f),
      )
    }
  }
}
