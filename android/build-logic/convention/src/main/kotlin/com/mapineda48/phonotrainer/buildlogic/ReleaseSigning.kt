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
 * It is a Java properties file, where `\` starts an escape: a Windows path is written with `/`
 * (`C:/Users/<you>/keys/release.jks`) or with each `\` doubled; a single `\` gets a hint in the error.
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

  val fileName = location.get().asFile.name
  val properties = try {
    Properties().apply { load(StringReader(text)) }
  } catch (e: IllegalArgumentException) {
    // A backslash and a "u" not followed by four hex digits, as a Windows path with single backslashes can hold.
    throw GradleException("$fileName cannot be read (${e.message}). $BACKSLASH_HINT", e)
  }
  fun value(key: String): String = properties.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: throw GradleException("$fileName has no '$key' (architecture 20.5; see android/README.md)")

  val storeFile = rootProject.file(value("storeFile"))
  if (!storeFile.isFile) {
    // Properties.load drops a single backslash: C:\keys\store.jks is read as the drive-relative C:keysstore.jks.
    val storeLine = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("storeFile") }.orEmpty()
    val hint = if ('\\' in storeLine) ". $BACKSLASH_HINT" else ""
    throw GradleException("The keystore named by $fileName does not exist (architecture 20.5)$hint")
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

/** Why a Windows path in the properties file is not the file it names. */
private const val BACKSLASH_HINT =
  "In a properties file '\\' starts an escape sequence: write the path with '/' (C:/Users/<you>/keys/release.jks) " +
    "or with each '\\' doubled ('\\\\')."
