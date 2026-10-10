package com.mapineda48.phonotrainer.core.ui.component

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant

/** Sample values for the previews of the shared components (made-up titles, no personal data). */
internal object PreviewData {
  private val job = JobId("20261004-133519-a77c")

  val clip = ClipSummary(
    id = job,
    title = "A walk in the park",
    durationS = 64.0,
    words = 180,
    changeCount = 41,
    source = ClipSource(SourceKind.LINK, Site.YOUTUBE, null, null),
    availability = ClipAvailability.OnThisPhone,
    video = VideoAvailability.OnThisPhone,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 3,
    shortsTotal = 9,
    addedAt = Instant.EPOCH,
  )

  val request = Request(
    id = RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11"),
    kind = RequestKind.LINK,
    state = RequestState.WAITING,
    title = "A walk in the park",
    site = Site.YOUTUBE,
    link = null,
    fileName = null,
    fileBytes = null,
    createdAt = Instant.EPOCH,
    progress = null,
    lastError = null,
    jobId = null,
    actions = setOf(RequestAction.SEND_NOW, RequestAction.REMOVE),
  )

  val occurrence = Occurrence(
    word = WordRef(job, segment = 2, word = 4),
    shortFirstSegment = 2,
    wordText = "water",
    dictIpa = "ˈwɔtɚ",
    realizedIpa = "ˈwɔɾɚ",
    startMs = 12_400,
    endMs = 12_800,
    change = ChangeLabel("flapping"),
    diffCost = 0.2,
    advice = Advice.PRODUCE,
    family = Family.TD,
    crossesBoundary = false,
    nextWordText = null,
    clipTitle = "A walk in the park",
  )
}
