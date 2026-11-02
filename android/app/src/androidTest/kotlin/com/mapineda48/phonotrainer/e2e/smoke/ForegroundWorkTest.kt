package com.mapineda48.phonotrainer.e2e.smoke

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.WorkDriver
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Work that enters the foreground and ends at once, again and again, never brings the app down on the e2e WorkManager
 * (decisions I-21). `WorkManagerTestInitHelper`'s task executor runs WorkManager's main-thread callbacks inline on the
 * worker's thread, so WorkManager's foreground dispatcher heard of the end of the work on that thread while the main
 * thread was still handling the work's last foreground request: it found no work left, asked Android for a foreground
 * service of type "none", and API 34+ refused it with `InvalidForegroundServiceTypeException` (seen in
 * `HomeAreaTest`). `HiltTestRunner` keeps WorkManager's foreground service off in e2e runs.
 *
 * Keep the test's shape: without the runner's change this class died at the first run and at the 67th, but a variant
 * with the v2 compose rule and a JUnit `Timeout` rule went through all 120 runs once (decisions I-21).
 */
@HiltAndroidTest
class ForegroundWorkTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var work: WorkDriver

  private val robot = PhonoRobot(compose)
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @After
  fun tearDown() {
    if (robot.isLaunched()) robot.scenario.close()
    NotificationManagerCompat.from(context).deleteNotificationChannel(CHANNEL)
  }

  @Test
  fun workThatEntersTheForegroundAndEndsAtOnceNeverBringsTheAppDown() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    // A foreground service may start only while the app is visible.
    robot.launch()
    NotificationManagerCompat.from(context).createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(CHANNEL).build(),
    )
    val workManager = WorkManager.getInstance(context)

    repeat(RUNS) { run ->
      // Each run ends a little later after entering the foreground, sweeping the moment the service handles it.
      val request = OneTimeWorkRequestBuilder<EnterForegroundAndEnd>()
        .setInputData(workDataOf(KEY_END_AFTER_MICROS to (run % SWEEP_STEPS) * STEP_MICROS))
        .build()
      workManager.enqueue(request).result.get()
      assertThat(work.awaitFinished(request.id)).isEqualTo(WorkInfo.State.SUCCEEDED)
    }
    // Whatever the service still has to handle is handled before the test ends.
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    assertThat(robot.isLaunched()).isTrue()
  }

  /**
   * Enters the foreground as a `dataSync` transfer and ends the given number of microseconds later (a visible download
   * that finds nothing to fetch ends this way). The service handles the start, then the notification update queued
   * right behind it; an end inside that update is what the e2e WorkManager raced.
   */
  class EnterForegroundAndEnd(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(CHANNEL)
        .build()
      setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
      // A busy loop on purpose, and not a wait for an effect (AGENTS.md 5 forbids those): it is this work's own length,
      // swept in steps of STEP_MICROS, finer than a coroutine delay or a sleep can time. It holds the worker's thread
      // for at most SWEEP_STEPS * STEP_MICROS (24 ms).
      val end = SystemClock.elapsedRealtimeNanos() + inputData.getLong(KEY_END_AFTER_MICROS, 0) * NANOS_PER_MICRO
      while (SystemClock.elapsedRealtimeNanos() < end) {
        // Spin until the work's length has passed.
      }
      return Result.success()
    }
  }

  private companion object {
    const val RUNS = 120
    const val SWEEP_STEPS = 60
    const val STEP_MICROS = 400L
    const val NANOS_PER_MICRO = 1_000L
    const val KEY_END_AFTER_MICROS = "end_after_micros"
    const val CHANNEL = "e2e-foreground-work"
    const val NOTIFICATION_ID = 9_901
  }
}
