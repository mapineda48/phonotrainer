package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.ui.ExplainSheet
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import com.mapineda48.phonotrainer.core.ui.a11y.LocalReducedMotion
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ShadowingOptions
import kotlinx.coroutines.launch

/** Everything the Watch screen can ask for: the cards, the pages, the header and the sheets. */
@Immutable
internal data class WatchActions(
  val card: CardCallbacks = CardCallbacks(),
  val end: EndCallbacks = EndCallbacks(),
  val question: QuestionCallbacks = QuestionCallbacks(),
  val empty: EmptyCallbacks = EmptyCallbacks(),
  val onPageSettled: (Int) -> Unit = {},
  val onScrolled: (ScrollRequest) -> Unit = {},
  val onPick: (FeedStart) -> Unit = {},
  val onLearnChange: (ChangeLabel) -> Unit = {},
  val onPickerOpened: () -> Unit = {},
  val onTrack: (Track) -> Unit = {},
  val onCaptions: (CaptionMode) -> Unit = {},
  val onListenFirst: (Boolean) -> Unit = {},
  val onPhraseDetails: () -> Unit = {},
  val onOpenClipPage: () -> Unit = {},
  val onDownloadVideo: () -> Unit = {},
  val onExcluded: (Boolean) -> Unit = {},
  val onShadowStart: (ShadowingOptions) -> Unit = {},
  val onShadowStop: () -> Unit = {},
  val onLearnMore: (LinkTarget) -> Unit = {},
)

/** The sheets of Watch (architecture 16.1: local UI state of the screen, not destinations). */
internal enum class WatchSheet { NONE, PICKER, MORE, LEGEND, SHADOW, RECORD, EXPLAIN }

/**
 * The Watch destination (spec F-06 to F-08, design 9.2 to 9.15, 10.1 T2): the feed header over a vertical pager of
 * pages (shorts, the set's questions, one end card), full bleed under the status bar (decisions I-12). Swipe, the
 * header's buttons and the card's accessibility actions move one short; with reduced motion the move is instant
 * (F-06-AC9). The stateless overload: screen tests and previews call it with a [WatchUiState].
 */
