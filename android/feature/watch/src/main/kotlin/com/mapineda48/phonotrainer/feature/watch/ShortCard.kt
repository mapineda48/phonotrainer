package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.ui.CoachMark
import com.mapineda48.phonotrainer.core.ui.CoachMarkHost
import com.mapineda48.phonotrainer.core.designsystem.component.CoachPointer
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.a11y.shortSemantics

/** What a short card can ask for; every gesture has one of these as its button or accessibility action. */
@Immutable
internal data class CardCallbacks(
  val onTogglePlay: () -> Unit = {},
  val onReplay: () -> Unit = {},
  val onHold: (Boolean) -> Unit = {},
  val onSpeed: () -> Unit = {},
  val onSlower: () -> Unit = {},
  val onLoop: () -> Unit = {},
  val onShadow: () -> Unit = {},
  val onSave: () -> Unit = {},
  val onMore: () -> Unit = {},
  val onWord: (WordRef) -> Unit = {},
  val onLoopPhrase: (Int) -> Unit = {},
  val onSeek: (Float) -> Unit = {},
  val onPlayWord: () -> Unit = {},
  val onSlowly: () -> Unit = {},
  val onOpenChange: () -> Unit = {},
  val onOpenClip: () -> Unit = {},
  val onShowWords: () -> Unit = {},
  val onShowCaptions: () -> Unit = {},
  val onExplain: (String) -> Unit = {},
  val onDismissCoach: (WatchCoachMark) -> Unit = {},
  val onNext: () -> Unit = {},
  val onPrevious: () -> Unit = {},
)

/**
 * One short (spec F-06 and F-07, design 9.8): reading order header (the screen's) → media → spotlight slip →
 * captions (+ the hint line) → rail → progress → clip line. A portrait video fills the card with the caption slip and
 * the rail capsule over it (design 9.5). At font scale 1.3 and above the rail becomes the two-row action bar and the
 * slip compacts (design 9.6). For TalkBack the card is one node with its summary and the custom actions of F-07-AC11.
 */
@Composable
internal fun ShortCard(
  page: FeedPage.Short,
  state: WatchUiState,
  current: Boolean,
  positionMs: () -> Long,
  volumeMuted: Boolean,
  surface: @Composable (Modifier, ContentScale) -> Unit,
  callbacks: CardCallbacks,
  modifier: Modifier = Modifier,
) {
  val content = state.contents[page.id]
  val playback = if (current) state.playback else IdleFor(page)
  val playhead: State<Long> = if (current) {
    rememberPlayhead(positionMs, playback.playing, key = state.seekTick to playback)
  } else {
    remember(page.key) { mutableLongStateOf(page.info.playStartMs) }
  }
  val failed = page.id.jobId in state.failed || (current && playback.error is AppError.Media.AudioMissing)
  val decodeFailed = current && playback.error is AppError.Media.VideoDecode
  val look = mediaLook(page.media, decodeFailed)
  val largeText = LocalDensity.current.fontScale >= LARGE_TEXT
  val saved = page.id in state.saved
  val actions = cardActions(
    speed = playback.speed,
    loop = playback.loop,
    saved = saved,
    onReplay = callbacks.onReplay,
    onSpeed = callbacks.onSpeed,
    onLoop = callbacks.onLoop,
    onShadow = callbacks.onShadow,
    onSave = callbacks.onSave,
    onMore = callbacks.onMore,
  )
  val summary = cardSummary(page, content)
  val a11yActions = listOf(
    CustomAccessibilityAction(stringResource(if (playback.playing) R.string.watch_action_pause else R.string.watch_action_play)) {
      callbacks.onTogglePlay(); true
    },
    CustomAccessibilityAction(stringResource(R.string.watch_next_short)) { callbacks.onNext(); true },
    CustomAccessibilityAction(stringResource(R.string.watch_previous_short)) { callbacks.onPrevious(); true },
    CustomAccessibilityAction(stringResource(R.string.watch_action_replay)) { callbacks.onReplay(); true },
    CustomAccessibilityAction(stringResource(R.string.watch_action_slower)) { callbacks.onSlower(); true },
    CustomAccessibilityAction(stringResource(R.string.watch_action_lesson)) {
      (content?.spotlight?.word ?: content?.phrases?.firstOrNull()?.words?.firstOrNull()?.ref)?.let(callbacks.onWord); true
    },
    CustomAccessibilityAction(stringResource(if (saved) R.string.watch_action_unsave else R.string.watch_action_save)) {
      callbacks.onSave(); true
    },
  )
  val cardModifier = modifier.fillMaxSize().shortSemantics(summary, a11yActions)
  if (look == MediaLook.PORTRAIT_VIDEO && !failed) {
    PortraitCard(page, state, current, content, playhead, playback, look, largeText, actions, volumeMuted, surface, callbacks, cardModifier)
  } else {
    StandardCard(page, state, current, content, playhead, playback, look, failed, largeText, actions, volumeMuted, surface, callbacks, cardModifier)
  }
}

