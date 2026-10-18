package com.mapineda48.phonotrainer.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.sync.engine.PassOutcome
import com.mapineda48.phonotrainer.core.sync.meta.SyncMeta
import com.mapineda48.phonotrainer.core.sync.work.SyncWorkNames
import com.mapineda48.phonotrainer.core.sync.work.WifiBackoff
import com.mapineda48.phonotrainer.core.sync.work.WorkManagerSyncWork
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The unique works of architecture 7.2 on WorkManager's test driver (Android review M1): `sync-on-wifi` retries with
 * a growing delay and never loops with a zero one, `send-now` runs one pass, the periodic sync has its constraints.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulingTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
  private val meta = SyncMeta(db.syncMetaDao())
  private val passes = ScriptedPasses()
  private val work = WorkManagerSyncWork(context, PhonoLog.NONE)
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  @Before
  fun setUp() = initWorkManager(context, SyncWorkerFactory({ passes }, { NO_DOWNLOADS }, { work }, { meta }))

  @After
  fun close() = db.close()

  private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

  private fun enqueued(name: String): WorkInfo? = infos(name).singleOrNull { it.state == WorkInfo.State.ENQUEUED }

  private suspend fun until(what: String, condition: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(10_000) { while (!condition()) delay(10) }
  }.also { check(condition()) { what } }

  @Test
  fun anUnreachableWifiGivesOneRunPerBackoffStepAndNeverAZeroDelayLoop() = runBlocking {
    val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
    work.ensureOnWifi()
    var current = enqueued(SyncWorkNames.ON_WIFI)!!
    assertThat(current.initialDelayMillis).isEqualTo(0)
    assertThat(current.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    // Nothing runs before the phone joins an unmetered network.
    delay(100)
    assertThat(passes.reasons).isEmpty()

    val steps = listOf(15L, 30L, 60L, 120L, 120L).map(Duration::ofMinutes)
    for ((index, step) in steps.withIndex()) {
      driver.setAllConstraintsMet(current.id)
      driver.setInitialDelayMet(current.id)
      until("run ${index + 1}") { passes.reasons.size == index + 1 && enqueued(SyncWorkNames.ON_WIFI)?.id?.let { it != current.id } == true }
      val next = enqueued(SyncWorkNames.ON_WIFI)!!
      assertThat(next.initialDelayMillis).isEqualTo(step.toMillis())
      assertThat(next.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
      // Its delay not met yet, the next run does not happen even with the network there.
      driver.setAllConstraintsMet(next.id)
      delay(100)
      assertThat(passes.reasons).hasSize(index + 1)
      current = next
    }
    assertThat(passes.reasons.toSet()).containsExactly(SyncReason.ON_WIFI)
    assertThat(meta.wifiBackoffMs()).isEqualTo(WifiBackoff.LAST.toMillis())
  }

  @Test
  fun aWifiRunThatReachesTheComputerRetriesOnlyWhatFailedForAMomentAndNeverTooSoon() = runBlocking {
    val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
    passes.outcome = PassOutcome.Done(listed = true)
    passes.retryIn = Duration.ofSeconds(5)
    work.ensureOnWifi()
    val first = enqueued(SyncWorkNames.ON_WIFI)!!

    driver.setAllConstraintsMet(first.id)
    until("the retry") { enqueued(SyncWorkNames.ON_WIFI)?.id?.let { it != first.id } == true }

    assertThat(enqueued(SyncWorkNames.ON_WIFI)!!.initialDelayMillis).isEqualTo(WifiBackoff.MIN_RETRY.toMillis())

    passes.retryIn = null
    val second = enqueued(SyncWorkNames.ON_WIFI)!!
    driver.setAllConstraintsMet(second.id)
    driver.setInitialDelayMet(second.id)
    until("the second run") { passes.reasons.size == 2 }
    delay(100)
    assertThat(enqueued(SyncWorkNames.ON_WIFI)).isNull()
  }

  @Test
  fun aBlockedQueueLeavesNoWifiRetryBehind() = runBlocking {
    val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
    passes.outcome = PassOutcome.Blocked
    work.ensureOnWifi()

    driver.setAllConstraintsMet(enqueued(SyncWorkNames.ON_WIFI)!!.id)
    until("the run") { passes.reasons.size == 1 }
    delay(100)

    assertThat(enqueued(SyncWorkNames.ON_WIFI)).isNull()
  }

  @Test
  fun somethingPendingKeepsOneWifiWorkAndNeverAddsASecond() = runBlocking {
    repeat(3) { work.ensureOnWifi() }

    assertThat(infos(SyncWorkNames.ON_WIFI).count { !it.state.isFinished }).isEqualTo(1)
  }

  @Test
  fun sendNowRunsOnePassAndLeavesAWifiWorkWhenTheComputerWasNotThere() = runBlocking {
    work.sendNow()

    until("the pass") { passes.reasons == listOf(SyncReason.SEND_NOW) }
    until("the wifi work") { enqueued(SyncWorkNames.ON_WIFI) != null }
  }

  @Test
  fun thePeriodicSyncRunsEveryQuarterHourOnAnUnmeteredNetworkWithBatteryNotLow() = runBlocking {
    work.ensurePeriodic()
    work.ensurePeriodic()

    val periodic = infos(SyncWorkNames.PERIODIC).single()
    assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(Duration.ofMinutes(15).toMillis())
    assertThat(periodic.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    assertThat(periodic.constraints.requiresBatteryNotLow()).isTrue()

    work.cancelUnpaired()
    assertThat(infos(SyncWorkNames.PERIODIC).single().state).isEqualTo(WorkInfo.State.CANCELLED)
  }
}
