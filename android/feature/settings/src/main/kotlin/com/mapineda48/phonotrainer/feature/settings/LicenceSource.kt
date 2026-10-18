package com.mapineda48.phonotrainer.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the licences screen reads its sources, all of them inside the APK (spec F-24-AC1: readable offline): the
 * generated report of runtime libraries, `bundled.json`, and the licence texts.
 */
interface LicenceSource {
  /** The AboutLibraries report of this build (`res/raw/aboutlibraries.json`); null when the build has none. */
  suspend fun report(): String?

  /** `:core:designsystem`'s `assets/licenses/bundled.json`; null when absent. */
  suspend fun bundled(): String?

  /** The text of the asset [path]; null when it is not bundled. */
  suspend fun text(path: String): String?
}

/** The installed app's version, as About shows it (spec F-24). */
interface AppVersion {
  /** The version name ("1.0.0"); null when the package manager cannot say. */
  val name: String?
}

/**
 * [LicenceSource] over the APK's resources and assets. The report is a raw resource of the application module (the
 * AboutLibraries plugin adds it to each variant, decision I-01), so it is found by name; `res/raw/settings_keep.xml`
 * keeps it through resource shrinking.
 */
class AndroidLicenceSource @Inject constructor(@param:ApplicationContext private val context: Context) : LicenceSource {
  // The report is generated into the application module's resources, which a library cannot reference by id.
  @SuppressLint("DiscouragedApi")
  override suspend fun report(): String? = withContext(Dispatchers.IO) {
    val id = context.resources.getIdentifier(REPORT, "raw", context.packageName)
    if (id == 0) null else context.resources.openRawResource(id).bufferedReader().use { it.readText() }
  }

  override suspend fun bundled(): String? = text(Licences.BUNDLED_INDEX)

  override suspend fun text(path: String): String? = withContext(Dispatchers.IO) {
    try {
      context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (missing: FileNotFoundException) {
      null
    }
  }

  private companion object {
    const val REPORT = "aboutlibraries"
  }
}

/** [AppVersion] from the package manager. */
class PackageAppVersion @Inject constructor(@param:ApplicationContext private val context: Context) : AppVersion {
  override val name: String? by lazy {
    try {
      val manager = context.packageManager
      val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
      }
      info.versionName
    } catch (missing: PackageManager.NameNotFoundException) {
      null
    }
  }
}

/** The bindings of :feature:settings' own seams (the licence sources and the app's version). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
  @Binds
  abstract fun licenceSource(source: AndroidLicenceSource): LicenceSource

  @Binds
  abstract fun appVersion(version: PackageAppVersion): AppVersion
}
