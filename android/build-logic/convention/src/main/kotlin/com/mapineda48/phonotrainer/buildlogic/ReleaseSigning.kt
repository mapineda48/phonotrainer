package com.mapineda48.phonotrainer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.io.StringReader
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Release signing (architecture 20.5): one stable key kept outside the repository. When the ignored
 * `android/keystore.properties` exists (or the file the Gradle property [LOCATION_PROPERTY] names), release builds are
 * signed with the key it describes; otherwise the release build stays unsigned. The file holds four keys:
 *
 * ```
 * storeFile=<path of the keystore, absolute or relative to android/>
 * storePassword=…
 * keyAlias=…
 * keyPassword=…
 * ```
 *
 * The `qa` build type is signed with the per-machine debug key whatever this file says (21.6), so `qa` and debug
 * builds never carry the release key and only release builds are installed on the test phone (20.5, D-20).
 */
internal fun Project.configureReleaseSigning(android: ApplicationExtension) {
  val location = providers.gradleProperty(LOCATION_PROPERTY)
    .map { rootProject.layout.projectDirectory.file(it) }
    .orElse(rootProject.layout.projectDirectory.file(DEFAULT_FILE))
  // Read through a provider, so the configuration cache tracks the file's content (and its absence).
  val text = providers.fileContents(location).asText.orNull ?: return

  val properties = Properties().apply { load(StringReader(text)) }
  fun value(key: String): String = properties.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: throw GradleException("${location.get().asFile.name} has no '$key' (architecture 20.5; see android/README.md)")

  val storeFile = rootProject.file(value("storeFile"))
  if (!storeFile.isFile) {
    throw GradleException("The keystore named by ${location.get().asFile.name} does not exist (architecture 20.5)")
  }
  val release = android.signingConfigs.create("release") {
    this.storeFile = storeFile
    storePassword = value("storePassword")
    keyAlias = value("keyAlias")
    keyPassword = value("keyPassword")
  }
  android.buildTypes.getByName("release").signingConfig = release
}

/** The ignored file of `android/.gitignore` (architecture 2.6), relative to `android/`. */
private const val DEFAULT_FILE = "keystore.properties"

/** Points the build at another properties file, for example one in a temporary directory (relative to `android/`). */
internal const val LOCATION_PROPERTY = "phonotrainer.keystoreProperties"
