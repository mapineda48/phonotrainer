package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.CoachMarkId
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef

/** The player's state before the pool reports anything. */
internal val IdlePlayback = FeedPlaybackState(
  index = 0,
  playing = false,
  speed = 1f,
  track = Track.MIX,
  loop = LoopMode.OFF,
  ended = false,
  holdSlow = false,
  videoShown = false,
  firstFrameRendered = false,
  error = null,
)

/**
 * The Watch coach marks (spec F-01 item 4), one at a time in this order, each the first time; the ids are the
 * catalogue's (WP-13 owns the catalogue; "swipe" and "tap_word" are the names its store tests use).
 */
internal enum class WatchCoachMark(val id: CoachMarkId) {
  SWIPE(CoachMarkId("swipe")),
  TAP_WORD(CoachMarkId("tap_word")),
  HOLD_SLOW(CoachMarkId("hold_slow")),
  MARKS(CoachMarkId("marks")),
}

/** A move of the pager the screen performs once and reports done ([WatchViewModel.scrolled]). */
@Immutable
internal data class ScrollRequest(val page: Int, val id: Long)

/** The mode picker (design 9.12, spec F-06 item 3): its rows and counts. */
@Immutable
internal data class PickerState(
  /** Today's set: shorts done and its length; null before a set exists. */
  val today: Pair<Int, Int>? = null,
  val savedCount: Int = 0,
  val families: List<PickerFamily> = emptyList(),
  val clips: List<PickerClip> = emptyList(),
)

/** One family header of "One change" with its changes and their short counts (spec D-48). */
@Immutable
internal data class PickerFamily(val family: Family, val changes: List<PickerChange>)

@Immutable
internal data class PickerChange(val label: ChangeLabel, val name: String, val shorts: Int)

/** One row of "One clip, in order": "left at short 4" when the learner left it there. */
@Immutable
internal data class PickerClip(val jobId: JobId, val title: String, val leftAt: Int?)

/** Everything the Watch screen draws (architecture 3.1). */
@Immutable
internal data class WatchUiState(
  val loading: Boolean = true,
  val empty: FeedEmpty? = null,
  val mode: ModeName? = null,
  val pages: List<FeedPage> = emptyList(),
  /** The page the feed is on (the pager follows it). */
  val current: Int = 0,
  /** Changes whenever the pages are built anew: the pager then starts again on [current]. */
  val generation: Int = 0,
  val contents: Map<ShortId, ShortContent> = emptyMap(),
  /** Clips whose clip data could not be read ("This short couldn't be played."). */
  val failed: Set<JobId> = emptySet(),
  val saved: Set<ShortId> = emptySet(),
  /** "On this phone": a paired computer that is not reachable now. */
  val offline: Boolean = false,
  val playback: FeedPlaybackState = IdlePlayback,
  /** Changes at every jump of the playhead (a seek, a replay), so a paused card reads the new position. */
  val seekTick: Int = 0,
  val settings: UserSettings = UserSettings(),
  /** Listen first: the phrases of the current short already shown in this visit (F-07 item 5). */
  val revealed: Set<Int> = emptySet(),
  val coachMark: WatchCoachMark? = null,
  val summary: SetSummary? = null,
  val clipEnd: ClipEnd? = null,
  /** "Only 4 shorts today: add a clip for more." (F-08 Partial): the set's length when under 8. */
  val shortSet: Int? = null,
  /** "3 more clips on the way" (F-06 Empty), on the end cards. */
  val clipsOnTheWay: Int = 0,
  val shadowing: ShadowingState = ShadowingState.Idle,
  val scroll: ScrollRequest? = null,
  /** "Getting the sound ready…": a short is current and its autoplay has not started yet. */
  val awaitingSound: Boolean = false,
  /** v1-complete slots (work-plan 4.4): absent in v1-core, so their menu items are not shown. */
  val canRecord: Boolean = false,
  val canPhraseDetails: Boolean = false,
  val picker: PickerState = PickerState(),
) {
  val currentPage: FeedPage? get() = pages.getOrNull(current)

  val currentShort: FeedPage.Short? get() = currentPage as? FeedPage.Short

  /** The shorts of the feed, in order. */
  val shortPages: List<FeedPage.Short> get() = pages.filterIsInstance<FeedPage.Short>()

  /** Whether the current clip is left out of the Mix (the More sheet's switch reads it). */
  val currentExcluded: Boolean
    get() = currentShort?.id?.jobId?.let { it in settings.excludedFromMix } ?: false

  /** The header's position words (F-07 item 1, design 9.2). */
  val position: HeaderPosition
    get() {
      val page = currentPage ?: return HeaderPosition.None
      val shorts = shortPages
      val inSet = mode is ModeName.Mix
      return when (page) {
        is FeedPage.Short -> {
          val number = shorts.indexOfFirst { it.key == page.key } + 1
          if (inSet) {
            HeaderPosition.InSet(number, shorts.size, SetProgress(number - 1, number - 1, shorts.size, finished = false))
          } else {
            HeaderPosition.Ordinal(number, shorts.size)
          }
        }
        is FeedPage.QuestionPage -> HeaderPosition.Question(page.number, page.of)
        is FeedPage.End -> HeaderPosition.Done(
          if (inSet) SetProgress(shorts.size, shorts.size, shorts.size, finished = true) else null,
        )
      }
    }
}

/** Where a word target leaves the feed for (spec 5.4; [WatchViewModel.startExit]). */
internal sealed interface WatchExit {
  /** The lesson sheet on the word, over its short. */
  data class Lesson(val word: WordRef) : WatchExit

  /** The clip is listed but has no shorts on this phone: its Clip page. */
  data class ClipPage(val jobId: JobId) : WatchExit

  /** The clip is gone: the Library, with the notice already posted. */
  data object Library : WatchExit
}
