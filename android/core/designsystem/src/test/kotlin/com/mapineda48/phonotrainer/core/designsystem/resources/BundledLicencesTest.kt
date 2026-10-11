package com.mapineda48.phonotrainer.core.designsystem.resources

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.DesignSources
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * `assets/licenses/bundled.json` (architecture 20.6), the index the licence check and the licences screen read:
 * one entry per font family and one for the Lucide set, in the schema `verifyLicences` parses (integration decision
 * I-02); it agrees with `FONTS.txt` (the same SHA-256 as each `res/font/` file, which are all listed), with
 * `icons.json`'s version and count, every licence file it names exists, and `THIRD-PARTY-NOTICES.md` 7.3 has one row
 * per entry that `verifyLicences` reads as equal (Component, Version and an SPDX-only Licence cell).
 */
class BundledLicencesTest {
  private val bundled = Json.parseToJsonElement(DesignSources.licences.resolve("bundled.json").readText()).jsonObject
  private val components = bundled.getValue("components").jsonArray.map { it.jsonObject }

  private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** The keys `verifyLicences` reads (decision I-02); a missing one fails the build's licence check. */
  @Test
  fun everyEntryHasTheKeysTheLicenceCheckReads() {
    for (c in components) {
      assertWithMessage(c.toString()).that(c.keys).containsAtLeast("name", "version", "kind", "licence", "licenceFile", "source")
      when (c.str("kind")) {
        "font" -> for (file in c.getValue("files").jsonArray.map { it.jsonObject }) {
          assertWithMessage(file.toString()).that(file.keys).containsAtLeast("name", "sha256")
          assertThat(file.str("name")).endsWith(".ttf")
          assertThat(file.str("sha256")).matches("[0-9a-f]{64}")
        }
        else -> assertThat(c.getValue("count").jsonPrimitive.int).isGreaterThan(0)
      }
    }
  }

  @Test
  fun oneEntryPerFontFamilyAndOneForTheIconSet() {
    assertThat(bundled.getValue("schema").jsonPrimitive.int).isEqualTo(1)
    assertThat(components.map { it.str("name") to it.str("kind") }).containsExactly(
      "Atkinson Hyperlegible Next" to "font",
      "Newsreader" to "font",
      "Charis" to "font",
      "Lucide" to "icons",
    ).inOrder()
    for (c in components) {
      assertThat(c.str("licence")).isAnyOf("OFL-1.1", "ISC AND MIT")
      assertWithMessage(c.str("name")).that(DesignSources.licences.resolve(c.str("licenceFile")).isFile).isTrue()
      assertThat(c.str("source")).startsWith("https://")
      assertThat(c.str("copyright")).contains("Copyright")
    }
  }

  @Test
  fun fontFilesMatchFontsTxt() {
    val fontsTxt = DesignSources.licences.resolve("FONTS.txt").readText()
    val listed = components.filter { it.str("kind") == "font" }.flatMap { c ->
      c.getValue("files").jsonArray.map { it.jsonObject.str("name") to it.jsonObject.str("sha256") }
    }
    val fontFiles = DesignSources.filesUnder(DesignSources.res.resolve("font"))
    assertThat(listed.map { it.first }).containsExactlyElementsIn(fontFiles)
    for ((file, sha) in listed) {
      assertWithMessage(file).that(sha256(DesignSources.res.resolve("font/$file").readBytes())).isEqualTo(sha)
      assertWithMessage("FONTS.txt names $file with its SHA-256").that(fontsTxt).contains(sha)
      assertThat(fontsTxt).contains("\n$file\n")
    }
    val charis = components.single { it.str("name") == "Charis" }
    assertThat(charis.str("version")).isEqualTo("7.000")
    assertThat(charis.str("copyright")).contains("Reserved Font Names")
  }

  @Test
  fun theIconSetAgreesWithIconsJson() {
    val icons = Json.parseToJsonElement(DesignSources.iconsJson.readText()).jsonObject
    val lucide = components.single { it.str("kind") == "icons" }
    assertThat(lucide.str("version")).isEqualTo(icons.getValue("lucideVersion").jsonPrimitive.content)
    assertThat(lucide.getValue("count").jsonPrimitive.int).isEqualTo(icons.getValue("icons").jsonArray.size)
    assertThat(DesignSources.licences.resolve("lucide-LICENSE.txt").readText()).contains("MIT License")
  }

  /**
   * Notices 7.3 read the way `verifyLicences` reads it: the first table of the section, cells keyed by the lower-case
   * header with back-ticks stripped, rows matched on Component, the Version equal and the SPDX identifiers of the
   * Licence cell equal to those of bundled.json's licence (so the cell must hold the expression alone).
   */
  @Test
  fun theNoticesListEveryBundledComponentInSectionSevenThree() {
    val lines = DesignSources.notices.readLines()
    val start = lines.indexOf("### 7.3 Fonts and icons")
    assertWithMessage("THIRD-PARTY-NOTICES.md has the section 7.3 Fonts and icons").that(start).isAtLeast(0)
    val section = lines.drop(start + 1).takeWhile { !it.matches(Regex("#{1,6} .*")) }
    val table = section.map(String::trim).dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") }
    assertWithMessage("7.3 has a table").that(table.size).isAtLeast(2)
    val header = cells(table[0]).map { it.lowercase() }
    assertThat(header).containsAtLeast("component", "version", "licence", "licence file").inOrder()
    val rows = table.drop(2).map { header.zip(cells(it)).toMap() }
    assertThat(rows.map { it.getValue("component") }).containsExactlyElementsIn(components.map { it.str("name") }).inOrder()
    for (c in components) {
      val row = rows.single { it.getValue("component") == c.str("name") }
      assertWithMessage(c.str("name")).that(row.getValue("version")).isEqualTo(c.str("version"))
      assertWithMessage(c.str("name")).that(spdxIds(row.getValue("licence"))).isEqualTo(spdxIds(c.str("licence")))
      assertWithMessage(c.str("name")).that(row.getValue("licence file")).isEqualTo("assets/licenses/${c.str("licenceFile")}")
    }
  }

  @Test
  fun spdxIdsSplitsAnExpressionAsTheLicenceCheckDoes() {
    assertThat(spdxIds("ISC AND MIT")).containsExactly("ISC", "MIT")
    assertThat(spdxIds("(Apache-2.0 OR MIT)")).containsExactly("Apache-2.0", "MIT")
    // The old 7.3 cell form, a licence followed by its file, is not a bare expression and must not pass.
    assertThat(spdxIds("OFL-1.1 (assets/licenses/charis-OFL.txt)")).isNotEqualTo(spdxIds("OFL-1.1"))
  }

  /** A copy of `LicenceCheck.spdxIds` (build-logic), which the module's tests cannot depend on. */
  private fun spdxIds(expression: String): Set<String> =
    expression.split(' ', ',', '(', ')').map(String::trim)
      .filter { it.isNotEmpty() && it.uppercase() !in setOf("AND", "OR", "WITH") }
      .toSortedSet()

  /** A copy of `LicenceCheck.cells`: the cells of a Markdown table row, trimmed, with back-ticks stripped. */
  private fun cells(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim().replace("`", "") }
}
