package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ArtifactProgress
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.component.ClipCardState
import java.time.Instant
import java.time.ZoneId

/**
 * Made-up clips, requests and shorts for the previews of every Library and Clip page state (no personal data). The
 * screen tests use them too.
 */
internal object LibrarySamples {
  val now: Instant = Instant.parse("2026-10-04T10:30:00Z")
  val zone: ZoneId = ZoneId.of("UTC")

  fun clip(
    id: String,
    title: String,
    addedAt: Instant = now.minusSeconds(3_600),
    availability: ClipAvailability = ClipAvailability.OnThisPhone,
    video: VideoAvailability = VideoAvailability.OnThisPhone,
    source: ClipSource = ClipSource(SourceKind.LINK, Site.YOUTUBE, "https://www.youtube.com/watch?v=example", null),
    kept: Boolean = false,
    origin: ClipOrigin = ClipOrigin.ACTIVE,
    olderRules: Boolean = false,
    durationS: Double? = 261.0,
    shortsSeen: Int = 12,
    shortsTotal: Int = 15,
  ) = ClipSummary(
    id = JobId(id),
    title = title,
    durationS = durationS,
    words = 691,
    changeCount = 14,
    source = source,
    availability = availability,
    video = video,
    kept = kept,
    origin = origin,
    olderRules = olderRules,
    shortsSeen = shortsSeen,
    shortsTotal = shortsTotal,
    addedAt = addedAt,
  )

