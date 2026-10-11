package com.mapineda48.phonotrainer.buildlogic.licences

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The licence check of architecture 20.6 passes on a consistent tree and fails on each planted fault of work-plan
 * WP-19's proof: a library missing from 7.2, a 7.2 row with no library, a font file missing from `bundled.json`, a
 * wrong font SHA-256, a 7.3 row missing, a licence text missing; plus the version, licence and allowlist rules.
 */
class LicenceCheckTest {
  @get:Rule
  val temporary = TemporaryFolder()

  // -----------------------------------------------------------------------------------------------------------------
  // Part (a): runtime libraries

  @Test
  fun aConsistentReportPasses() {
    assertThat(LicenceCheck.checkLibraries(LicenceCheck.parseReport(report()), notices())).isEmpty()
  }

  @Test
  fun aMissingLibraryFailsWithItsRow() {
    val problems = LicenceCheck.checkLibraries(
      LicenceCheck.parseReport(report()),
      notices(libraries = listOf(ACTIVITY_ROW)),
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("missing `com.squareup.okio:okio` 3.16.4")
    assertThat(problems.single()).contains("| Okio | `com.squareup.okio:okio` | 3.16.4 | Apache-2.0 |")
  }

  @Test
  fun aRowWithNoLibraryFails() {
    val problems = LicenceCheck.checkLibraries(
      LicenceCheck.parseReport(report()),
      notices(libraries = listOf(ACTIVITY_ROW, OKIO_ROW, "| Gone | `com.example:gone` | 1.0 | MIT |")),
    )

    assertThat(problems).containsExactly(
      "Notices 7.2 lists `com.example:gone`, which is not on the release runtime classpath; remove its row",
    )
  }

  @Test
  fun aDifferentVersionOrLicenceFails() {
    val problems = LicenceCheck.checkLibraries(
      LicenceCheck.parseReport(report()),
      notices(libraries = listOf(ACTIVITY_ROW.replace("1.13.0", "1.12.0"), OKIO_ROW.replace("Apache-2.0", "MIT"))),
    )

    assertThat(problems).hasSize(2)
    assertThat(problems[0]).contains("version 1.12.0, the report 1.13.0")
    assertThat(problems[1]).contains("the licence MIT, the report Apache-2.0")
  }

  @Test
  fun aLicenceOutsideTheAllowlistFailsEvenWhenListed() {
    val problems = LicenceCheck.checkLibraries(
      LicenceCheck.parseReport(report(okioLicence = "GPL-2.0-only")),
      notices(libraries = listOf(ACTIVITY_ROW, OKIO_ROW.replace("Apache-2.0", "GPL-2.0-only"))),
    )

    assertThat(problems.joinToString()).contains("GPL-2.0-only, which is not allowlisted")
  }

  @Test
  fun licenceKeysResolveToTheirSpdxIdAndAnExampleTableInACodeBlockIsIgnored() {
    val libraries = LicenceCheck.parseReport(report())
    val section = LicenceCheck.section(notices(fenced = true), LicenceCheck.LIBRARIES_HEADING)!!

    assertThat(libraries.map { it.licences }).containsExactly(setOf("Apache-2.0"), setOf("Apache-2.0"))
    assertThat(LicenceCheck.table(section).map { it["coordinates"] })
      .containsExactly("androidx.activity:activity", "com.squareup.okio:okio")
  }

  @Test
  fun aMissingSectionFails() {
    assertThat(LicenceCheck.checkLibraries(emptyList(), "# Notices\n")).containsExactly(
      "THIRD-PARTY-NOTICES.md has no section \"7.2 Runtime libraries\"",
    )
  }

  // -----------------------------------------------------------------------------------------------------------------
  // Part (b): bundled fonts and icons

  @Test
  fun aConsistentBundledIndexPasses() {
    val fonts = fonts()

    assertThat(check(bundled(fonts), fonts = fonts)).isEmpty()
  }

  @Test
  fun aFontFileMissingFromBundledJsonFails() {
    val fonts = fonts()
    val extra = File(fonts.first().parentFile, "extra_font.ttf").apply { writeBytes(byteArrayOf(9, 9)) }

    assertThat(check(bundled(fonts), fonts = fonts + extra)).containsExactly(
      "Font file extra_font.ttf ships in res/font/ but bundled.json does not list it",
    )
  }

  @Test
  fun aWrongFontSha256Fails() {
    val fonts = fonts()
    val index = bundled(fonts).replace(LicenceCheck.sha256(fonts.first()), "0".repeat(64))

    assertThat(check(index, fonts = fonts)).containsExactly(
      "The SHA-256 of charis_regular.ttf differs from bundled.json (`Charis`)",
    )
  }

  @Test
  fun aListedFontFileThatDoesNotShipFails() {
    val fonts = fonts()

    assertThat(check(bundled(fonts), fonts = fonts.drop(1))).containsExactly(
      "bundled.json lists the font file charis_regular.ttf of `Charis`, which is not in res/font/",
    )
  }

  @Test
  fun aMissing73RowFails() {
    val fonts = fonts()

    assertThat(check(bundled(fonts), rows = listOf(LUCIDE_ROW), fonts = fonts)).containsExactly(
      "Notices 7.3 is missing `Charis` 7.000 (OFL-1.1)",
    )
  }

  @Test
  fun a73RowWithNoComponentFails() {
    val fonts = fonts()
    val rows = listOf(CHARIS_ROW, LUCIDE_ROW, "| Other | 1.0 | font | x.ttf | OFL-1.1 | other-OFL.txt | https://example.org |")

    assertThat(check(bundled(fonts), rows = rows, fonts = fonts)).containsExactly(
      "Notices 7.3 lists `Other`, which bundled.json does not name",
    )
  }

  @Test
  fun aMissingLicenceTextFails() {
    val fonts = fonts()

    assertThat(check(bundled(fonts), licenceFiles = setOf("lucide-LICENSE.txt", "bundled.json"), fonts = fonts))
      .containsExactly("The licence text charis-OFL.txt of `Charis` is missing from assets/licenses/")
  }

  @Test
  fun aBundledLicenceOutsideOflIscMitFails() {
    val fonts = fonts()
    val index = bundled(fonts).replace("\"licence\": \"OFL-1.1\"", "\"licence\": \"GPL-3.0-only\"")

    assertThat(check(index, rows = listOf(CHARIS_ROW.replace("OFL-1.1", "GPL-3.0-only"), LUCIDE_ROW), fonts = fonts))
      .containsExactly("bundled.json: `Charis` has the licence GPL-3.0-only (allowed: OFL-1.1, ISC, MIT)")
  }

  @Test
  fun beforeBundledJsonExistsNothingMayBeListedOrShipped() {
    assertThat(LicenceCheck.checkBundled(null, notices(bundled = emptyList()), emptySet(), emptyList())).isEmpty()
    assertThat(LicenceCheck.checkBundled(null, notices(bundled = emptyList()), emptySet(), fonts()))
      .containsExactly(
        "Font file charis_bold.ttf ships, but bundled.json does not exist",
        "Font file charis_regular.ttf ships, but bundled.json does not exist",
      )
    assertThat(LicenceCheck.checkBundled(null, notices(), emptySet(), emptyList()))
      .containsExactly(
        "Notices 7.3 lists `Charis`, but bundled.json does not exist",
        "Notices 7.3 lists `Lucide`, but bundled.json does not exist",
      )
  }

  @Test
  fun spdxExpressionsSplitIntoTheirIdentifiers() {
    assertThat(LicenceCheck.spdxIds("ISC AND MIT")).containsExactly("ISC", "MIT")
    assertThat(LicenceCheck.spdxIds("(Apache-2.0 OR MIT)")).containsExactly("Apache-2.0", "MIT")
    assertThat(LicenceCheck.spdxIds("Apache-2.0, BSD-3-Clause")).containsExactly("Apache-2.0", "BSD-3-Clause")
  }

  // -----------------------------------------------------------------------------------------------------------------

  private fun check(
    index: String,
    rows: List<String> = listOf(CHARIS_ROW, LUCIDE_ROW),
    licenceFiles: Set<String> = setOf("charis-OFL.txt", "lucide-LICENSE.txt", "bundled.json"),
    fonts: List<File>,
  ): List<String> = LicenceCheck.checkBundled(LicenceCheck.parseBundled(index), notices(bundled = rows), licenceFiles, fonts)

  /** Two "font" files with known bytes, and an XML font family that the check must ignore. */
  private fun fonts(): List<File> {
    val dir = temporary.newFolder()
    return listOf(
      File(dir, "charis_regular.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) },
      File(dir, "charis_bold.ttf").apply { writeBytes(byteArrayOf(4, 5, 6)) },
      File(dir, "charis.xml").apply { writeText("<font-family/>") },
    )
  }

  private fun bundled(fonts: List<File>): String = """
    {
      "schema": 1,
      "components": [
        {
          "name": "Charis", "version": "7.000", "kind": "font", "licence": "OFL-1.1",
          "licenceFile": "charis-OFL.txt", "source": "https://software.sil.org/charis/",
          "files": [
            {"name": "charis_regular.ttf", "sha256": "${LicenceCheck.sha256(fonts[0])}"},
            {"name": "charis_bold.ttf", "sha256": "${LicenceCheck.sha256(fonts[1])}"}
          ]
        },
        {
          "name": "Lucide", "version": "1.51.0", "kind": "icons", "licence": "ISC AND MIT",
          "licenceFile": "lucide-LICENSE.txt", "source": "https://lucide.dev", "count": 122
        }
      ]
    }
  """.trimIndent()

  private fun notices(
    libraries: List<String> = listOf(ACTIVITY_ROW, OKIO_ROW),
    bundled: List<String> = listOf(CHARIS_ROW, LUCIDE_ROW),
    fenced: Boolean = false,
  ): String = buildString {
    appendLine("# Third-party notices")
    appendLine()
    appendLine("## 7. Android client")
    appendLine()
    appendLine("### 7.1 Overview")
    appendLine()
    if (fenced) {
      appendLine("```")
      appendLine("| Library | Coordinates | Version | Licence |")
      appendLine("|---|---|---|---|")
      appendLine("| Example | `com.example:example` | 1.0 | MIT |")
      appendLine("```")
    }
    appendLine("### 7.2 Runtime libraries")
    appendLine()
    if (fenced) {
      appendLine("```")
      appendLine("| Library | Coordinates | Version | Licence |")
      appendLine("|---|---|---|---|")
      appendLine("| Example | `com.example:example` | 1.0 | MIT |")
      appendLine("```")
      appendLine()
    }
    appendLine("| Library | Coordinates | Version | Licence |")
    appendLine("|---|---|---|---|")
    libraries.forEach(::appendLine)
    appendLine()
    appendLine("### 7.3 Fonts and icons")
    appendLine()
    if (bundled.isNotEmpty()) {
      appendLine("| Component | Version | Kind | Files | Licence | Licence file | Source |")
      appendLine("|---|---|---|---|---|---|---|")
      bundled.forEach(::appendLine)
    }
    appendLine()
    appendLine("### 7.4 Build tools")
    appendLine()
    appendLine("| Tool | Version | Licence |")
    appendLine("|---|---|---|")
    appendLine("| AboutLibraries | 15.2.0 | Apache-2.0 |")
  }

  private companion object {
    const val ACTIVITY_ROW = "| Activity | `androidx.activity:activity` | 1.13.0 | Apache-2.0 |"
    const val OKIO_ROW = "| Okio | `com.squareup.okio:okio` | 3.16.4 | Apache-2.0 |"
    const val CHARIS_ROW = "| Charis | 7.000 | font | 2 files | OFL-1.1 | `charis-OFL.txt` | https://software.sil.org/charis/ |"
    const val LUCIDE_ROW = "| Lucide | 1.51.0 | icons | 122 icons | ISC AND MIT | `lucide-LICENSE.txt` | https://lucide.dev |"

    /** The shape of AboutLibraries' `aboutlibraries.json` (keys of `licenses` resolve to an SPDX id). */
    fun report(okioLicence: String = "Apache-2.0"): String = """
      {
        "metadata": {"generated": "2026-10-10"},
        "licenses": {
          "Apache-2.0": {"name": "Apache License 2.0", "spdxId": "Apache-2.0", "hash": "Apache-2.0"},
          "asl_2_0_hash": {"name": "The Apache Software License, Version 2.0", "spdxId": "Apache-2.0", "hash": "asl_2_0_hash"}
        },
        "libraries": [
          {
            "uniqueId": "androidx.activity:activity", "artifactVersion": "1.13.0", "name": "Activity",
            "licenses": ["asl_2_0_hash"]
          },
          {
            "uniqueId": "com.squareup.okio:okio", "artifactVersion": "3.16.4", "name": "Okio",
            "licenses": ["$okioLicence"]
          }
        ]
      }
    """.trimIndent()
  }
}
