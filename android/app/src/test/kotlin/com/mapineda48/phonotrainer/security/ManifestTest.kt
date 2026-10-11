package com.mapineda48.phonotrainer.security

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Architecture 20.1 and work-plan WP-02 Proof: the merged manifest exports only the launcher and the share target of
 * the app's own components, the internal alias stays private, `ShareActivity` has no task affinity, and the exported
 * components and permissions equal the table of `android/SECURITY.md` (the hygiene check compares the same table with
 * the merged release manifest). Robolectric reads the debug variant's merged manifest, so the two components that only
 * debug builds merge (the Compose test activity and the preview activity) are left out of the comparison.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManifestTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Suppress("DEPRECATION") // getPackageInfo(String, Int): the flags overload is the one every API level has
  private val info: PackageInfo = context.packageManager.getPackageInfo(
    context.packageName,
    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
      PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS,
  )

  private val components: List<Pair<String, ComponentInfo>> =
    info.activities.orEmpty().map { (if (it.targetActivity != null) "activity-alias" else "activity") to it } +
      info.services.orEmpty().map { "service" to it } +
      info.receivers.orEmpty().map { "receiver" to it } +
      info.providers.orEmpty().map { "provider" to it }

  @Test
  fun onlyTheLauncherAndTheShareTargetOfTheAppAreExported() {
    val own = components.filter { (_, c) -> c.exported && c.name.startsWith(APP_PACKAGE) }.map { it.second.name }
    assertThat(own).containsExactly(MAIN_ACTIVITY, SHARE_ACTIVITY)
  }

  @Test
  fun theInternalEntryIsAPrivateAliasOfMainActivity() {
    val alias = info.activities.orEmpty().single { it.name == "$APP_PACKAGE.InternalEntry" }
    assertThat(alias.exported).isFalse()
    assertThat(alias.targetActivity).isEqualTo(MAIN_ACTIVITY)
  }

  @Test
  fun theShareTargetHasNoTaskAffinityAndStaysOutOfRecents() {
    val share = info.activities.orEmpty().single { it.name == SHARE_ACTIVITY }
    val main = info.activities.orEmpty().single { it.name == MAIN_ACTIVITY }

    assertThat(share.taskAffinity.orEmpty()).isEmpty()
    assertThat(share.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
    // MainActivity keeps the default affinity (architecture 20.1).
    assertThat(main.taskAffinity).isEqualTo(context.packageName)
    assertThat(main.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_TASK)
  }

  @Test
  fun exportedComponentsAndPermissionsEqualSecurityMd() {
    val actual = info.requestedPermissions.orEmpty().map { "permission" to it }.toSet() +
      components.filter { (_, c) -> c.exported && c.name !in DEBUG_ONLY }.map { (kind, c) -> kind to c.name }

    assertThat(actual).containsExactlyElementsIn(securityMdTable())
  }

  /** The `Kind | Name` table under "Merged release manifest" in android/SECURITY.md. */
  private fun securityMdTable(): Set<Pair<String, String>> {
    val lines = File(System.getProperty("user.dir"), "../SECURITY.md").readLines()
    val start = lines.indexOfFirst { it.startsWith("#") && "merged release manifest" in it.lowercase() }
    check(start >= 0) { "no 'Merged release manifest' section in SECURITY.md" }
    return lines.drop(start + 1)
      .takeWhile { !it.startsWith("#") }
      .filter { it.trim().startsWith("|") }
      .drop(1)
      .map { row -> row.trim().trim('|').split('|').map { it.trim().trim('`') } }
      .filterNot { cells -> cells[0].all { it == '-' || it == ':' } }
      .map { cells -> cells[0].lowercase() to cells[1] }
      .toSet()
  }

  private companion object {
    const val APP_PACKAGE = "com.mapineda48.phonotrainer"
    const val MAIN_ACTIVITY = "$APP_PACKAGE.MainActivity"
    const val SHARE_ACTIVITY = "$APP_PACKAGE.feature.share.ShareActivity"

    /**
     * Merged only into debug builds, never into the release manifest: the host activity of Compose screen tests
     * (`ui-test-manifest`) and the activity that runs previews on a device (`ui-tooling`).
     */
    val DEBUG_ONLY = setOf("androidx.activity.ComponentActivity", "androidx.compose.ui.tooling.PreviewActivity")
  }
}
