package com.mapineda48.phonotrainer.core.ui.component

import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant

/** Sample clips for the previews of `ClipCard` and `ClipRow` (made-up titles, no personal data). */
internal object ClipCardSamples {
  private val coffee = ClipSummary(
    id = JobId("20261004-133519-a77c"),
    title = "Coffee shop small talk",
    durationS = 261.0,
    words = 691,
    changeCount = 14,
    source = ClipSource(SourceKind.LINK, Site.YOUTUBE, null, null),
    availability = ClipAvailability.OnThisPhone,
    video = VideoAvailability.OnThisPhone,
    kept = true,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 12,
    shortsTotal = 15,
    addedAt = Instant.EPOCH,
  )

  private val radio = coffee.copy(
    id = JobId("20261003-091204-1b2c"),
    title = "Morning radio interview",
    durationS = 614.0,
    words = 1_489,
    changeCount = 48,
    source = ClipSource(SourceKind.UPLOAD, null, null, "radio.m4a"),
    video = VideoAvailability.AudioOnlyClip,
    kept = false,
    shortsSeen = 0,
    shortsTotal = 31,
  )

  private val station = coffee.copy(
    id = JobId("20261002-180011-9e0f"),
    title = "At the station",
    durationS = 95.0,
    words = 240,
    changeCount = 9,
    source = ClipSource(SourceKind.LINK, Site.X, null, null),
    availability = ClipAvailability.Bringing(45),
    video = VideoAvailability.NotOnThisPhone,
    kept = false,
    olderRules = true,
    shortsSeen = 0,
    shortsTotal = 0,
  )

  private val forgotten = coffee.copy(
    id = JobId("20260901-101010-0a0b"),
    title = "Two friends at the lake",
    source = ClipSource(SourceKind.PATH, null, null, null),
    availability = ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED),
    video = VideoAvailability.NotOnThisPhone,
    kept = false,
    origin = ClipOrigin.FORGOTTEN_COMPUTER,
    shortsSeen = 3,
  )

  val shelf = ClipCardState(
    coffee,
    families = listOf(
      FamilyCount(Family.REDUCTION, 21),
      FamilyCount(Family.TD, 14),
      FamilyCount(Family.ASSIMILATION, 3),
      FamilyCount(Family.BOUNDARY, 9),
    ),
  )

  val audioShelf = ClipCardState(
    radio,
    families = listOf(FamilyCount(Family.REDUCTION, 48), FamilyCount(Family.TD, 30), FamilyCount(Family.BOUNDARY, 22)),
  )

  val rows: List<ClipCardState> = listOf(coffee, radio, station, forgotten).map { ClipCardState(it) }
}
