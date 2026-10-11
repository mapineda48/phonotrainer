package com.mapineda48.phonotrainer.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability

/**
 * The tone of a status line. Each tone has its own icon, and the words are always ink: `good` and `critical` colour
 * only the icon (design 2.1), so colour is never the only cue and never text.
 */
enum class StatusTone { NEUTRAL, ACTIVE, GOOD, CAUTION, CRITICAL }

/** The icon a [tone] shows when the caller names none. */
@DrawableRes
fun StatusTone.defaultIcon(): Int = when (this) {
  StatusTone.NEUTRAL -> PhonoIcons.Info
  StatusTone.ACTIVE -> PhonoIcons.StatusAnalyzing
  StatusTone.GOOD -> PhonoIcons.StatusReady
  StatusTone.CAUTION -> PhonoIcons.Caution
  StatusTone.CRITICAL -> PhonoIcons.StatusAttention
}

/**
 * Icon + words for a request, connection or availability state (design 9.17 to 9.19). [icon] overrides the tone's
 * icon; the loader of an ACTIVE line turns while motion is allowed (the only icon that moves, design 6). The icon is
 * decorative: the words carry the state.
 */
@Composable
fun StatusLine(
  text: String,
  modifier: Modifier = Modifier,
  tone: StatusTone = StatusTone.NEUTRAL,
  @DrawableRes icon: Int? = null,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
  val colors = PhonoTheme.colors
  val tint = when (tone) {
    StatusTone.GOOD -> colors.good
    StatusTone.CRITICAL -> colors.critical
    else -> colors.ink
  }
  val drawable = icon ?: tone.defaultIcon()
  val spins = drawable == PhonoIcons.StatusAnalyzing && !PhonoTheme.motion.reduced
  Row(
    modifier = modifier.semantics(mergeDescendants = true) { },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    val angle = if (spins) {
      val turn by rememberInfiniteTransition(label = "status").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
      )
      turn
    } else {
      0f
    }
    Icon(painterResource(drawable), null, tint = tint, modifier = Modifier.size(PhonoSizes.iconButton).rotate(angle))
    PhonoText(text, style = style, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
  }
}

/** A clip's availability on this phone, in the words of spec 7.11 with its icon (design 9.18). */
@Composable
fun AvailabilityChip(availability: ClipAvailability, modifier: Modifier = Modifier) {
  val text = when (availability) {
    ClipAvailability.OnThisPhone -> stringResource(R.string.ui_availability_on_phone)
    is ClipAvailability.Bringing -> stringResource(R.string.ui_availability_bringing, availability.percent)
    ClipAvailability.NotOnThisPhone -> stringResource(R.string.ui_availability_not_on_phone)
    ClipAvailability.GettingReadyOnComputer -> stringResource(R.string.ui_availability_getting_ready)
    is ClipAvailability.DownloadPaused -> stringResource(
      when (availability.reason) {
        PauseReason.BUDGET_REACHED -> R.string.ui_availability_paused_budget
        PauseReason.FREE_SPACE_FLOOR -> R.string.ui_availability_paused_space
        PauseReason.NOT_CONNECTED -> R.string.ui_availability_paused_offline
        PauseReason.NOT_WANTED -> R.string.ui_availability_paused
      },
    )
    ClipAvailability.NeedsDownloadingAgain -> stringResource(R.string.ui_availability_again)
  }
  val icon = when (availability) {
    ClipAvailability.OnThisPhone -> PhonoIcons.AvailOnPhone
    is ClipAvailability.Bringing -> PhonoIcons.StatusDownloading
    ClipAvailability.NotOnThisPhone -> PhonoIcons.AvailOnComputer
    ClipAvailability.GettingReadyOnComputer -> PhonoIcons.AvailGettingReady
    is ClipAvailability.DownloadPaused -> PhonoIcons.StatusWaiting
    ClipAvailability.NeedsDownloadingAgain -> PhonoIcons.StatusDownloading
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    StatusLine(text = text, icon = icon, style = MaterialTheme.typography.bodySmall)
    if (availability is ClipAvailability.Bringing) {
      PhonoProgressBar(availability.percent / 100f, modifier = Modifier.padding(start = 28.dp).width(160.dp))
    }
  }
}

/** The video's availability (design 9.18): film, monitor-cog or headphones with the words; ink2 when it is missing. */
@Composable
fun VideoAvailabilityChip(video: VideoAvailability, modifier: Modifier = Modifier) {
  val (text, icon) = when (video) {
    VideoAvailability.OnThisPhone -> stringResource(R.string.ui_video_on_phone) to PhonoIcons.AvailVideo
    is VideoAvailability.Downloading -> stringResource(R.string.ui_video_downloading, video.percent) to PhonoIcons.AvailVideo
    VideoAvailability.NotOnThisPhone -> stringResource(R.string.ui_video_not_on_phone) to PhonoIcons.AvailVideo
    VideoAvailability.GettingReadyOnComputer ->
      stringResource(R.string.ui_video_getting_ready) to PhonoIcons.AvailGettingReady
    VideoAvailability.NotAvailableFromComputer -> stringResource(R.string.ui_video_not_available) to PhonoIcons.AvailVideo
    VideoAvailability.CannotPlayOnThisPhone -> stringResource(R.string.ui_video_cannot_play) to PhonoIcons.AvailVideo
    VideoAvailability.AudioOnlyClip -> stringResource(R.string.ui_video_audio_only) to PhonoIcons.AudioOnly
  }
  val muted = video is VideoAvailability.NotOnThisPhone || video is VideoAvailability.NotAvailableFromComputer ||
    video is VideoAvailability.CannotPlayOnThisPhone
  val colors = PhonoTheme.colors
  Column(modifier, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    Row(
      modifier = Modifier.semantics(mergeDescendants = true) { },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Icon(painterResource(icon), null, tint = if (muted) colors.ink2 else colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
      PhonoText(text, style = MaterialTheme.typography.bodySmall, color = if (muted) colors.ink2 else colors.ink)
    }
    if (video is VideoAvailability.Downloading) {
      PhonoProgressBar(video.percent / 100f, modifier = Modifier.padding(start = 28.dp).width(160.dp))
    }
  }
}

@PreviewLightDark
@Composable
internal fun StatusPreview() {
  PhonoPreviewSurface {
    StatusLine("2 waiting on this phone", icon = PhonoIcons.StatusWaiting)
    StatusLine("1 analyzing · 40 %", tone = StatusTone.ACTIVE)
    StatusLine("Ready to watch", tone = StatusTone.GOOD)
    StatusLine("Storage almost full", tone = StatusTone.CAUTION)
    StatusLine("1 needs your attention", tone = StatusTone.CRITICAL)
    AvailabilityChip(ClipAvailability.OnThisPhone)
    AvailabilityChip(ClipAvailability.Bringing(42))
    AvailabilityChip(ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED))
    VideoAvailabilityChip(VideoAvailability.NotOnThisPhone)
    VideoAvailabilityChip(VideoAvailability.AudioOnlyClip)
  }
}
