package com.mapineda48.phonotrainer.core.connectivity.platform

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** This app's version, as pairing sends it and `min_app_version` is compared with (server-contract 6.2). */
internal interface AppVersion {
  val code: Int
  val name: String
}

/** The installed package's `versionCode` and `versionName` (at least 1, which the computer requires). */
internal class PackageAppVersion @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : AppVersion {
  private val info by lazy {
    val manager = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      manager.getPackageInfo(context.packageName, 0)
    }
  }

  override val code: Int get() = info.longVersionCode.toInt().coerceAtLeast(1)

  override val name: String get() = info.versionName?.takeIf { it.isNotBlank() } ?: code.toString()
}

/** Whether the app is in the foreground: probes, re-discovery and the 30 s / 2 min schedule follow it (spec 7.5). */
internal interface AppForeground {
  val foreground: StateFlow<Boolean>
}

/** [AppForeground] from `ProcessLifecycleOwner`: at least STARTED. */
@Singleton
internal class ProcessForeground @Inject constructor(
  @param:ApplicationScope private val scope: CoroutineScope,
) : AppForeground {
  private val state = MutableStateFlow(false)

  override val foreground: StateFlow<Boolean> = state.asStateFlow()

  init {
    scope.launch(Dispatchers.Main.immediate) {
      val lifecycle = ProcessLifecycleOwner.get().lifecycle
      state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
      lifecycle.addObserver(
        LifecycleEventObserver { _, _ -> state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
      )
    }
  }
}

/** What the platform says about running this app in the background (architecture 6.4). */
internal interface BackgroundRestrictionSource {
  /** `ActivityManager.isBackgroundRestricted()`. */
  fun restricted(): Boolean

  /** `UsageStatsManager.getAppStandbyBucket()`: the app's own bucket needs no permission. */
  fun standbyBucket(): Int
}

internal class AndroidBackgroundRestrictionSource @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : BackgroundRestrictionSource {
  override fun restricted(): Boolean = context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

  override fun standbyBucket(): Int =
    context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket ?: UsageStatsManager.STANDBY_BUCKET_ACTIVE
}

/**
 * The background-limited state of the Home line and Connection details (architecture 6.4, F-03, F-21): the learner
 * restricted the app, or Android put it in the `RARE` or `RESTRICTED` standby bucket. Read at start and every time
 * the app comes to the foreground (the learner changes it in the system settings, outside the app).
 */
@Singleton
internal class BackgroundRestrictionMonitor @Inject constructor(
  private val source: BackgroundRestrictionSource,
  private val log: PhonoLog,
) {
  private val state = MutableStateFlow(false)

  val restricted: StateFlow<Boolean> = state.asStateFlow()

  fun refresh() {
    state.value = try {
      limited(source.restricted(), source.standbyBucket())
    } catch (e: RuntimeException) {
      log.w("connectivity.background_state", e)
      false
    }
  }

  companion object {
    /** `RESTRICTED` (45, API 30) is above `RARE` (40); both limit background work for hours. */
    fun limited(restricted: Boolean, bucket: Int): Boolean = restricted || bucket >= UsageStatsManager.STANDBY_BUCKET_RARE
  }
}

/** The unique works "Forget this computer" cancels (architecture 6.3, 7.2). */
internal interface ComputerWork {
  fun cancelAll()

  companion object {
    /**
     * Every unique work that talks to the computer (architecture 7.2). The copies of shared and picked files
     * (`intake-…`) are not among them: waiting requests stay, with their copies, for the next computer paired.
     */
    val UNIQUE_NAMES: List<String> = listOf("sync-periodic", "sync-on-wifi", "send-now", "downloads", "uploads")
  }
}

internal class WorkManagerComputerWork @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val log: PhonoLog,
) : ComputerWork {
  override fun cancelAll() {
    try {
      val work = WorkManager.getInstance(context)
      ComputerWork.UNIQUE_NAMES.forEach(work::cancelUniqueWork)
    } catch (e: IllegalStateException) {
      // WorkManager is not initialized (only possible in a process without the application): nothing is scheduled.
      log.w("connectivity.work_not_initialized", e)
    }
  }
}
