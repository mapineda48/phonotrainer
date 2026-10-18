package com.mapineda48.phonotrainer.qasmoke

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first black-box test of the release-like build (architecture 21.6; work-plan WP-19, first week): the unmodified
 * `qa` APK (R8 and resource shrinking on, not debuggable) starts from the launcher, shows the Welcome on a first
 * launch, and "Skip" leaves it (for Connect's first run, over Home). It proves the module and the minified start-up
 * path (Hilt, Compose, Navigation 3 keys, the settings store) before anything depends on them.
 *
 * Nothing of the app is on this APK's classpath: the app is found by its package name and its screens by the test
 * tags the shell exposes as resource ids, which are part of the test contract (work-plan 2.6).
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)

  @Before
  fun startFromNothing() {
    // A first launch every time: the Welcome shows only until its end is stored.
    device.executeShellCommand("pm clear $APP")
    device.pressHome()
  }

  @Test
  fun theUnmodifiedQaApkStartsAndSkipLeavesTheWelcome() {
    val info = instrumentation.context.packageManager.getApplicationInfo(APP, 0)
    assertThat(info.flags and ApplicationInfo.FLAG_DEBUGGABLE).isEqualTo(0)

    val launch = checkNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(APP)) {
      "$APP has no launcher activity"
    }
    instrumentation.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    assertThat(device.wait(Until.hasObject(By.pkg(APP).depth(0)), LAUNCH_TIMEOUT_MS)).isTrue()

    // "Skip" is on every page of the Welcome (spec F-01); "Get started" only on the last one.
    val skip = device.wait(Until.findObject(By.res(SKIP)), UI_TIMEOUT_MS)
    assertThat(skip).isNotNull()
    skip.click()

    assertThat(device.wait(Until.hasObject(By.res(HOME_TAB)), UI_TIMEOUT_MS)).isTrue()
    assertThat(device.wait(Until.gone(By.res(WELCOME)), UI_TIMEOUT_MS)).isTrue()
  }

  private companion object {
    const val APP = "com.mapineda48.phonotrainer"

    // The app's test tags, exposed as resource ids by the shell (`OnboardingTags.SKIP`, `ShellTags`).
    const val SKIP = "onboarding.welcome.skip"
    const val WELCOME = "destination.welcome"
    const val HOME_TAB = "shell.tab.home"

    const val LAUNCH_TIMEOUT_MS = 20_000L
    const val UI_TIMEOUT_MS = 10_000L
  }
}
