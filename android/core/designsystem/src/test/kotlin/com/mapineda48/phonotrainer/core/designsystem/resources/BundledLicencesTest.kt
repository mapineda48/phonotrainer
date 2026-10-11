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
 * one entry per font family and one for the Lucide set; it agrees with `FONTS.txt` (the same SHA-256 as each
 * `res/font/` file, which are all listed), with `icons.json`'s version and count, every licence file it names exists,
 * and `THIRD-PARTY-NOTICES.md` 7.3 has one row per entry.
 */
class BundledLicencesTest {
  private val bundled = Json.parseToJsonElement(DesignSources.licences.resolve("bundled.json").readText()).jsonObject
  private val components = bundled.getValue("components").jsonArray.map { it.jsonObject }

  private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
      c.getValue("files").jsonArray.map { it.jsonObject.str("file") to it.jsonObject.str("sha256") }
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

  @Test
  fun theNoticesListEveryBundledComponentInSectionSevenThree() {
    val lines = DesignSources.notices.readLines()
    val start = lines.indexOf("### 7.3 Fonts and icons")
    assertWithMessage("THIRD-PARTY-NOTICES.md has the section 7.3 Fonts and icons").that(start).isAtLeast(0)
    val section = lines.drop(start + 1).takeWhile { !it.matches(Regex("#{1,3} .*")) }
    val rows = section.filter { it.startsWith("| ") && !it.startsWith("| Component") }
    assertThat(rows).hasSize(components.size)
    for (c in components) {
      assertWithMessage(c.str("name")).that(rows.any { it.startsWith("| ${c.str("name")} | ${c.str("version")} |") }).isTrue()
    }
  }
}
