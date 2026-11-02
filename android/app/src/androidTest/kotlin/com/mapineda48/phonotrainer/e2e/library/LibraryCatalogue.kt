package com.mapineda48.phonotrainer.e2e.library

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.support.TestTime
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The catalogue of the Library's area tests, on the real stack: [SIZE] clips on this test's computer, "Walk and talk nn"
 * for the first [WALKS] and "Coffee chat nn" after, each added a minute after the one before (so "Newest first" lists
 * the last one first), brought to this phone through the real stack ([TestSeeder]: pairing, a sync pass, the
 * indexer), then every tenth removed from this phone, as the learner's "Remove from this phone" does. Searching
 * "walk" with "On this phone" selected leaves [SEARCHED_ON_PHONE] of them.
 */
class LibraryCatalogue private constructor(private val ids: List<JobId>) {
  /** The computer's id of clip [number] (1 to [SIZE]). */
  fun id(number: Int): JobId = ids[number - 1]

  /** The rows "walk" and "On this phone" leave, newest first. */
  val searchedOnPhone: List<JobId> = (WALKS downTo 1).filter(::onThisPhone).map(::id)

  companion object {
    const val SIZE = 50
    const val WALKS = 44
    const val SEARCH = "walk"
    const val SEARCHED_ON_PHONE = 40

    /** Between one clip and the next on the computer, so the order of "Newest first" is the clips' numbers. */
    private val ADDED_APART: Duration = Duration.ofMinutes(1)

    /** Fifty clips: a listing, a hundred verified downloads and fifty indexed clips. */
    private val SEED_TIMEOUT = 120.seconds

    fun title(number: Int): String = if (number <= WALKS) "Walk and talk %02d".format(number) else "Coffee chat %02d".format(number)

    fun onThisPhone(number: Int): Boolean = number % 10 != 0

    /** Seeds the catalogue on this test's computer and phone; [time] spaces the clips a minute apart. */
    suspend fun seed(seeder: TestSeeder, time: TestTime, clips: ClipRepository): LibraryCatalogue {
      val ids = (1..SIZE).map { number ->
        if (number > 1) time.advanceBy(ADDED_APART)
        seeder.addFinishedClips(title(number), video = false).single()
      }
      seeder.bringToPhone(ids, SEED_TIMEOUT)
      (1..SIZE).filterNot(::onThisPhone).forEach { clips.removeFromPhone(ids[it - 1]) }
      return LibraryCatalogue(ids)
    }
  }
}
