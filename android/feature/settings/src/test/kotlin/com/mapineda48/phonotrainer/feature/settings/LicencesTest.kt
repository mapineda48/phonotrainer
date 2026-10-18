package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The licence sources of spec F-24 and architecture 20.6, read as the screen reads them: `bundled.json` of
 * `:core:designsystem` (the three font families and the Lucide set), the AboutLibraries report (a sample in the
 * report's shape), and the texts this module bundles for every licence the build allows.
 */
class LicencesTest {
  /** The Gradle unit-test working directory is the module's: android/feature/settings. */
  private val android = File("../..").canonicalFile
  private val texts = File("src/main/assets/licence-texts")
  private val bundledIndex = android.resolve("core/designsystem/src/main/assets/licenses/bundled.json")

  private fun sample(): String = requireNotNull(javaClass.classLoader?.getResource("report-sample.json")).readText()

  @Test
  fun bundledJsonListsTheThreeFontFamiliesAndTheIconSetWithTheirLicenceFiles() {
    val entries = Licences.bundled(bundledIndex.readText())

    assertThat(entries.map { it.name }).containsExactly("Atkinson Hyperlegible Next", "Newsreader", "Charis", "Lucide").inOrder()
    assertThat(entries.map { it.kind }).containsExactly(LicenceKind.FONT, LicenceKind.FONT, LicenceKind.FONT, LicenceKind.ICONS).inOrder()
    assertThat(entries.map { it.licenceLine }).containsExactly("OFL-1.1", "OFL-1.1", "OFL-1.1", "ISC AND MIT").inOrder()
    assertThat(entries.map { it.files }).containsExactly(1, 2, 2, null).inOrder()
    assertThat(entries.last().icons).isEqualTo(122)
    entries.forEach { entry ->
      val asset = entry.licences.single().asset!!
      assertThat(asset).startsWith(Licences.BUNDLED_DIR)
      val file = android.resolve("core/designsystem/src/main/assets").resolve(asset)
      assertWithMessage(entry.name).that(file.isFile).isTrue()
      assertThat(entry.id).isEqualTo("bundled:${entry.name}")
      assertThat(entry.holder).isNotEmpty()
      assertThat(entry.source).isNotEmpty()
    }
  }

  @Test
  fun theReportsLibrariesAreSortedByNameWithTheirVersionAuthorsAndLicences() {
    val libraries = Licences.libraries(sample())

    assertThat(libraries.map { it.name })
      .containsExactly("Activity", "OkHttp", "org.example:with-text", "Preferences External Protobuf").inOrder()
    val activity = libraries.first()
    assertThat(activity.id).isEqualTo("androidx.activity:activity")
    assertThat(activity.version).isEqualTo("1.13.0")
    assertThat(activity.holder).isEqualTo("The Android Open Source Project")
    assertThat(activity.source).isEqualTo("https://developer.android.com/jetpack/androidx/releases/activity#1.13.0")
    val apache = activity.licences.single()
    assertThat(apache.id).isEqualTo("Apache-2.0")
    assertThat(apache.name).isEqualTo("The Apache License, Version 2.0")
    assertThat(apache.asset).isEqualTo("licence-texts/Apache-2.0.txt")
    assertThat(apache.template).isFalse()

    // Without an organisation, the developers are the authors; without a website, the source is the repository.
    val okhttp = libraries.single { it.name == "OkHttp" }
    assertThat(okhttp.holder).isEqualTo("Square, Inc.")
    val protobuf = libraries.single { it.name == "Preferences External Protobuf" }
    assertThat(protobuf.source).isEqualTo("https://cs.android.com/androidx/platform/frameworks/support")
    assertThat(protobuf.licenceLine).isEqualTo("BSD-3-Clause")
    assertThat(protobuf.licences.single().template).isTrue()
  }

  @Test
  fun aLibraryWithoutANameShowsItsCoordinatesAndTheReportsOwnTextWins() {
    val unnamed = Licences.libraries(sample()).single { it.id == "org.example:with-text" }

    assertThat(unnamed.name).isEqualTo("org.example:with-text")
    val licence = unnamed.licences.single()
    assertThat(licence.id).isEqualTo("MIT")
    assertThat(licence.text).isEqualTo("Permission is hereby granted.\n\nThe software is provided as is.")
    // A text the report carries is the holder's own, never the template.
    assertThat(licence.template).isFalse()
  }

  @Test
  fun everyLicenceTheBuildAllowsHasItsTextBundled() {
    // The allowlist of the licence check (build-logic's LicenceCheck, architecture 20.6), read from its source so a new
    // allowed licence without a text here fails this test.
    val check = android.resolve("build-logic/convention/src/main/kotlin/com/mapineda48/phonotrainer/buildlogic/licences/LicenceCheck.kt")
    val line = check.readLines().single { "val LIBRARY_LICENCES" in it }
    val allowed = Regex("\"([^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
    assertThat(allowed).isNotEmpty()

    allowed.forEach { spdx ->
      val text = texts.resolve("$spdx.txt")
      assertWithMessage(spdx).that(text.isFile).isTrue()
      assertWithMessage(spdx).that(text.readText().length).isGreaterThan(400)
    }
  }

  @Test
  fun theAppsLicenceIsTheRepositorysLicenseFile() {
    val app = Licences.app("1.0.0")

    assertThat(app.licenceLine).isEqualTo("GPL-3.0-or-later")
    assertThat(app.source).isEqualTo("https://github.com/mapineda48/phenomena")
    assertThat(texts.resolve("GPL-3.0-or-later.txt").readText()).isEqualTo(android.resolve("../LICENSE").readText())
  }

  @Test
  fun aPlainTextLicenceReflowsIntoParagraphs() {
    val text = "   Apache License\n   Version 2.0\n\n  1. Definitions.\n\n     \"License\" shall mean the terms\n     and conditions.\n\n\n"

    assertThat(Licences.paragraphs(text))
      .containsExactly("Apache License Version 2.0", "1. Definitions.", "\"License\" shall mean the terms and conditions.").inOrder()
    assertThat(Licences.paragraphs(texts.resolve("Apache-2.0.txt").readText()).first()).isEqualTo("Apache License Version 2.0, January 2004 http://www.apache.org/licenses/")
  }
}
