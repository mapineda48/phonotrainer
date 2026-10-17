package com.mapineda48.phonotrainer.e2e.library

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipDetail
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeClipRepository
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow

/**
 * The settable [FakeClipRepository] of work-plan 4.6 that also counts subscriptions to the whole catalogue. The
 * Library subscribes once per ViewModel while it is shown, so a count that does not move across a tab switch or a
 * recreate tells a kept ViewModel from a new one (the per-tab state proof of architecture 16.2).
 */
class CountingClipRepository(val fake: FakeClipRepository = FakeClipRepository()) : ClipRepository by fake {
  private val reads = AtomicInteger()

  /** How many times the catalogue was subscribed to. */
  val catalogueReads: Int get() = reads.get()

  override fun clips(query: ClipQuery): Flow<List<ClipSummary>> {
    reads.incrementAndGet()
    return fake.clips(query)
  }

  /** What the app asked the repository to do, oldest first ("removeFromPhone:<id>", …). */
  fun actions(): List<String> = synchronized(fake.actions) { fake.actions.toList() }
}

/**
 * The catalogue of the Library's area tests: [SIZE] clips, "Walk and talk nn" for the first [WALKS] and "Coffee chat
 * nn" after, each added a minute after the one before (so "Newest first" lists the last one first), on this phone
 * except every tenth. Searching "walk" with "On this phone" selected leaves [SEARCHED_ON_PHONE] of them.
 */
object LibraryCatalogue {
  const val SIZE = 50
  const val WALKS = 44
  const val SEARCH = "walk"
  const val SEARCHED_ON_PHONE = 40

  private val ADDED: Instant = Instant.parse("2026-09-01T09:00:00Z")

  fun id(number: Int): JobId = JobId("e2e-library-%02d".format(number))

  fun title(number: Int): String = if (number <= WALKS) "Walk and talk %02d".format(number) else "Coffee chat %02d".format(number)

  fun onThisPhone(number: Int): Boolean = number % 10 != 0

  fun summary(number: Int) = ClipSummary(
    id = id(number),
    title = title(number),
    durationS = 60.0 + number,
    words = 120 + number,
    changeCount = 10 + number % 7,
    source = ClipSource(SourceKind.LINK, Site.YOUTUBE, link = null, fileName = null),
    availability = if (onThisPhone(number)) ClipAvailability.OnThisPhone else ClipAvailability.NotOnThisPhone,
    video = if (onThisPhone(number)) VideoAvailability.OnThisPhone else VideoAvailability.NotOnThisPhone,
    kept = false,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = number % 5,
    shortsTotal = 12,
    addedAt = ADDED.plusSeconds(number * 60L),
  )

  fun detail(number: Int) = ClipDetail(
    summary = summary(number),
    durationS = 60.0 + number,
    segments = 9,
    changeCounts = mapOf(ChangeLabel("flapping") to 4, ChangeLabel("linking") to 2),
    rulesVersion = 3,
    phoneEngine = "timit61",
    dialogueSeparation = true,
    hasDialogueTrack = false,
    studyPackBytes = if (onThisPhone(number)) 2_100_000 else null,
    videoBytes = if (onThisPhone(number)) 17_000_000 else null,
    lastWatched = null,
    resume = null,
    computerError = null,
  )

  /** Fills [clips] with the whole catalogue and every clip's detail. */
  fun seed(clips: CountingClipRepository) {
    val numbers = 1..SIZE
    clips.fake.details.value = numbers.associate { id(it) to detail(it) }
    clips.fake.all.value = numbers.map(::summary)
  }

  /** The numbers the Library lists for [SEARCH] with "On this phone", newest first. */
  val searchedOnPhone: List<Int> = (WALKS downTo 1).filter(::onThisPhone)
}