@Composable
internal fun WatchScreen(
  state: WatchUiState,
  positionMs: () -> Long,
  volumeMuted: Boolean,
  actions: WatchActions,
  modifier: Modifier = Modifier,
  surface: @Composable (index: Int, Modifier, ContentScale) -> Unit = { _, _, _ -> },
  glossary: (String) -> GlossaryEntry? = { null },
  record: (@Composable (Modifier) -> Unit)? = null,
  initialSheet: WatchSheet = WatchSheet.NONE,
) {
  val colors = PhonoTheme.colors
  val title = stringResource(R.string.watch_title)
  var sheet by rememberSaveable { mutableStateOf(initialSheet) }
  var explainKey by rememberSaveable { mutableStateOf<String?>(null) }
  val reduced = LocalReducedMotion.current
  Column(
    modifier
      .fillMaxSize()
      .background(colors.page)
      .statusBarsPadding()
      .paneTitle(title)
      .testTag(WatchTags.SCREEN),
  ) {
    if (state.loading) {
      LoadingGate(loading = true, label = stringResource(R.string.watch_loading), modifier = Modifier.testTag(WatchTags.LOADING)) { }
      return@Column
    }
    val empty = state.empty
    if (empty != null) {
      FeedHeader(
        mode = state.mode,
        position = HeaderPosition.None,
        offline = state.offline,
        canPrevious = false,
        canNext = false,
        onPicker = {
          actions.onPickerOpened()
          sheet = WatchSheet.PICKER
        },
        onPrevious = {},
        onNext = {},
      )
      EmptyFeed(empty, actions.empty)
    } else {
      key(state.generation) {
        val pager = rememberPagerState(initialPage = state.current.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0))) { state.pages.size }
        val scope = rememberCoroutineScope()
        val move: (Int) -> Unit = { target ->
          if (target in state.pages.indices) {
            scope.launch { if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
          }
        }
        LaunchedEffect(pager) {
          snapshotFlow { pager.settledPage }.collect { actions.onPageSettled(it) }
        }
        LaunchedEffect(state.scroll) {
          val request = state.scroll ?: return@LaunchedEffect
          if (reduced) pager.scrollToPage(request.page) else pager.animateScrollToPage(request.page)
          actions.onScrolled(request)
        }
        FeedHeader(
          mode = state.mode,
          position = state.position,
          offline = state.offline,
          canPrevious = pager.currentPage > 0,
          canNext = pager.currentPage < state.pages.lastIndex,
          onPicker = {
            actions.onPickerOpened()
            sheet = WatchSheet.PICKER
          },
          onPrevious = { move(pager.currentPage - 1) },
          onNext = { move(pager.currentPage + 1) },
        )
        state.shortSet?.let { count ->
          if (state.currentPage is FeedPage.Short && state.current == 0) {
            PhonoText(
              pluralStringResource(R.plurals.watch_short_set, count, count),
              style = MaterialTheme.typography.bodySmall,
              color = colors.ink2,
              modifier = Modifier.padding(horizontal = PhonoSpacing.gutter).testTag(WatchTags.SHORT_SET),
            )
          }
        }
        val card = actions.card.copy(
          onNext = { move(pager.currentPage + 1) },
          onPrevious = { move(pager.currentPage - 1) },
          onMore = { sheet = WatchSheet.MORE },
          onShadow = { sheet = WatchSheet.SHADOW },
          onExplain = { key ->
            explainKey = key
            sheet = WatchSheet.EXPLAIN
          },
        )
        val end = actions.end.copy(onWatchAgain = { actions.end.onWatchAgain(); move(0) })
        val question = actions.question.copy(onNext = { move(pager.currentPage + 1) })
        VerticalPager(
          state = pager,
          key = { index -> state.pages.getOrNull(index)?.key ?: index },
          flingBehavior = PagerDefaults.flingBehavior(
            state = pager,
            snapAnimationSpec = if (reduced) snap() else tween(SWIPE_MS),
          ),
          modifier = Modifier.fillMaxWidth().weight(1f).testTag(WatchTags.PAGER),
        ) { index ->
          Box(Modifier.fillMaxSize().testTag(WatchTags.page(index))) {
            when (val page = state.pages[index]) {
              is FeedPage.Short -> ShortCard(
                page = page,
                state = state,
                current = index == state.current,
                positionMs = positionMs,
                volumeMuted = volumeMuted,
                surface = { m, scale -> surface(page.queueIndex, m, scale) },
                callbacks = card,
              )
              is FeedPage.QuestionPage -> QuestionFeedPage(page, savedWords = false, callbacks = question)
              is FeedPage.End -> EndCard(page.kind, state, end)
            }
          }
        }
      }
    }
  }
  val current = state.currentShort
  when (sheet) {
    WatchSheet.NONE -> Unit
    WatchSheet.PICKER -> ModePickerSheet(
      picker = state.picker,
      mode = state.mode,
      currentClip = current?.id?.jobId,
      onPick = {
        sheet = WatchSheet.NONE
        actions.onPick(it)
      },
      onLearn = {
        sheet = WatchSheet.NONE
        actions.onLearnChange(it)
      },
      onDismiss = { sheet = WatchSheet.NONE },
    )
    WatchSheet.MORE -> MoreSheet(
      track = state.playback.track,
      showTrack = current?.media?.audioDialogue != null || state.playback.trackFallback == TrackFallback.DIALOGUE_NOT_ON_PHONE ||
        state.playback.track == Track.DIALOGUE,
      captions = state.settings.captions,
      listenFirst = state.settings.listenFirst,
      canPhraseDetails = state.canPhraseDetails,
      canRecord = state.canRecord && record != null,
      canDownloadVideo = current?.media?.videoAvailability == VideoAvailability.NotOnThisPhone,
      excluded = state.currentExcluded,
      onTrack = actions.onTrack,
      onCaptions = actions.onCaptions,
      onListenFirst = actions.onListenFirst,
      onPhraseDetails = {
        sheet = WatchSheet.NONE
        actions.onPhraseDetails()
      },
      onRecord = { sheet = WatchSheet.RECORD },
      onOpenClip = {
        sheet = WatchSheet.NONE
        actions.onOpenClipPage()
      },
      onDownloadVideo = {
        sheet = WatchSheet.NONE
        actions.onDownloadVideo()
      },
      onExcluded = actions.onExcluded,
      onMarks = { sheet = WatchSheet.LEGEND },
      onDismiss = { sheet = WatchSheet.NONE },
    )
    WatchSheet.LEGEND -> LegendSheet(onDismiss = { sheet = WatchSheet.NONE })
    WatchSheet.SHADOW -> ShadowSheet(
      state = state.shadowing,
      onStart = actions.onShadowStart,
      onStop = actions.onShadowStop,
      onDismiss = {
        actions.onShadowStop()
        sheet = WatchSheet.NONE
      },
    )
    WatchSheet.RECORD -> PhonoSheet(
      onDismiss = { sheet = WatchSheet.NONE },
      title = stringResource(R.string.watch_record),
      modifier = Modifier.testTag(WatchTags.RECORD_SHEET),
    ) { record?.invoke(Modifier.fillMaxWidth()) }
    WatchSheet.EXPLAIN -> {
      val entry = explainKey?.let(glossary)
      if (entry == null) {
        sheet = WatchSheet.NONE
      } else {
        ExplainSheet(entry, onDismiss = { sheet = WatchSheet.NONE }) { target ->
          sheet = WatchSheet.NONE
          actions.onLearnMore(target)
        }
      }
    }
  }
}

/** The vertical pager's slide between shorts (design 8: 280 ms, standard easing). */
private const val SWIPE_MS = 280
