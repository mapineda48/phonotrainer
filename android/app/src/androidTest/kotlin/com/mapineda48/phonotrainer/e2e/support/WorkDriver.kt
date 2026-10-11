package com.mapineda48.phonotrainer.e2e.support

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * Drives the test WorkManager that `HiltTestRunner` installs (architecture 21.5; F-21-AC8 and F-23-AC1 use it):
 * work runs synchronously, but a request waits for its constraints, initial delay or period until a test says they
 * are met. Area robots and the cross-area flows use it instead of waiting for real time to pass.
 */
class WorkDriver @Inject constructor(@ApplicationContext private val context: Context) {
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  private val driver: TestDriver
    get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)) {
      "WorkManager is not the test instance; is the run using HiltTestRunner?"
    }

  /** Every request in [states], newest information first. */
  fun workInfos(vararg states: WorkInfo.State = WorkInfo.State.entries.toTypedArray()): List<WorkInfo> =
    workManager.getWorkInfos(WorkQuery.fromStates(*states)).get()

  /** Every request tagged [tag], in any state. */
  fun workInfosByTag(tag: String): List<WorkInfo> = workManager.getWorkInfos(WorkQuery.fromTags(tag)).get()

  /**
   * Marks the constraints, initial delay and (for periodic work) period of every enqueued request as met, so each runs
   * now; returns the ids it released. Work the released work enqueues is not released; call again for it.
   */
  fun runEnqueued(): List<UUID> {
    val enqueued = workInfos(WorkInfo.State.ENQUEUED)
    enqueued.forEach { info ->
      driver.setAllConstraintsMet(info.id)
      driver.setInitialDelayMet(info.id)
      if (info.periodicityInfo != null) driver.setPeriodDelayMet(info.id)
    }
    return enqueued.map { it.id }
  }
}
