package com.mapineda48.phonotrainer.feature.home

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.remote.Capabilities
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import com.mapineda48.phonotrainer.core.ui.component.ConnectionCardExtras
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The blocks of Home in reading order (spec F-03 items 2 to 10, design 9.16); the masthead is always first. */
internal enum class HomeBlock {
  /** Your computer status card (F-03 item 2), always present. */
  STATUS,

  /** First run: the teaching block of the web's empty library, adapted (F-03 States). */
  TEACHING,

  /** Paired, and the computer has no clips yet (F-03 States). */
  EMPTY,

  /** Today's set (item 3): the first study item (F-03-AC1). */
  TODAY,

  /** Keep watching (item 4), when the last feed position is not today's set. */
  KEEP_WATCHING,

  /** Add a clip (item 5). */
  ADD,

  /** New on this phone (item 6). */
  NEW,

  /** The catalogue could not be read (F-03 States: Error); in place of the shelf. */
  ERROR,

  /** Your clips (item 7). */
  SHELF,

  /** Watch by change (item 8). */
  CHANGES,

  /** Practice (item 9). */
  PRACTICE,

  /** The Insights teaser (item 10, v1-complete; only when Insights is bound). */
  INSIGHTS,
}

/** The catalogue as Home knows it. [onPhone]: clips whose study pack is on this phone ("On this phone"). */
internal sealed interface HomeCatalogue {
  data object Loading : HomeCatalogue

  data object Failed : HomeCatalogue

  data class Ready(val total: Int, val onPhone: Int) : HomeCatalogue
}

/**
 * Why Home asks the repository for today's set (F-08 item 3): the local day it asks about and the clips on this phone
 * then. A new local day, or another clip on the phone while no set of the day exists, is a new reason to ask.
 */
internal data class TodayAsk(val date: LocalDate, val onPhone: Int)

/** One mark of the Today's set card's stations thread (design 9.16 block 3). */
@Immutable
internal sealed interface Station {
  /** A short: its spotlight change's family (null when it has none), saved, already watched. */
  data class Short(val family: Family?, val saved: Boolean, val done: Boolean) : Station

  /** A question slug. */
  data class Question(val answered: Boolean) : Station
}

/**
 * What a set holds, for the card's sentence (F-03-AC10): [shorts] (not dropped), the distinct spotlight changes in
 * set order ([names], at most three) and how many more there are ([moreChanges]), the saved shorts back and the
 * questions.
 */
@Immutable
internal data class TodaySummary(
  val shorts: Int,
  val names: List<String>,
  val moreChanges: Int,
  val saved: Int,
  val questions: Int,
)

/** Where today's set stands. */
internal enum class TodayPhase { FRESH, IN_PROGRESS, DONE }

/** The Today's set card (F-03 item 3, F-08). */
@Immutable
internal sealed interface TodayCard {
  /** No clip on this phone yet: "Today's set starts with your first clip." */
  data object NoClips : TodayCard

  /** The set is being made: "Preparing today's set…" (after 300 ms). */
  data object Preparing : TodayCard

  /** Clips are on the phone but no set can be made: every short was seen lately (F-06's "Mix with nothing left"). */
  data object NothingNew : TodayCard

  data class Ready(
    val summary: TodaySummary,
    val minutes: Int,
    val stations: List<Station>,
    val phase: TodayPhase,
    /** Shorts already watched in this set ("Today · 3 of 8 done"). */
    val done: Int,
    /** "What you met": each spotlight change of the set with how many shorts had it, most first. */
    val met: List<Pair<String, Int>>,
  ) : TodayCard
}

/** The Keep watching card (F-03 item 4): the clip, "Short 4 of 15" and the spotlight. */
@Immutable
internal data class KeepWatchingCard(
  val clip: ClipCardState,
  val short: ShortId,
  val ordinal: Int?,
  val total: Int?,
  val changeName: String?,
  val family: Family?,
  /** The spotlight's words, "water" or "pick it" when it crosses into the next word. */
  val words: String?,
)

/** A clip of "New on this phone" (F-03 item 6). */
@Immutable
internal data class NewClip(val clip: ClipSummary, val shorts: Int)

/** One chip of Watch by change (F-03 item 8): the change and its short count (D-48, `ChangeCount.shorts`). */
@Immutable
internal data class ChangeChipState(val label: ChangeLabel, val name: String, val family: Family, val shorts: Int)

/** The chips of one family, in the reference's order. */
@Immutable
internal data class ChangeGroup(val family: Family, val chips: List<ChangeChipState>)

/** "Worth reviewing: t/d deletion · 4 of 9". */
@Immutable
internal data class WeakSpot(val name: String, val correct: Int, val seen: Int)

/** The Practice card (F-03 item 9). */
@Immutable
internal data class PracticeCard(val weakSpot: WeakSpot? = null, val savedShorts: Int = 0)

/** A short-lived line under the masthead: pull to refresh while the computer is out of reach (F-03). */
internal enum class HomeNotice { NOT_REACHABLE }

/** Everything Home shows (spec F-03, design 9.16). */
@Immutable
internal data class HomeUiState(
  val status: ComputerStatus = ComputerStatus.NotConnected,
  val paired: Boolean = false,
  val capabilities: Capabilities? = null,
  val sync: SyncState = SyncState.Idle,
  val counts: RequestCounts = RequestCounts(0, 0, 0, 0),
  /** The oldest request of this phone that needs attention: the card's inline action comes from it. */
  val attention: Request? = null,
  val extras: ConnectionCardExtras = ConnectionCardExtras(),
  val catalogue: HomeCatalogue = HomeCatalogue.Loading,
  val today: TodayCard = TodayCard.Preparing,
  val keepWatching: KeepWatchingCard? = null,
  val newOnPhone: List<NewClip> = emptyList(),
  val shelf: List<ClipCardState> = emptyList(),
  val changes: List<ChangeGroup> = emptyList(),
  val practice: PracticeCard = PracticeCard(),
  val insights: String? = null,
  val refreshing: Boolean = false,
  val notice: HomeNotice? = null,
  val now: Instant = Instant.EPOCH,
  val zone: ZoneId = ZoneId.of("UTC"),
) {
  /** Never paired and nothing on the phone: the first-run state. */
  val firstRun: Boolean
    get() = !paired && catalogue == HomeCatalogue.Ready(0, 0)

  /** Paired, and the catalogue holds no clip. */
  val empty: Boolean
    get() = paired && (catalogue as? HomeCatalogue.Ready)?.total == 0
}
