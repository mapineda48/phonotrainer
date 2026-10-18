package com.mapineda48.phonotrainer.feature.home

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import com.mapineda48.phonotrainer.core.ui.component.ConnectionCardExtras
import com.mapineda48.phonotrainer.core.ui.component.FamilyCount
import java.time.Instant
import java.time.ZoneId

/**
 * Made-up clips, sets and counts for the previews of every Home state (no personal data); the screen tests use them
 * too. The populated state shows every block of F-03 (F-25 list item 3).
 */
internal object HomeSamples {
  val now: Instant = Instant.parse("2026-10-04T10:30:00Z")
  val zone: ZoneId = ZoneId.of("UTC")

  fun clip(
    id: String,
    title: String,
    addedAt: Instant = now.minusSeconds(3_600),
    availability: ClipAvailability = ClipAvailability.OnThisPhone,
    video: VideoAvailability = VideoAvailability.OnThisPhone,
    shortsSeen: Int = 0,
    shortsTotal: Int = 15,
  ) = ClipSummary(
    id = JobId(id),
    title = title,
    durationS = 261.0,
    words = 691,
    changeCount = 14,
    source = ClipSource(SourceKind.LINK, Site.YOUTUBE, null, null),
    availability = availability,
    video = video,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = shortsSeen,
    shortsTotal = shortsTotal,
    addedAt = addedAt,
  )

  val coffee = clip("20261004-091519-a77c", "Coffee shop small talk", shortsSeen = 4)
  val radio = clip("20261003-091204-1b2c", "Morning radio interview", now.minusSeconds(90_000), video = VideoAvailability.AudioOnlyClip, shortsTotal = 31)
  val station = clip("20261002-180011-9e0f", "At the station", now.minusSeconds(170_000), ClipAvailability.Bringing(40), VideoAvailability.NotOnThisPhone, shortsTotal = 0)
  val lake = clip("20261001-120000-0a0b", "Two friends at the lake", now.minusSeconds(260_000), ClipAvailability.NotOnThisPhone, VideoAvailability.NotOnThisPhone)

  val shelf: List<ClipCardState> = listOf(
    ClipCardState(coffee, families = listOf(FamilyCount(Family.REDUCTION, 6), FamilyCount(Family.TD, 5), FamilyCount(Family.BOUNDARY, 3))),
    ClipCardState(radio, families = listOf(FamilyCount(Family.REDUCTION, 20), FamilyCount(Family.ASSIMILATION, 4))),
    ClipCardState(station),
    ClipCardState(lake),
  )

  /** F-03-AC10's set: 8 shorts (2 saved) whose spotlights are flapping, linking, h-dropping, flapping, t/d deletion,
   *  vowel reduction, linking, flapping, and 5 questions. */
  val summary = TodaySummary(shorts = 8, names = listOf("flapping", "linking", "h-dropping"), moreChanges = 2, saved = 2, questions = 5)

  private val stations: List<Station> = listOf(
    Station.Short(Family.TD, saved = true, done = false),
    Station.Short(Family.BOUNDARY, saved = false, done = false),
    Station.Short(Family.BOUNDARY, saved = true, done = false),
    Station.Short(Family.TD, saved = false, done = false),
    Station.Short(Family.TD, saved = false, done = false),
    Station.Short(Family.REDUCTION, saved = false, done = false),
    Station.Short(Family.BOUNDARY, saved = false, done = false),
    Station.Short(Family.TD, saved = false, done = false),
  ) + List(5) { Station.Question(answered = false) }

  val fresh = TodayCard.Ready(
    summary = summary,
    minutes = 5,
    stations = stations,
    phase = TodayPhase.FRESH,
    done = 0,
    met = listOf("flapping" to 3, "linking" to 2, "h-dropping" to 1, "t/d deletion" to 1, "vowel reduction" to 1),
  )

  val midSet = fresh.copy(
    phase = TodayPhase.IN_PROGRESS,
    done = 3,
    stations = stations.mapIndexed { index, station -> if (index < 3 && station is Station.Short) station.copy(done = true) else station },
  )

  val finished = fresh.copy(
    phase = TodayPhase.DONE,
    done = 8,
    stations = stations.map { station ->
      when (station) {
        is Station.Short -> station.copy(done = true)
        is Station.Question -> station.copy(answered = true)
      }
    },
  )

  val shortSet = fresh.copy(
    summary = TodaySummary(shorts = 4, names = listOf("flapping", "linking"), moreChanges = 0, saved = 0, questions = 2),
    minutes = 2,
    stations = stations.take(4) + List(2) { Station.Question(answered = false) },
  )

  val keepWatching = KeepWatchingCard(
    clip = ClipCardState(radio),
    short = ShortId(radio.id, 9),
    ordinal = 4,
    total = 31,
    changeName = "flapping",
    family = Family.TD,
    words = "water",
  )

