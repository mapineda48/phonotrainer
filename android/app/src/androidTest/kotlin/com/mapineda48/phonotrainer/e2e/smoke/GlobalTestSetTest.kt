package com.mapineda48.phonotrainer.e2e.smoke

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.PairingRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.spi.ClipRemovalHook
import com.mapineda48.phonotrainer.core.domain.spi.DiscoveryTransport
import com.mapineda48.phonotrainer.core.domain.spi.NetworkMonitor
import com.mapineda48.phonotrainer.core.domain.spi.StartupOrder
import com.mapineda48.phonotrainer.core.domain.spi.StartupTask
import com.mapineda48.phonotrainer.core.network.NetworkPolicyModule
import com.mapineda48.phonotrainer.core.network.api.AddressPolicy
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.computer.JobStatus
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeNetworkMonitor
import com.mapineda48.phonotrainer.e2e.di.FakeComputers
import com.mapineda48.phonotrainer.e2e.di.FakeDiscovery
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.support.TestTime
import com.mapineda48.phonotrainer.e2e.support.WorkDriver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The global test set of architecture 21.5 at work (work-plan WP-19, first week): every replaced seam is the test
 * one, each test has its own fake computer that the phone reaches over the loopback network and that is closed when
 * the test ends, discovery is answered by it, the phone's and the computer's clocks move together, WorkManager runs
 * work when the driver releases it and creates `@HiltWorker` workers with the running test's bindings, and
 * `TestSeeder` seeds the computer (and, once the cores are merged, the phone through the real stack).
 */
@HiltAndroidTest
class GlobalTestSetTest {
  @get:Rule
  val hilt = HiltAndroidRule(this)

  @Inject lateinit var clock: Clock

  @Inject lateinit var testClock: TestClock

  @Inject lateinit var time: TestTime

  @Inject lateinit var network: NetworkMonitor

  @Inject lateinit var discovery: DiscoveryTransport

  @Inject lateinit var addressPolicy: AddressPolicy

  @Inject lateinit var library: LibraryDatabase

  @Inject lateinit var learning: LearningDatabase

  @Inject lateinit var computer: FakeComputer

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var work: WorkDriver

  @Inject lateinit var pairing: PairingRepository

  @Inject lateinit var sync: SyncController

  @Inject lateinit var clips: ClipRepository

  @Inject lateinit var startupTasks: Set<@JvmSuppressWildcards StartupTask>

  @Inject lateinit var removalHooks: Set<@JvmSuppressWildcards ClipRemovalHook>

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun inject() = hilt.inject()

  @Test
  fun everyReplacedSeamIsTheTestOneAndNothingSurvivesFromAnotherTest() {
    assertThat(clock).isSameInstanceAs(testClock)
    assertThat(network).isInstanceOf(FakeNetworkMonitor::class.java)
    assertThat(discovery).isInstanceOf(FakeDiscovery::class.java)
    assertThat(addressPolicy.allowsCandidate(FakeComputer.HOST)).isTrue()
    assertThat(library.openHelper.databaseName).isNull()
    assertThat(learning.openHelper.databaseName).isNull()
    assertThat(work.workInfos()).isEmpty()
  }

  /** CR-01-A1: the in-memory learning database names its history as a real one does, with one `learning_id`. */
  @Test
  fun theSpiSetsAreDeclaredAndEveryStartupTaskCarriesItsOrder() {
    // Declared with @Multibinds in :app (decision I-16), so StartupCheck (WP-08) and the clip removal (WP-08) can
    // inject them before, and whether or not, any core contributes one.
    startupTasks.forEach { task ->
      assertWithMessage(task.javaClass.name).that(task.javaClass.isAnnotationPresent(StartupOrder::class.java)).isTrue()
    }
    assertThat(removalHooks.size).isAtLeast(0)
  }

