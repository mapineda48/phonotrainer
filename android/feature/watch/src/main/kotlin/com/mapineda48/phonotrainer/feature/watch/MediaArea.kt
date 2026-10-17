package com.mapineda48.phonotrainer.feature.watch

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.ui.PosterArt
import kotlinx.coroutines.delay

/**
 * The media area of a short (spec F-06 item 7, design 9.5): the video (landscape shown whole on the letterbox,
 * portrait filling the card) drawn by [surface] with the poster over it until the first frame, the generated drum for
 * an audio clip, the poster with its chip when the video is not on the phone, not available or cannot play. Tap plays
 * or pauses, double-tap replays the phrase, press and hold plays at 0.5× while held (F-07 gestures); every gesture
 * has a button or an accessibility action too.
 */
@Composable
internal fun MediaArea(
  page: FeedPage.Short,
  look: MediaLook,
  content: ShortContent?,
  playhead: State<Long>,
  current: Boolean,
  playback: FeedPlaybackState,
  awaitingSound: Boolean,
  surface: @Composable (Modifier, ContentScale) -> Unit,
  onTogglePlay: () -> Unit,
  onReplay: () -> Unit,
  onHold: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  summaryActions: List<CustomAccessibilityAction> = emptyList(),
) {
  val colors = PhonoTheme.colors
  val toggle by rememberUpdatedState(onTogglePlay)
  val replay by rememberUpdatedState(onReplay)
  val hold by rememberUpdatedState(onHold)
  val description = stringResource(R.string.watch_media_description)
  val playLabel = stringResource(if (current && playback.playing) R.string.watch_pause else R.string.watch_play)
  var held by remember { mutableStateOf(false) }
  Box(
    modifier = modifier
      .background(if (look == MediaLook.LANDSCAPE_VIDEO || look == MediaLook.PORTRAIT_VIDEO) colors.letterbox else colors.page)
      .pointerInput(Unit) {
        detectTapGestures(
          onTap = { toggle() },
          onDoubleTap = { replay() },
          onLongPress = {
            held = true
            hold(true)
          },
          onPress = {
            tryAwaitRelease()
            if (held) {
              held = false
              hold(false)
            }
          },
        )
      }
      .testTag(WatchTags.MEDIA)
      .semantics(mergeDescendants = true) {
        contentDescription = summary ?: description
        if (summaryActions.isNotEmpty()) customActions = summaryActions
        onClick(label = playLabel) { toggle(); true }
      },
  ) {
    val title = content?.clipTitle
    when (look) {
      MediaLook.AUDIO_ONLY -> {
        AudioOnlyVisual(page.id.jobId, content, playhead, Modifier.fillMaxSize().testTag(WatchTags.AUDIO_ONLY))
        val measurable = content?.phraseAt(playhead.value)?.let { p -> content.pitch.firstOrNull { it.segment == p.segment }?.measurable } ?: false
        OverlayChip(stringResource(R.string.watch_audio_only), PhonoIcons.AudioOnly, Modifier.align(Alignment.TopStart))
        OverlayChip(
          stringResource(if (measurable) R.string.watch_pitch_of_phrase else R.string.watch_no_pitch),
          null,
          Modifier.align(Alignment.TopEnd),
          italic = true,
        )
      }
      MediaLook.LANDSCAPE_VIDEO, MediaLook.PORTRAIT_VIDEO -> {
        val scale = if (look == MediaLook.PORTRAIT_VIDEO) ContentScale.Crop else ContentScale.Fit
        if (current) surface(Modifier.fillMaxSize(), scale)
        if (!(current && playback.firstFrameRendered)) {
          PosterArt(page.media.poster, page.id.jobId, audioOnly = false, modifier = Modifier.fillMaxSize(), title = title)
        }
      }
      MediaLook.VIDEO_NOT_ON_PHONE -> {
        PosterArt(page.media.poster, page.id.jobId, audioOnly = false, modifier = Modifier.fillMaxSize(), title = title)
        OverlayChip(stringResource(R.string.watch_video_not_on_phone), PhonoIcons.AvailVideo, Modifier.align(Alignment.BottomStart).testTag(WatchTags.MEDIA_CHIP))
      }
      MediaLook.VIDEO_NOT_AVAILABLE -> {
        PosterArt(null, page.id.jobId, audioOnly = false, modifier = Modifier.fillMaxSize(), title = title)
        OverlayChip(stringResource(R.string.watch_video_not_available), PhonoIcons.AvailOnComputer, Modifier.align(Alignment.BottomStart).testTag(WatchTags.MEDIA_CHIP))
      }
      MediaLook.CANNOT_PLAY -> {
        PosterArt(page.media.poster, page.id.jobId, audioOnly = false, modifier = Modifier.fillMaxSize(), title = title)
        OverlayChip(stringResource(R.string.watch_video_cannot_play), PhonoIcons.Caution, Modifier.align(Alignment.BottomStart).testTag(WatchTags.MEDIA_CHIP))
      }
    }
    if (current && playback.holdSlow) {
      OverlayChip(stringResource(R.string.watch_hold_slow), PhonoIcons.Slow, Modifier.align(Alignment.TopCenter).testTag(WatchTags.HOLD_CHIP))
    }
    if (current && awaitingSound) GettingReady()
  }
}

/** "Getting the sound ready…", a static chip shown only after 300 ms (design 9.5, spec loading rule). */
@Composable
private fun BoxScope.GettingReady() {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(LOADING_AFTER_MS)
    shown = true
  }
  if (shown) {
    OverlayChip(
      stringResource(R.string.watch_getting_ready),
      PhonoIcons.StatusPreparing,
      Modifier.align(Alignment.Center).testTag(WatchTags.GETTING_READY),
    )
  }
}

private const val LOADING_AFTER_MS = 300L

/** An overlay chip over the picture (design 9.5): sheet at 94 % (gate G9), 32 dp, labelMedium, icon + words. */
@Composable
internal fun OverlayChip(text: String, @DrawableRes icon: Int?, modifier: Modifier = Modifier, italic: Boolean = false) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .padding(PhonoSpacing.s8)
      .widthIn(max = 320.dp)
      .heightIn(min = 32.dp)
      .clip(CircleShape)
      .background(colors.overlayPlate)
      .padding(horizontal = PhonoSpacing.s10, vertical = PhonoSpacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    icon?.let { Icon(painterResource(it), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline)) }
    PhonoText(
      text,
      style = MaterialTheme.typography.labelMedium.let { if (italic) it.copy(fontStyle = FontStyle.Italic) else it },
      color = colors.ink,
    )
  }
}
