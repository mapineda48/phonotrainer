package com.mapineda48.phonotrainer.core.connectivity.platform

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.connectivity.status.ResourceStatusWords
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.NetworkKind
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The Android adapters of `:core:connectivity` on Robolectric (architecture 6.1, 6.3, 6.4). */
@RunWith(RobolectricTestRunner::class)
class PlatformTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)

  private fun capabilities(transport: Int, validated: Boolean): NetworkCapabilities =
    ShadowNetworkCapabilities.newInstance().also { capabilities ->
      shadowOf(capabilities).addTransportType(transport)
      if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

  @Test
  fun theMonitorFollowsTheWifiCallbacksAndFallsBackToMobileOnly() {
    val monitor = AndroidNetworkMonitor(context, PhonoLog.NONE)
    val callbacks = shadowOf(connectivity).networkCallbacks.toList()
    assertThat(callbacks).hasSize(3)
    val wifi = ShadowNetwork.newInstance(7)
    val cell = ShadowNetwork.newInstance(8)

    callbacks.forEach { it.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = false)) }
    assertThat(monitor.network.value.kind).isEqualTo(NetworkKind.WIFI)
    assertThat(monitor.network.value.networkHandle).isEqualTo(wifi.networkHandle)
    assertThat(monitor.network.value.validated).isFalse()

    // Only the cellular callback reacts to availability; the home callback ignores it.
    callbacks.forEach { it.onAvailable(cell) }
    callbacks.forEach { it.onLost(wifi) }
    assertThat(monitor.network.value.kind).isEqualTo(NetworkKind.MOBILE_ONLY)
    callbacks.forEach { it.onLost(cell) }
    assertThat(monitor.network.value.kind).isEqualTo(NetworkKind.NONE)
  }

  @Test
  fun anEthernetNetworkIsAHomeNetworkAndACellularCapabilityIsNot() {
    val monitor = AndroidNetworkMonitor(context, PhonoLog.NONE)
    val callbacks = shadowOf(connectivity).networkCallbacks.toList()
    callbacks.forEach { it.onCapabilitiesChanged(ShadowNetwork.newInstance(3), capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, true)) }
    assertThat(monitor.network.value.kind).isEqualTo(NetworkKind.NONE)

    callbacks.forEach { it.onCapabilitiesChanged(ShadowNetwork.newInstance(4), capabilities(NetworkCapabilities.TRANSPORT_ETHERNET, true)) }
    assertThat(monitor.network.value.kind).isEqualTo(NetworkKind.ETHERNET)
    assertThat(monitor.network.value.validated).isTrue()
  }

  @Test
  fun aRestrictedAppOrARareOrRestrictedBucketIsBackgroundLimited() {
    val activity = shadowOf(context.getSystemService(ActivityManager::class.java))
    val usage = shadowOf(context.getSystemService(UsageStatsManager::class.java))
    val monitor = BackgroundRestrictionMonitor(AndroidBackgroundRestrictionSource(context), PhonoLog.NONE)

    usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE)
    monitor.refresh()
    assertThat(monitor.restricted.value).isFalse()
    usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
    monitor.refresh()
    assertThat(monitor.restricted.value).isTrue()
    usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
    monitor.refresh()
    assertThat(monitor.restricted.value).isTrue()
    usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT)
    activity.setBackgroundRestricted(true)
    monitor.refresh()
    assertThat(monitor.restricted.value).isTrue()
    activity.setBackgroundRestricted(false)
    monitor.refresh()
    assertThat(monitor.restricted.value).isFalse()
  }

  class Idle(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
  }

  @Test
  fun forgettingCancelsTheComputersUniqueWorksAndKeepsTheFileCopies() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    val work = WorkManager.getInstance(context)
    work.enqueueUniquePeriodicWork(
      "sync-periodic",
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<Idle>(15, TimeUnit.MINUTES).build(),
    )
    listOf("sync-on-wifi", "send-now", "downloads", "uploads", "intake-00000000-0000-4000-8000-000000000001").forEach { name ->
      work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<Idle>().setInitialDelay(1, TimeUnit.HOURS).build())
    }

    WorkManagerComputerWork(context, PhonoLog.NONE).cancelAll()

    ComputerWork.UNIQUE_NAMES.forEach { name ->
      assertThat(work.getWorkInfosForUniqueWork(name).get().map { it.state }).containsExactly(WorkInfo.State.CANCELLED)
    }
    assertThat(work.getWorkInfosForUniqueWork("intake-00000000-0000-4000-8000-000000000001").get().single().state)
      .isEqualTo(WorkInfo.State.ENQUEUED)
  }

  @Test
  fun theAppVersionIsAtLeastOne() {
    val version = PackageAppVersion(context)

    assertThat(version.code).isAtLeast(1)
    assertThat(version.name).isNotEmpty()
  }

  @Test
  fun onlyConnectedOfflineAndNotReachableHaveAnnouncementWords() {
    val words = ResourceStatusWords(context)

    assertThat(words.of(ComputerStatus.Connected("Study desk", 0, 0, false))).isEqualTo("Connected to Study desk")
    assertThat(words.of(ComputerStatus.Offline)).isEqualTo("Offline")
    assertThat(words.of(ComputerStatus.NotReachable("Study desk", null, false, ProbeOutcome.NoAnswer(3))))
      .isEqualTo("Your computer isn't reachable")
    listOf(ComputerStatus.Checking, ComputerStatus.Looking, ComputerStatus.NotOnWifi, ComputerStatus.ConnectAgain)
      .forEach { assertThat(words.of(it)).isNull() }
  }
}