  val coffee = clip("20261004-091519-a77c", "Coffee shop small talk", kept = true)
  val radio = clip(
    "20261003-091204-1b2c",
    "Morning radio interview",
    addedAt = now.minusSeconds(90_000),
    video = VideoAvailability.AudioOnlyClip,
    source = ClipSource(SourceKind.UPLOAD, null, null, "radio.m4a"),
    durationS = 614.0,
    shortsSeen = 0,
    shortsTotal = 31,
  )
  val station = clip(
    "20261002-180011-9e0f",
    "At the station",
    addedAt = now.minusSeconds(200_000),
    availability = ClipAvailability.Bringing(45),
    video = VideoAvailability.NotOnThisPhone,
    source = ClipSource(SourceKind.LINK, Site.X, "https://x.com/example/status/1", null),
    olderRules = true,
    shortsSeen = 0,
    shortsTotal = 0,
  )
  val lake = clip(
    "20260901-101010-0a0b",
    "Two friends at the lake",
    addedAt = now.minusSeconds(3_000_000),
    availability = ClipAvailability.NotOnThisPhone,
    video = VideoAvailability.NotOnThisPhone,
    origin = ClipOrigin.FORGOTTEN_COMPUTER,
    shortsSeen = 3,
  )
  val market = clip(
    "20260920-120000-cc01",
    "Ordering at the market",
    addedAt = now.minusSeconds(1_200_000),
    availability = ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED),
    video = VideoAvailability.NotOnThisPhone,
    origin = ClipOrigin.OTHER_LIBRARY,
  )

  val clips: List<ClipSummary> = listOf(coffee, radio, station, lake, market)

  fun request(
    id: String,
    state: RequestState,
    title: String,
    kind: RequestKind = RequestKind.LINK,
    actions: Set<RequestAction> = emptySet(),
    error: RequestError? = null,
    progress: RequestProgress? = null,
    createdAt: Instant = now.minusSeconds(600),
  ) = Request(
    id = RequestId(id),
    kind = kind,
    state = state,
    title = title,
    site = if (kind == RequestKind.LINK) Site.YOUTUBE else null,
    link = null,
    fileName = if (kind == RequestKind.FILE) "bus-stop-chat.mp4" else null,
    fileBytes = if (kind == RequestKind.FILE) 48_000_000 else null,
    createdAt = createdAt,
    progress = progress,
    lastError = error,
    jobId = null,
    actions = actions,
  )

  val failed = request(
    "5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11",
    RequestState.NEEDS_ATTENTION,
    "YouTube link",
    actions = setOf(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.REMOVE),
    error = RequestError("download_forbidden", null, null, atSending = false),
  )
  val waiting = request(
    "7d1e2f30-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
    RequestState.WAITING,
    "bus-stop-chat.mp4",
    kind = RequestKind.FILE,
    actions = setOf(RequestAction.REMOVE),
  )
  val analyzing = request(
    "9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a",
    RequestState.ANALYZING,
    "How to order coffee like a local",
    actions = setOf(RequestAction.CANCEL),
    progress = RequestProgress(Stage.PHONES, 61, null, null, null, null),
  )

  val ready = LibraryUiState(
    catalogue = Catalogue.Ready(clips.map { ClipCardState(it) }),
    clips = LibraryRules.arrange(clips, "", ClipFilter.ALL, ClipSort.NEWEST).map { ClipCardState(it) },
    total = clips.size,
    inProgress = listOf(failed, waiting, analyzing),
    startedOnComputer = 2,
    paired = true,
    now = now,
    zone = zone,
  )

  // --- The Clip page ---

  private fun short(ordinal: Int, segment: Int, startMs: Long, endMs: Long, spotlight: Spotlight?, marked: Int) =
    ShortInfo(ShortId(coffee.id, segment), ordinal, 6, segment + 1, startMs, endMs, spotlight, marked)

  private fun spot(segment: Int, word: Int, change: String, advice: Advice, crosses: Boolean = false) =
    Spotlight(WordRef(coffee.id, segment, word), ChangeLabel(change), advice, crosses)

  val shorts: List<ShortInfo> = listOf(
    short(1, 0, 0, 17_000, spot(0, 3, "linking", Advice.PRODUCE, crosses = true), 9),
    short(2, 2, 17_000, 36_000, spot(2, 1, "vowel_reduction", Advice.PRODUCE), 12),
    short(3, 4, 36_000, 42_000, spot(4, 2, "flapping", Advice.PRODUCE), 4),
    short(4, 6, 42_000, 58_000, spot(6, 0, "palatalization", Advice.UNDERSTAND, crosses = true), 11),
    short(5, 8, 58_000, 66_000, null, 0),
  )

  val shortRows: List<ShortRowState> = listOf(
    ShortRowState(shorts[0], "Linking", Family.BOUNDARY, "pick it", seen = true, next = false),
    ShortRowState(shorts[1], "Vowel reduction", Family.REDUCTION, "to", seen = true, next = false),
    ShortRowState(shorts[2], "Flapping", Family.TD, "better", seen = true, next = false),
    ShortRowState(shorts[3], "Palatalization", Family.ASSIMILATION, "did you", seen = false, next = true),
    ShortRowState(shorts[4], null, null, null, seen = false, next = false),
  )

  val changes: List<ChangeRowState> = listOf(
    ChangeRowState(ChangeLabel("flapping"), "Flapping", Family.TD, Advice.PRODUCE, 12),
    ChangeRowState(ChangeLabel("vowel_reduction"), "Vowel reduction", Family.REDUCTION, Advice.PRODUCE, 9),
    ChangeRowState(ChangeLabel("linking"), "Linking", Family.BOUNDARY, Advice.PRODUCE, 7),
    ChangeRowState(ChangeLabel("palatalization"), "Palatalization", Family.ASSIMILATION, Advice.UNDERSTAND, 3),
  )

  fun detail(summary: ClipSummary = coffee, artifacts: List<ArtifactProgress> = emptyList()) = ClipDetail(
    summary = summary,
    durationS = summary.durationS,
    segments = 87,
    changeCounts = changes.associate { it.label to it.count },
    rulesVersion = 3,
    phoneEngine = "timit61",
    dialogueSeparation = true,
    hasDialogueTrack = true,
    studyPackBytes = if (summary.availability == ClipAvailability.OnThisPhone) 2_100_000 else null,
    videoBytes = if (summary.video == VideoAvailability.OnThisPhone) 17_000_000 else null,
    lastWatched = null,
    resume = shorts[3].id,
    computerError = null,
    artifacts = artifacts,
  )

  val page = ClipUiState(
    presence = ClipPresence.Known(detail()),
    shorts = ShortsState.Ready(shortRows),
    changes = changes,
    resume = shorts[3],
    connected = true,
    now = now,
    zone = zone,
  )

  /** A clip whose study pack is coming: the audio at 40 %. */
  val bringing = page.copy(
    presence = ClipPresence.Known(
      detail(
        station.copy(availability = ClipAvailability.Bringing(30)),
        artifacts = listOf(
          ArtifactProgress(ArtifactRole.CLIP_DATA, ArtifactLocalState.READY, 100, null, 0),
          ArtifactProgress(ArtifactRole.AUDIO_MIX, ArtifactLocalState.DOWNLOADING, 40, null, 0),
        ),
      ),
    ),
    resume = null,
  )

  /** Not on this phone and the computer not reachable. */
  val notHereOffline = page.copy(
    presence = ClipPresence.Known(detail(lake)),
    shorts = ShortsState.NotHere,
    connected = false,
    resume = null,
  )

  /** Imported without its audio. */
  val noAudio = page.copy(
    presence = ClipPresence.Known(
      detail(
        coffee.copy(source = ClipSource(SourceKind.IMPORT, null, null, null), video = VideoAvailability.AudioOnlyClip),
        artifacts = listOf(ArtifactProgress(ArtifactRole.CLIP_DATA, ArtifactLocalState.READY, 100, null, 0)),
      ),
    ),
  )

  val processing = page.copy(
    presence = ClipPresence.Known(
      detail(coffee.copy(availability = ClipAvailability.GettingReadyOnComputer, video = VideoAvailability.GettingReadyOnComputer)),
    ),
    request = analyzing,
  )
}
