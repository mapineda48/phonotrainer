package com.mapineda48.phonotrainer.core.designsystem.resources

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.DesignSources
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import org.junit.Test

/**
 * The conversion's inventory (work-plan WP-03): the staging export holds exactly the 173 files of the plan, every one
 * of them is in this module under the same name with the same bytes, every `icons.json` entry is converted, and the
 * module holds nothing the export lacks (no legacy raster mipmaps, no `-v26` qualifier).
 */
class ConvertedResourcesTest {
  private val exportFiles = DesignSources.filesUnder(DesignSources.export)

  /** Files of the module the conversion does not take from the export: written by hand or generated from it. */
  private val ownFiles = setOf(
    "res/values/themes.xml",
    "res/values-night/themes.xml",
    "res/values-v31/themes.xml",
    "assets/licenses/bundled.json",
  )

  private val brandDrawables = setOf("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome", "ic_notification")

  private fun iconsJsonNames(): List<String> =
    Regex("\"res\"\\s*:\\s*\"(ic_[a-z0-9_]+)\"").findAll(DesignSources.iconsJson.readText()).map { it.groupValues[1] }.toList()

  @Test
  fun theExportHoldsTheOneHundredSeventyThreeFilesOfThePlan() {
    assertThat(exportFiles).hasSize(173)
    val byFolder = exportFiles.groupingBy { it.substringBeforeLast('/') }.eachCount()
    assertThat(byFolder).containsExactly(
      "res/font", 5,
      "assets/licenses", 5,
      "res/drawable", 142,
      "res/drawable-night", 16,
      "res/mipmap-anydpi", 2,
      "res/values", 2,
      "res/values-night", 1,
    )
    assertThat(exportFiles.filter { it.startsWith("res/font/") }).containsExactly(
      "res/font/atkinson_hyperlegible_next.ttf", "res/font/newsreader.ttf", "res/font/newsreader_italic.ttf",
      "res/font/charis_regular.ttf", "res/font/charis_bold.ttf",
    )
    assertThat(exportFiles.filter { it.startsWith("assets/") }).containsExactly(
      "assets/licenses/atkinson-hyperlegible-next-OFL.txt", "assets/licenses/newsreader-OFL.txt",
      "assets/licenses/charis-OFL.txt", "assets/licenses/lucide-LICENSE.txt", "assets/licenses/FONTS.txt",
    )
  }

  @Test
  fun everyStagedFileIsInTheModuleWithTheSameBytes() {
    for (path in exportFiles) {
      val converted = DesignSources.main.resolve(path)
      assertWithMessage("$path is converted").that(converted.isFile).isTrue()
      assertWithMessage("$path has the staged bytes")
        .that(converted.readBytes().contentEquals(DesignSources.export.resolve(path).readBytes())).isTrue()
    }
  }

  @Test
  fun theModuleHoldsNothingTheExportLacks() {
    val resources = DesignSources.filesUnder(DesignSources.main).filter { it.startsWith("res/") || it.startsWith("assets/") }
    val extra = resources.toSet() - exportFiles - ownFiles
    // Strings are the module's own (it has none today); everything else must come from the export.
    assertThat(extra.filterNot { it.matches(Regex("res/values[^/]*/strings[^/]*\\.xml")) }).isEmpty()
    val folders = resources.map { it.substringBeforeLast('/') }.toSet()
    assertThat(folders.filter { it.startsWith("res/mipmap") }).containsExactly("res/mipmap-anydpi")
    assertThat(folders.filter { it.endsWith("-v26") }).isEmpty()
  }

  @Test
  fun everyIconsJsonEntryIsConvertedAndNothingElse() {
    val listed = iconsJsonNames()
    assertThat(listed).hasSize(122)
    assertThat(listed).containsNoDuplicates()
    val drawables = DesignSources.filesUnder(DesignSources.res.resolve("drawable")).map { it.removeSuffix(".xml") }
    assertThat(drawables.filter { it.startsWith("ic_") }.toSet()).containsExactlyElementsIn(listed.toSet() + brandDrawables)
    // PhonoIcons names every entry, in the order of icons.json.
    assertThat(PhonoIcons.all.keys.toList()).containsExactlyElementsIn(listed).inOrder()
  }

  @Test
  fun everyIllustrationAndSplashHasItsDarkTwin() {
    val night = DesignSources.filesUnder(DesignSources.res.resolve("drawable-night"))
    val illustrations = DesignSources.filesUnder(DesignSources.res.resolve("drawable")).filter { it.startsWith("ill_") }
    assertThat(illustrations).hasSize(14)
    assertThat(night).containsExactlyElementsIn(illustrations + listOf("splash_mark.xml", "splash_mark_animated.xml"))
    assertThat(PhonoIllustrations.all.keys).containsExactlyElementsIn(illustrations.map { it.removeSuffix(".xml") })
  }
}