  @Test
  fun theInMemoryLearningDatabaseHasItsLearningId() {
    val rows = learning.openHelper.writableDatabase.query("SELECT `key`, `value` FROM learning_meta").use { cursor ->
      buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
    }

    assertThat(rows.map { it.first }).containsExactly("learning_id")
    assertThat(UUID.fromString(rows.single().second).toString()).isEqualTo(rows.single().second)
  }

  /**
   * The test policy is the release one plus loopback and nothing more (architecture 5.2, 21.5), so the cleartext
   * confinement proven end to end (X-AC6) is the release one: every private and link-local range the release accepts
   * is a candidate here too, and no host name passes, however much it starts like a private address.
   */
  @Test
  fun theAddressPolicyIsTheReleaseOnePlusLoopback() {
    val release = NetworkPolicyModule.addressPolicy()
    // hygiene: test-vector begin
    val accepted = listOf("10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.1.1")
    val rejected = listOf(
      "10.example.test", "10.evil.example", "192.168.1.1.attacker.example", "172.15.0.1", "172.32.0.1", "169.253.1.1",
      "192.169.1.1", "010.1.2.3", "10.1.2.256", "10.1.2", "203.0.113.7", "127.0.0.2", "localhost", "::1", "",
    )
    // hygiene: test-vector end

    assertThat(addressPolicy.allowsCandidate("127.0.0.1")).isTrue()
    assertThat(release.allowsCandidate("127.0.0.1")).isFalse()
    for (host in accepted) {
      assertWithMessage("accepts %s", host).that(addressPolicy.allowsCandidate(host)).isTrue()
    }
    for (host in rejected) {
      assertWithMessage("rejects %s", host).that(addressPolicy.allowsCandidate(host)).isFalse()
    }
    for (host in accepted + rejected) {
      assertWithMessage("agrees with the release policy on %s", host)
        .that(addressPolicy.allowsCandidate(host)).isEqualTo(release.allowsCandidate(host))
    }
  }

  @Test
  fun eachTestHasItsOwnComputerThatThePhoneReaches() {
    // A computer left open by an earlier test would show here: the runner's listener closes each one.
    assertThat(FakeComputers.openCount).isEqualTo(1)

    val connection = computer.url("/api/phone/hello").toUrl().openConnection() as HttpURLConnection
    val body = try {
      assertThat(connection.responseCode).isEqualTo(HttpURLConnection.HTTP_OK)
      connection.inputStream.bufferedReader().readText()
    } finally {
      connection.disconnect()
    }
    assertThat(body).contains("\"server_id\":\"${computer.serverId.value}\"")
  }

  @Test
  fun anotherTestGetsAnotherComputer() {
    assertThat(FakeComputers.openCount).isEqualTo(1)
    assertThat(computer.jobs).isEmpty()
    assertThat(computer.pairedDevices).isEmpty()
  }

  @Test
  fun discoveryIsAnsweredByThisTestsComputer() = runBlocking {
    val answers = discovery.probe(PROBE, listOf("255.255.255.255"), listOf(Duration.ZERO), Duration.ofSeconds(1)).toList()

    assertThat(answers).hasSize(1)
    assertThat(answers.single().sourceHost).isEqualTo(FakeComputer.HOST)
    assertThat(answers.single().sourcePort).isEqualTo(FakeDiscovery.DISCOVERY_PORT)
    val reply = answers.single().bytes.decodeToString()
    assertThat(reply).contains("\"server_id\":\"${computer.serverId.value}\"")
    assertThat(reply).contains("\"http_port\":${computer.address.port}")
    assertThat((discovery as FakeDiscovery).probes).hasSize(1)

    computer.phoneAccessOff()
    assertThat(discovery.probe(PROBE, emptyList(), emptyList(), Duration.ZERO).toList()).isEmpty()
  }