@Composable
private fun StandardCard(
  page: FeedPage.Short,
  state: WatchUiState,
  current: Boolean,
  content: ShortContent?,
  playhead: State<Long>,
  playback: FeedPlaybackState,
  look: MediaLook,
  failed: Boolean,
  largeText: Boolean,
  actions: List<CardAction>,
  volumeMuted: Boolean,
  surface: @Composable (Modifier, ContentScale) -> Unit,
  callbacks: CardCallbacks,
  modifier: Modifier,
) {
  Column(modifier) {
    if (failed) {
      Box(Modifier.fillMaxWidth().aspectRatio(MEDIA_RATIO).background(PhonoTheme.colors.page), contentAlignment = Alignment.Center) {
        Notice(
          body = stringResource(R.string.watch_short_failed),
          kind = NoticeKind.CAUTION,
          live = true,
          modifier = Modifier.padding(PhonoSpacing.feedGutter).testTag(WatchTags.ERROR),
        )
      }
    } else {
      MediaArea(
        page = page,
        look = look,
        content = content,
        playhead = playhead,
        current = current,
        playback = playback,
        awaitingSound = state.awaitingSound,
        surface = surface,
        onTogglePlay = callbacks.onTogglePlay,
        onReplay = callbacks.onReplay,
        onHold = callbacks.onHold,
        modifier = Modifier.fillMaxWidth().aspectRatio(MEDIA_RATIO),
      )
    }
    Row(Modifier.weight(1f).fillMaxWidth()) {
      Column(
        Modifier.weight(1f).padding(start = PhonoSpacing.feedGutter, end = PhonoSpacing.s4, top = PhonoSpacing.s10),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      ) {
        TextBlock(page, state, current, content, playhead, largeText, volumeMuted, callbacks, Modifier.weight(1f))
      }
      if (!largeText) ActionRail(actions, Modifier.padding(top = PhonoSpacing.s8, end = PhonoSpacing.s4))
    }
    Column(
      Modifier.padding(horizontal = PhonoSpacing.feedGutter, vertical = PhonoSpacing.s4),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      ProgressRow(content, playhead, playback.playing, callbacks.onTogglePlay, callbacks.onSeek)
      if (largeText) ActionBar(actions)
      ClipLine(page, content?.clipTitle, playback.trackFallback.takeIf { current }, callbacks.onOpenClip)
    }
  }
}

@Composable
private fun PortraitCard(
  page: FeedPage.Short,
  state: WatchUiState,
  current: Boolean,
  content: ShortContent?,
  playhead: State<Long>,
  playback: FeedPlaybackState,
  look: MediaLook,
  largeText: Boolean,
  actions: List<CardAction>,
  volumeMuted: Boolean,
  surface: @Composable (Modifier, ContentScale) -> Unit,
  callbacks: CardCallbacks,
  modifier: Modifier,
) {
  val colors = PhonoTheme.colors
  Column(modifier) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      MediaArea(
        page = page,
        look = look,
        content = content,
        playhead = playhead,
        current = current,
        playback = playback,
        awaitingSound = state.awaitingSound,
        surface = surface,
        onTogglePlay = callbacks.onTogglePlay,
        onReplay = callbacks.onReplay,
        onHold = callbacks.onHold,
        modifier = Modifier.fillMaxSize(),
      )
      // The caption slip: an opaque sheet at the bottom left, so captions stay legible over any picture.
      Column(
        Modifier
          .align(Alignment.BottomStart)
          .padding(start = PhonoSpacing.s8, bottom = PhonoSpacing.s8, end = if (largeText) PhonoSpacing.s8 else 84.dp)
          .clip(RoundedCornerShape(topEnd = PhonoRadius.large))
          .background(colors.sheet)
          .padding(PhonoSpacing.s10)
          .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
      ) {
        TextBlock(page, state, current, content, playhead, largeText = true, volumeMuted, callbacks, Modifier)
      }
      if (!largeText) {
        ActionRail(
          actions,
          Modifier
            .align(Alignment.CenterEnd)
            .padding(end = PhonoSpacing.s4)
            .width(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(colors.sheet)
            .padding(vertical = PhonoSpacing.s6),
        )
      }
    }
    Column(
      Modifier.padding(horizontal = PhonoSpacing.feedGutter, vertical = PhonoSpacing.s4),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      ProgressRow(content, playhead, playback.playing, callbacks.onTogglePlay, callbacks.onSeek)
      if (largeText) ActionBar(actions)
      ClipLine(page, content?.clipTitle, playback.trackFallback.takeIf { current }, callbacks.onOpenClip)
    }
  }
}

