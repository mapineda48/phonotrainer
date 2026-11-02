package com.mapineda48.phonotrainer.e2e.support

import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
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
import com.mapineda48.phonotrainer.core.testing.computer.ClipContent
import com.mapineda48.phonotrainer.core.testing.computer.ClipContentProvider
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.FakeMediaBytes
import com.mapineda48.phonotrainer.core.testing.computer.JobSeed
import java.util.concurrent.ConcurrentHashMap
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
 * manual address, as a learner would type it), and a real sync pass brings them to the phone, where the real indexer
 * cuts them into shorts; nothing is written into the phone's databases or files behind the app's back.
 *
 * [seedLibrary] serves each clip's default content (the fake computer's `mk_analysis`); [seedAudioClip] serves a clip
 * data of the test's choosing (one of WP-12's fixtures) with audio as long as the clip, for tests that play it, and
 * [addDialogue] brings that clip's dialogue track later. Against stubs pairing answers "offline" and [pair] fails,
 * saying so; [workDriver] runs whatever the stack scheduled.
 *
 * ```
 * @Inject lateinit var seeder: TestSeeder
 * …
 * val ids = runBlocking { seeder.seedLibrary("Ordering coffee", "At the station") }
 * val lake = runBlocking { seeder.seedAudioClip("Two friends at the lake", FixtureClips.of(Variant.RULES_3)) }
 * ```
 */
class TestSeeder @Inject constructor(
  val computer: FakeComputer,
  val workDriver: WorkDriver,
  private val pairing: PairingRepository,
  private val status: ComputerStatusRepository,
  private val sync: SyncController,
  private val clips: ClipRepository,
  private val content: ClipContentRepository,
) {
  /** What each clip of [seedAudioClip] serves, so [addDialogue] can add its dialogue track to the same files. */
  private val served = ConcurrentHashMap<JobId, ClipContent>()

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
  suspend fun seedLibrary(vararg titles: String, video: Boolean = true, timeout: Duration = SYNC_TIMEOUT): List<JobId> =
    bringToPhone(addFinishedClips(*titles, video = video), timeout)

  /**
   * Seeds one finished audio-only clip titled [title] whose clip data is what [clipData] makes (WP-12's fixtures:
   * `FixtureClips.of(variant)`, `FixtureClips.synthetic(words, phrases, seed)`), and brings it to this phone as
   * [seedLibrary] does. Its mix is a silent WAV one second longer than the clip, so every short of it plays to its end
   * (the fake computer's own content stops at 30 s); it has no dialogue track until [addDialogue]. Returns its id.
   */
  suspend fun seedAudioClip(title: String, clipData: ClipContentProvider, timeout: Duration = SYNC_TIMEOUT): JobId {
    val made = clipData.contentFor(title, separateDialogue = false)
    val playable = ClipContent(
      analysisJson = made.analysisJson,
      audioMix = FakeMediaBytes.wav(made.durationS + AUDIO_TAIL_S),
      audioDialogue = null,
      durationS = made.durationS,
      segments = made.segments,
      words = made.words,
      changeCounts = made.changeCounts,
    )
    val id = computer.addJob(JobSeed(title = title, audioOnly = true, content = playable))
    served[id] = playable
    return bringToPhone(listOf(id), timeout).single()
  }

  /**
   * The dialogue track of [id], a clip of [seedAudioClip], arrives: the computer finishes the job again with the same
   * clip data and mix plus a dialogue WAV of the mix's length (a different checksum), and a sync pass brings it to this
   * phone. Returns once the clip's media has a verified dialogue file here.
   */
  suspend fun addDialogue(id: JobId, timeout: Duration = SYNC_TIMEOUT) {
    val before = checkNotNull(served[id]) { "${id.value} was not seeded with seedAudioClip" }
    val withDialogue = ClipContent(
      analysisJson = before.analysisJson,
      audioMix = before.audioMix,
      audioDialogue = FakeMediaBytes.wav(before.durationS + AUDIO_TAIL_S, level = DIALOGUE_LEVEL),
      durationS = before.durationS,
      segments = before.segments,
      words = before.words,
      changeCounts = before.changeCounts,
    )
    computer.finishJob(id, content = withDialogue)
    served[id] = withDialogue
    sync.syncNow(SyncReason.SYNC_NOW)
    drive("The dialogue track of ${id.value}", timeout) { content.media(id)?.audioDialogue != null }
  }

  /**
   * Pairs if this phone is not paired yet, runs a sync pass and waits until each of [ids] (clips already on the
   * computer) is on this phone. Returns [ids].
   */
  suspend fun bringToPhone(ids: List<JobId>, timeout: Duration = SYNC_TIMEOUT): List<JobId> {
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
    var listed: List<ClipSummary> = emptyList()
    drive("The clips ${ids.map { it.value }}", timeout) {
      listed = clips.clips(ClipQuery()).first()
      ids.all { id -> listed.any { it.id == id && it.availability == ClipAvailability.OnThisPhone } }
    }
    return listed
  }

  /**
   * Releases the work the stack schedules until [done], at most [timeout]; [what] names the wait in its failure. The
   * periodic sync is left waiting: released at every poll it would run a pass, and say hello to the computer, ten
   * times a second, and the computer's rate limit would cut a long seeding off (`WorkDriver.runEnqueued`).
   */
  private suspend fun drive(what: String, timeout: Duration, done: suspend () -> Boolean) {
    try {
      withTimeout(timeout) {
        while (true) {
          workDriver.runEnqueued(periodic = false)
          if (done()) break
          delay(POLL)
        }
      }
    } catch (timedOut: TimeoutCancellationException) {
      throw AssertionError(
        "$what did not reach this phone within $timeout through ${sync.javaClass.simpleName} and " +
          "${clips.javaClass.simpleName}; the sync state is ${sync.state.value}",
        timedOut,
      )
    }
  }

  private companion object {
    const val DEVICE_NAME = "Test phone"
    val SYNC_TIMEOUT = 30.seconds
    val POLL = 100.milliseconds

    /** Audio a little longer than the clip's timeline, as a computer's `audio.wav` is never shorter. */
    const val AUDIO_TAIL_S = 1.0

    /** Every sample of the dialogue WAV, so it differs from the silent mix in its bytes and checksum. */
    const val DIALOGUE_LEVEL: Short = 1
  }
}