  val changes: List<ChangeGroup> = listOf(
    ChangeGroup(
      Family.REDUCTION,
      listOf(
        ChangeChipState(ChangeLabel("vowel_reduction"), "Vowel reduction", Family.REDUCTION, 41),
        ChangeChipState(ChangeLabel("monophthongization"), "Monophthongization", Family.REDUCTION, 3),
        ChangeChipState(ChangeLabel("elision_syllable"), "Elided syllable", Family.REDUCTION, 0),
        ChangeChipState(ChangeLabel("function_elision"), "Function-word elision", Family.REDUCTION, 12),
      ),
    ),
    ChangeGroup(
      Family.TD,
      listOf(
        ChangeChipState(ChangeLabel("flapping"), "Flapping", Family.TD, 34),
        ChangeChipState(ChangeLabel("t_deletion"), "T/d deletion", Family.TD, 18),
        ChangeChipState(ChangeLabel("t_unreleased"), "Unreleased t/d", Family.TD, 7),
        ChangeChipState(ChangeLabel("glottalization"), "Glottalization", Family.TD, 0),
        ChangeChipState(ChangeLabel("nt_reduction"), "Nt → n", Family.TD, 2),
      ),
    ),
    ChangeGroup(
      Family.ASSIMILATION,
      listOf(
        ChangeChipState(ChangeLabel("th_stopping"), "Th-stopping", Family.ASSIMILATION, 0),
        ChangeChipState(ChangeLabel("palatalization"), "Palatalization", Family.ASSIMILATION, 5),
        ChangeChipState(ChangeLabel("place_assimilation"), "Place assimilation", Family.ASSIMILATION, 4),
      ),
    ),
    ChangeGroup(
      Family.BOUNDARY,
      listOf(
        ChangeChipState(ChangeLabel("linking"), "Linking", Family.BOUNDARY, 27),
        ChangeChipState(ChangeLabel("h_dropping"), "H-dropping", Family.BOUNDARY, 9),
      ),
    ),
    ChangeGroup(Family.LEXICAL, listOf(ChangeChipState(ChangeLabel("contraction_lex"), "Lexical contraction", Family.LEXICAL, 6))),
    ChangeGroup(Family.OTHER, listOf(ChangeChipState(ChangeLabel("word_elision"), "Elided word", Family.OTHER, 0))),
  )

  private val connected = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 2, lowOnSpace = false)

  /** Every block of F-03 (nine in v1-core): paired and connected, a set to start, clips on the shelf. */
  val populated = HomeUiState(
    status = connected,
    paired = true,
    sync = SyncState.Idle,
    counts = RequestCounts(waiting = 2, onComputer = 3, needsAttention = 0, readyNew = 0, analyzing = 1, inLine = 2),
    extras = ConnectionCardExtras(
      now = now,
      checkedAt = now.minusSeconds(10),
      analyzing = RequestProgress(Stage.PHONES, 40, null, null, null, null),
    ),
    catalogue = HomeCatalogue.Ready(total = 4, onPhone = 2),
    today = fresh,
    keepWatching = keepWatching,
    newOnPhone = listOf(NewClip(coffee, 12)),
    shelf = shelf,
    changes = changes,
    practice = PracticeCard(WeakSpot("t/d deletion", 4, 9), savedShorts = 12),
    now = now,
    zone = zone,
  )

  /** Never paired, nothing on the phone (F-03 States: first run). */
  val firstRun = HomeUiState(
    catalogue = HomeCatalogue.Ready(0, 0),
    today = TodayCard.NoClips,
    changes = changes.map { group -> group.copy(chips = group.chips.map { it.copy(shorts = 0) }) },
    now = now,
    zone = zone,
  )

  /** Paired, and the computer has no clips yet. */
  val empty = firstRun.copy(status = connected.copy(analyzing = 0, inLine = 0), paired = true, extras = ConnectionCardExtras(now = now, checkedAt = now))

  /** The first sync brings the clips (F-03 States: loading). */
  val loading = populated.copy(
    sync = SyncState.Active(bringing = 3, percent = 42, sending = 0, doneBytes = 34_000_000, totalBytes = 61_000_000),
    counts = RequestCounts(0, 0, 0, 0),
    extras = ConnectionCardExtras(now = now, clipsOnPhone = 1, clipsTotal = 4),
    today = TodayCard.Preparing,
    keepWatching = null,
    newOnPhone = emptyList(),
    practice = PracticeCard(),
  )

  /** Paired but out of reach: everything on the phone still works (F-03 States: offline / unreachable). */
  val unreachable = populated.copy(
    status = ComputerStatus.NotReachable("Desk PC", now.minusSeconds(10_800), addressMayHaveChanged = false, ProbeOutcome.NoAnswer(3)),
    counts = RequestCounts(waiting = 2, onComputer = 0, needsAttention = 0, readyNew = 0),
    extras = ConnectionCardExtras(now = now),
    newOnPhone = emptyList(),
  )

  /** The catalogue could not be read; the status card still works (F-03 States: error). */
  val error = populated.copy(catalogue = HomeCatalogue.Failed, shelf = emptyList(), newOnPhone = emptyList(), keepWatching = null)

  /** Storage nearly full and Android limiting the app in the background (F-03 States). */
  val limits = populated.copy(extras = populated.extras.copy(storageNearlyFull = true, backgroundRestricted = true))
}
