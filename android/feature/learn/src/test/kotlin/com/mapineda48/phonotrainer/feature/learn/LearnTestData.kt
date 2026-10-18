package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Clips, occurrences and a span player that plays until told, for the ViewModel and screen tests of Learn. */
internal object LearnTestData {
  val JOB = JobId("20261004-133519-a77c")
  val GONE_JOB = JobId("20261001-080000-dead")
  val FLAPPING = ChangeLabel("flapping")
  val VOWEL_REDUCTION = ChangeLabel("vowel_reduction")

  /** Flapping on the phone: [count] eligible occurrences, one per word, clearest first. */
  fun flapping(count: Int, job: JobId = JOB): List<Occurrence> = (0 until count).map { index ->
    occurrence(job, segment = index / 4, word = index % 4, text = "word$index", startMs = 1_000L * index)
  }

  fun occurrence(
    job: JobId = JOB,
    segment: Int = 0,
    word: Int = 4,
    text: String = "water",
    startMs: Long = 45_000,
    change: ChangeLabel = FLAPPING,
    family: Family = Family.TD,
    dict: String? = "ˈwɔtɚ",
    said: String? = "ˈwɔɾɚ",
  ) = Occurrence(
    word = WordRef(job, segment, word),
    shortFirstSegment = segment,
    wordText = text,
    dictIpa = dict,
    realizedIpa = said,
    startMs = startMs,
    endMs = startMs + 300,
    change = change,
    diffCost = 0.1,
    advice = Advice.PRODUCE,
    family = family,
    crossesBoundary = false,
    nextWordText = null,
    clipTitle = "Two friends at the lake",
  )

  fun summary(id: JobId, availability: ClipAvailability = ClipAvailability.OnThisPhone) = ClipSummary(
    id = id,
    title = "Two friends at the lake",
    durationS = 64.0,
    words = 180,
    changeCount = 41,
    source = ClipSource(SourceKind.UPLOAD, null, null, "lake.mp4"),
    availability = availability,
    video = VideoAvailability.OnThisPhone,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 0,
    shortsTotal = 9,
    addedAt = Instant.EPOCH,
  )

  fun detail(id: JobId, availability: ClipAvailability = ClipAvailability.NotOnThisPhone) = ClipDetail(
    summary = summary(id, availability),
    durationS = 64.0,
    segments = 20,
    changeCounts = emptyMap(),
    rulesVersion = 3,
    phoneEngine = "timit61",
    dialogueSeparation = false,
    hasDialogueTrack = false,
    studyPackBytes = null,
    videoBytes = null,
    lastWatched = null,
    resume = null,
    computerError = null,
  )
}

/**
 * A [SpanPlayer] whose [play] lasts until [finish] (or a stop), as the real one plays a span to its end: it records each
 * play and stop.
 */
internal class HoldingSpanPlayer : SpanPlayer {
  private val flow = MutableStateFlow(SpanPlaybackState(null, false, 0, 1f, Track.MIX, false, null))
  override val state: StateFlow<SpanPlaybackState> = flow
  val played = mutableListOf<ClipSpan>()
  val tracks = mutableListOf<Track>()
  var stops = 0
    private set
  private var done: CompletableDeferred<Unit>? = null

  override suspend fun play(span: ClipSpan, speed: Float, track: Track, loop: Boolean) {
    played += span
    tracks += track
    val running = CompletableDeferred<Unit>()
    done?.complete(Unit)
    done = running
    flow.value = SpanPlaybackState(span, true, span.startMs, speed, track, loop, null)
    try {
      running.await()
    } finally {
      flow.value = flow.value.copy(playing = false)
    }
  }

  override fun stop() {
    stops++
    done?.complete(Unit)
  }

  /** The span ends on its own. */
  fun finish() {
    done?.complete(Unit)
  }
}