/** The slip, the captions, the hint line and the volume notice: the card's words. */
@Composable
private fun TextBlock(
  page: FeedPage.Short,
  state: WatchUiState,
  current: Boolean,
  content: ShortContent?,
  playhead: State<Long>,
  largeText: Boolean,
  volumeMuted: Boolean,
  callbacks: CardCallbacks,
  captionsModifier: Modifier,
) {
  LoadingGate(loading = content == null, label = stringResource(R.string.watch_loading)) {
    val c = content ?: return@LoadingGate
    val slip = c.spotlight
    if (slip != null) {
      SpotlightSlipCard(
        slip = slip,
        playhead = playhead,
        savedTag = page.savedTag,
        compact = largeText,
        onOpenLesson = { callbacks.onWord(slip.word) },
        onPlayWord = callbacks.onPlayWord,
        onSlowly = callbacks.onSlowly,
        onOpenChange = callbacks.onOpenChange,
      )
    } else {
      NoSpotlightLine()
    }
    val longPhrase = c.phrases.any { it.words.size > LONG_PHRASE_WORDS }
    Captions(
      content = c,
      playhead = playhead,
      mode = state.settings.captions,
      listenFirst = state.settings.listenFirst,
      revealed = if (current) state.revealed else emptySet(),
      compact = largeText || state.settings.captions == CaptionMode.IPA || longPhrase,
      onWord = callbacks.onWord,
      onLoopPhrase = callbacks.onLoopPhrase,
      onShowWords = callbacks.onShowWords,
      onShowCaptions = callbacks.onShowCaptions,
      onExplain = callbacks.onExplain,
      modifier = captionsModifier,
    )
    if (current) {
      state.coachMark?.let { mark ->
        CoachMarkHost(
          mark = coachMarkOf(mark),
          onDismiss = { callbacks.onDismissCoach(mark) },
          pointer = CoachPointer.TOP,
          modifier = Modifier.testTag(WatchTags.COACH),
        ) { }
      }
      if (volumeMuted) {
        Notice(
          body = stringResource(R.string.watch_volume_zero),
          kind = NoticeKind.TIP,
          modifier = Modifier.testTag(WatchTags.VOLUME),
        )
      }
    }
  }
}

@Composable
private fun coachMarkOf(mark: WatchCoachMark): CoachMark = when (mark) {
  WatchCoachMark.SWIPE -> CoachMark(stringResource(R.string.watch_coach_swipe_title), stringResource(R.string.watch_coach_swipe_body), PhonoIcons.ChevronUp)
  WatchCoachMark.TAP_WORD -> CoachMark(stringResource(R.string.watch_coach_tap_word_title), stringResource(R.string.watch_coach_tap_word_body), PhonoIcons.Hold)
  WatchCoachMark.HOLD_SLOW -> CoachMark(stringResource(R.string.watch_coach_hold_title), stringResource(R.string.watch_coach_hold_body), PhonoIcons.Slow)
  WatchCoachMark.MARKS -> CoachMark(stringResource(R.string.watch_coach_marks_title), stringResource(R.string.watch_coach_marks_body), PhonoIcons.Legend)
}

/** The card's TalkBack summary (F-07-AC11): "Short 4 of 15, {clip}. Listen for flapping in “water”. 14 seconds." */
@Composable
internal fun cardSummary(page: FeedPage.Short, content: ShortContent?): String {
  val title = content?.clipTitle ?: stringResource(R.string.watch_clip_untitled)
  val seconds = ((page.info.playEndMs - page.info.playStartMs + 500) / 1000).toInt()
  val duration = pluralStringResource(R.plurals.watch_seconds, seconds, seconds)
  val slip = content?.spotlight
  return if (slip != null) {
    val word = if (slip.nextWordText != null) "${slip.wordText} ${slip.nextWordText}" else slip.wordText
    stringResource(R.string.watch_summary_spotlight, page.info.ordinal, page.info.ofTotal, title, slip.changeName, word, duration)
  } else {
    stringResource(R.string.watch_summary_plain, page.info.ordinal, page.info.ofTotal, title, duration)
  }
}

/** The state a card that is not current draws: paused at its start. */
private fun IdleFor(page: FeedPage.Short): FeedPlaybackState = IdlePlayback.copy(index = page.queueIndex)

/** From this font scale the rail becomes the action bar and the slip compacts (design 9.6). */
internal const val LARGE_TEXT = 1.3f

/** A phrase this long drops the dimmed phrases first (design 9.4 overflow). */
private const val LONG_PHRASE_WORDS = 14

/** The media footprint of a landscape video and of the drum (design 7.3, 9.5). */
private const val MEDIA_RATIO = 16f / 9f
