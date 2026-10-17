package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability

// Previews of every Watch state (definition of done 2.5 item 7; design 9.8's table). Device: the design's 412 × 915 dp.

private const val DEVICE = "spec:width=412dp,height=915dp"

@Composable
private fun Feed(state: WatchUiState, muted: Boolean = false, sheet: WatchSheet = WatchSheet.NONE, cvd: Boolean = false) {
  PhonoTheme(colors = if (cvd) ColorChoice.COLOR_VISION_FRIENDLY else ColorChoice.STANDARD) {
    WatchScreen(
      state = state,
      positionMs = { 44_900 },
      volumeMuted = muted,
      actions = WatchActions(),
      initialSheet = sheet,
    )
  }
}

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun LandscapeShortPreview() = Feed(WatchPreviewData.ready())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun AudioOnlyOfflinePreview() = Feed(
  WatchPreviewData.ready(WatchPreviewData.shortPage(jobId = WatchPreviewData.audioJob, video = VideoAvailability.AudioOnlyClip))
    .copy(offline = true),
  cvd = true,
)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun PortraitShortPreview() = Feed(
  WatchPreviewData.ready(WatchPreviewData.shortPage(width = 720, height = 1280)).copy(coachMark = WatchCoachMark.HOLD_SLOW),
)

@Preview(device = DEVICE, fontScale = 2f)
@Composable
private fun LargeTextPreview() = Feed(WatchPreviewData.ready())

@Preview(device = DEVICE)
@Composable
private fun ListenFirstPreview() = Feed(WatchPreviewData.ready().copy(settings = UserSettings(listenFirst = true)))

@Preview(device = DEVICE)
@Composable
private fun NoSpotlightPreview() = Feed(
  WatchPreviewData.ready(
    WatchPreviewData.shortPage(),
    WatchPreviewData.content(slip = null),
  ),
)

@Preview(device = DEVICE)
@Composable
private fun CrossingSpotlightPreview() = Feed(
  WatchPreviewData.ready(WatchPreviewData.shortPage(), WatchPreviewData.content(slip = WatchPreviewData.crossingSlip())),
)

@Preview(device = DEVICE)
@Composable
private fun IpaCaptionsPreview() = Feed(WatchPreviewData.ready().copy(settings = UserSettings(captions = CaptionMode.IPA)))

@Preview(device = DEVICE)
@Composable
private fun CaptionsOffPreview() = Feed(WatchPreviewData.ready().copy(settings = UserSettings(captions = CaptionMode.OFF)))

@Preview(device = DEVICE)
@Composable
private fun PlayingSlowLoopPreview() = Feed(
  WatchPreviewData.ready().copy(
    playback = IdlePlayback.copy(playing = true, speed = 0.5f, loop = LoopMode.PHRASE, holdSlow = true, firstFrameRendered = false),
    saved = setOf(WatchPreviewData.shortPage().id),
  ),
)

@Preview(device = DEVICE)
@Composable
private fun VideoNotOnPhonePreview() = Feed(
  WatchPreviewData.ready(WatchPreviewData.shortPage(video = VideoAvailability.NotOnThisPhone))
    .copy(playback = IdlePlayback.copy(trackFallback = TrackFallback.DIALOGUE_NOT_ON_PHONE)),
)

@Preview(device = DEVICE)
@Composable
private fun VideoNotAvailablePreview() = Feed(
  WatchPreviewData.ready(WatchPreviewData.shortPage(video = VideoAvailability.NotAvailableFromComputer)),
  muted = true,
)

@Preview(device = DEVICE)
@Composable
private fun DecodeFailurePreview() = Feed(WatchPreviewData.ready().copy(playback = IdlePlayback.copy(error = AppError.Media.VideoDecode)))

@Preview(device = DEVICE)
@Composable
private fun CorruptFilePreview() = Feed(WatchPreviewData.ready().copy(failed = setOf(WatchPreviewData.job)))

@Preview(device = DEVICE)
@Composable
private fun LoadingShortPreview() = Feed(WatchPreviewData.ready(content = null))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun SetShortPreview() = Feed(WatchPreviewData.set(current = 1))

@Preview(device = DEVICE)
@Composable
private fun SetQuestionPreview() = Feed(WatchPreviewData.set(current = 3))

@Preview(device = DEVICE)
@Composable
private fun SetFeedbackPreview() = Feed(WatchPreviewData.set(current = 4))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun SetSummaryPreview() = Feed(WatchPreviewData.set(current = 5).copy(clipsOnTheWay = 3))

@Preview(device = DEVICE)
@Composable
private fun ClipEndPreview() = Feed(WatchPreviewData.ready().copy(current = 1))

@Preview(device = DEVICE)
@Composable
private fun ChangeEndPreview() = Feed(
  WatchPreviewData.ready().copy(
    mode = ModeName.Change(WatchPreviewData.spotlight.change, "flapping", com.mapineda48.phonotrainer.core.domain.model.Family.TD),
    pages = listOf(WatchPreviewData.shortPage(), FeedPage.End(EndKind.CHANGE_END)),
    current = 1,
  ),
)

@Preview(device = DEVICE)
@Composable
private fun SavedEndPreview() = Feed(
  WatchPreviewData.ready().copy(mode = ModeName.Saved, pages = listOf(FeedPage.End(EndKind.SAVED_END)), current = 0),
)

@Preview(device = DEVICE)
@Composable
private fun NothingLeftPreview() = Feed(
  WatchUiState(loading = false, mode = ModeName.Mix(today = true), pages = listOf(FeedPage.End(EndKind.NOTHING_LEFT))),
)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun FirstRunPreview() = Feed(WatchUiState(loading = false, mode = ModeName.Mix(true), empty = FeedEmpty.FirstRun))

@Preview(device = DEVICE)
@Composable
private fun ClipsComingPreview() = Feed(WatchUiState(loading = false, mode = ModeName.Mix(true), empty = FeedEmpty.ClipsComing(42)))

@Preview(device = DEVICE)
@Composable
private fun EmptyChangePreview() = Feed(
  WatchUiState(loading = false, mode = ModeName.Saved, empty = FeedEmpty.NoShortsWithChange("nt → n")),
)

@Preview(device = DEVICE)
@Composable
private fun NothingSavedPreview() = Feed(WatchUiState(loading = false, mode = ModeName.Saved, empty = FeedEmpty.NothingSaved))

@Preview(device = DEVICE)
@Composable
private fun ClipNotHerePreview() = Feed(WatchUiState(loading = false, mode = ModeName.InOrder(null), empty = FeedEmpty.ClipNotHere))

@Preview(device = DEVICE)
@Composable
private fun LoadingFeedPreview() = Feed(WatchUiState())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun ModePickerPreview() = Feed(WatchPreviewData.ready().copy(picker = WatchPreviewData.picker), sheet = WatchSheet.PICKER)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun MoreSheetPreview() = Feed(WatchPreviewData.ready(), sheet = WatchSheet.MORE)

@Preview(device = DEVICE)
@Composable
private fun LegendSheetPreview() = Feed(WatchPreviewData.ready(), sheet = WatchSheet.LEGEND)

@Preview(device = DEVICE)
@Composable
private fun ShadowSheetPreview() = Feed(WatchPreviewData.ready(), sheet = WatchSheet.SHADOW)