  @Test
  fun thePhoneAndTheComputerShareOneClockThatOnlyTestsMove() {
    val start = testClock.now()
    assertThat(computer.serverTimeS()).isEqualTo(start.epochSecond)

    time.advanceBy(Duration.ofMinutes(10))

    assertThat(clock.now()).isEqualTo(start.plusSeconds(600))
    assertThat(computer.serverTimeS()).isEqualTo(start.epochSecond + 600)
  }

  @Test
  fun workWaitsForItsConstraintsUntilTheDriverReleasesIt() {
    val request = OneTimeWorkRequestBuilder<ClockProbeWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
      .setInitialDelay(1, TimeUnit.HOURS)
      .build()
    WorkManager.getInstance(context).enqueue(request).result.get()
    assertThat(state(request.id)).isEqualTo(WorkInfo.State.ENQUEUED)

    assertThat(work.runEnqueued()).containsExactly(request.id)

    assertThat(state(request.id)).isEqualTo(WorkInfo.State.SUCCEEDED)
  }

  @Test
  fun hiltWorkersGetTheRunningTestsBindings() {
    testClock.instant = Instant.parse("2026-10-11T08:30:00Z")
    val request = OneTimeWorkRequestBuilder<ClockProbeWorker>().build()

    WorkManager.getInstance(context).enqueue(request).result.get()
    work.runEnqueued()

    val info = WorkManager.getInstance(context).getWorkInfoById(request.id).get()!!
    assertThat(info.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    assertThat(info.outputData.getLong(ClockProbeWorker.NOW, 0)).isEqualTo(testClock.instant.toEpochMilli())
  }

  @Test
  fun theSeederCreatesFinishedClipsOnThisTestsComputer() {
    assertThat(seeder.computer).isSameInstanceAs(computer)

    val ids = seeder.addFinishedClips("Ordering coffee", "At the station")

    assertThat(computer.jobs.map { it.id }).containsExactlyElementsIn(ids)
    assertThat(computer.jobs.map { it.status }.toSet()).containsExactly(JobStatus.DONE)
    assertThat(computer.jobs.map { it.title }).containsExactly("Ordering coffee", "At the station")
  }

  /**
   * Seeding through the real stack. Once the cores replace their B1 stubs (WP-05b, WP-07, WP-08, WP-10) it brings a
   * clip to the phone. While any of them is still a stub it proves the other half of the seeder's contract: seeding
   * fails, naming the stub it ran into, instead of passing or hanging. The integrator watches it switch at those
   * merges (no skipped test: the managed-device report counts an assumption failure as a failure).
   */
  @Test
  fun theSeederBringsClipsToThePhoneThroughTheRealStack() = runBlocking {
    val stubs = listOf(pairing, sync, clips).map { it.javaClass.simpleName }.filter { it.startsWith("Stub") }
    if (stubs.isNotEmpty()) {
      val failure = runCatching { seeder.seedLibrary("Ordering coffee", timeout = 2.seconds) }.exceptionOrNull()
      assertWithMessage("seeding with the B1 stubs $stubs").that(failure).isNotNull()
      assertThat(failure).hasMessageThat().contains("Stub")
      return@runBlocking
    }

    val ids = seeder.seedLibrary("Ordering coffee")

    val listed = clips.clips(ClipQuery()).first()
    assertThat(listed.single { it.id == ids.single() }.availability).isEqualTo(ClipAvailability.OnThisPhone)
  }

  private fun state(id: UUID): WorkInfo.State? = WorkManager.getInstance(context).getWorkInfoById(id).get()?.state

  private companion object {
    /** A discovery probe as server-contract 5.2 defines it. */
    val PROBE = """{"t":"phonotrainer-probe","v":1,"nonce":"0123456789abcdef"}""".encodeToByteArray()
  }
}

/** A `@HiltWorker` that reports the injected clock: proves that workers get the running test's bindings. */
@HiltWorker
class ClockProbeWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val clock: Clock,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = Result.success(workDataOf(NOW to clock.now().toEpochMilli()))

  companion object {
    const val NOW = "now"
  }
}
