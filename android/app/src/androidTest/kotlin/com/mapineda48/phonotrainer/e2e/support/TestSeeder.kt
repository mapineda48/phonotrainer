package com.mapineda48.phonotrainer.e2e.support

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PairResult
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PairingTarget
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Starts an end-to-end test from a library **seeded through the real stack** (architecture 21.5; work-plan 2.4): the
 * clips are created on this test's [FakeComputer], the phone pairs with it through the real `PairingRepository` (the
 * manual address, as a learner would type it), and a real sync pass brings them to the phone; nothing is written into
 * the phone's databases behind the app's back.
 *
 * It works once the cores it drives are merged (WP-05b network, WP-07 pairing, WP-08 sync, and WP-10 for clip
 * content); against the B1 stubs pairing answers "offline" and [pair] fails, saying so. Area tests use it only when
 * their baseline contains those cores (work-plan 2.4); [workDriver] runs whatever the stack scheduled.
 *
 * ```
 * @Inject lateinit var seeder: TestSeeder
 * …
 * val ids = runBlocking { seeder.seedLibrary("Ordering coffee", "At the station") }
 * ```
 */
class TestSeeder @Inject constructor(
  val computer: FakeComputer,
  val workDriver: WorkDriver,
  private val pairing: PairingRepository,
  private val status: ComputerStatusRepository,
  private val sync: SyncController,
  private val clips: ClipRepository,
) {
  /** Pairs this phone with the fake computer through the real stack; fails with the stack's answer otherwise. */
  suspend fun pair(deviceName: String = DEVICE_NAME): PairedComputer {
    val code = computer.newPairingCode()
    val target = PairingTarget(computer.address, computer.serverId, computer.name)
    val result = pairing.pair(target, code, deviceName)
    check(result is PairResult.Paired) {
      "Pairing with the fake computer through ${pairing.javaClass.simpleName} answered $result; TestSeeder needs the " +
        "real :core:connectivity and :core:network (WP-07, WP-05b) in this baseline"
    }
    return result.computer
  }

  /** Finished clips on the computer (one per title, video unless [video] is false); returns their ids. */
  fun addFinishedClips(vararg titles: String, video: Boolean = true): List<JobId> =
    titles.map { computer.addFinishedClip(it, video = video) }

  /**
   * Seeds [titles] as finished clips on the computer, pairs if this phone is not paired yet, runs a sync pass and
   * waits until every one is on this phone. Returns their ids, in the order of [titles].
   */
  suspend fun seedLibrary(vararg titles: String, video: Boolean = true, timeout: Duration = SYNC_TIMEOUT): List<JobId> {
    val ids = addFinishedClips(*titles, video = video)
    if (status.paired.value == null) pair()
    syncAndAwait(ids, timeout)
    return ids
  }

  /**
   * Runs a sync pass and waits until each of [ids] is on this phone, meanwhile running whatever work the stack
   * schedules (WorkManager runs synchronously in tests, but only once a request's constraints are marked met).
   */
  suspend fun syncAndAwait(ids: List<JobId>, timeout: Duration = SYNC_TIMEOUT): List<ClipSummary> {
    sync.syncNow(SyncReason.SYNC_NOW)
    return try {
      withTimeout(timeout) {
        var listed: List<ClipSummary>
        while (true) {
          workDriver.runEnqueued()
          listed = clips.clips(ClipQuery()).first()
          if (ids.all { id -> listed.any { it.id == id && it.availability == ClipAvailability.OnThisPhone } }) break
          delay(POLL)
        }
        listed
      }
    } catch (timedOut: TimeoutCancellationException) {
      throw AssertionError(
        "The clips ${ids.map { it.value }} did not reach this phone within $timeout through " +
          "${sync.javaClass.simpleName} and ${clips.javaClass.simpleName}; the sync state is ${sync.state.value}",
        timedOut,
      )
    }
  }

  private companion object {
    const val DEVICE_NAME = "Test phone"
    val SYNC_TIMEOUT = 30.seconds
    val POLL = 100.milliseconds
  }
}
