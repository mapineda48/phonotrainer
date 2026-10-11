package com.mapineda48.phonotrainer.core.designsystem.text

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.DesignSources
import com.mapineda48.phonotrainer.core.designsystem.component.GlyphRule
import java.awt.Font
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.BeforeClass
import org.junit.Test
import org.w3c.dom.Element

/**
 * The glyph rule of design 4.1, guarded for every module's strings: (1) no value holds "▾", "✓", "✕", a soft
 * hyphen, a micro sign or a thin space, or ends in " →" (controls draw those as icons); (2) every character of the UI
 * copy is in the UI face (Atkinson Hyperlegible Next), except the arrows, which `PhonoText` sets in Charis and must be
 * there, and IPA between slashes or brackets, which `IpaText` sets in Charis. Nothing is left to the system's fallback
 * font.
 */
class GlyphRuleTest {
  companion object {
    private val forbidden = mapOf('▾' to "▾", '✓' to "✓", '✕' to "✕", '­' to "soft hyphen",
      'µ' to "micro sign", ' ' to "thin space")

    private lateinit var atkinson: Font
    private lateinit var charis: Font
    private lateinit var newsreader: Font

    @JvmStatic
    @BeforeClass
    fun loadFonts() {
      System.setProperty("java.awt.headless", "true")
      fun load(name: String) = Font.createFont(Font.TRUETYPE_FONT, DesignSources.res.resolve("font/$name"))
      atkinson = load("atkinson_hyperlegible_next.ttf")
      charis = load("charis_regular.ttf")
      newsreader = load("newsreader.ttf")
    }
  }

  private data class Entry(val file: String, val name: String, val value: String)

  /** Every string and plural item of every module's `strings*.xml`, with its format arguments removed. */
  private fun allStrings(): List<Entry> {
    val files = DesignSources.android.walkTopDown()
      .onEnter { it.name != "build" && !it.name.startsWith(".") }
      .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" && it.parentFile.name.startsWith("values") }
      .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}res${File.separator}") }
      .toList()
    assertThat(files).isNotEmpty()
    return files.flatMap { file ->
      val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
      val nodes = doc.getElementsByTagName("*")
      (0 until nodes.length).map { nodes.item(it) as Element }
        .filter { it.tagName == "string" || it.tagName == "item" }
        .map { Entry(file.relativeTo(DesignSources.android).path, it.getAttribute("name"), it.textContent) }
    }
  }

  private fun visible(value: String): String = value
    .replace(Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfx%]"), "")
    .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", " ").replace("\\?", "?").replace("\\@", "@")

  /** [value] without its IPA segments (between slashes or brackets), which are set in Charis. */
  private fun withoutIpa(value: String): Pair<String, List<String>> {
    val ipa = Regex("""(?<![\p{L}\p{N}])/[^/\n]+?/(?![\p{L}\p{N}])|\[[^\]\n]+]""")
    val segments = ipa.findAll(value).map { it.value }.toList()
    return ipa.replace(value, " ") to segments
  }

  @Test
  fun noStringHoldsAGlyphThatAControlDrawsAsAnIcon() {
    val problems = allStrings().flatMap { e ->
      val v = visible(e.value)
      forbidden.filterKeys { it in v }.map { "${e.file} ${e.name}: ${it.value}" } +
        listOfNotNull(if (v.trimEnd().endsWith(" →")) "${e.file} ${e.name}: trailing arrow" else null)
    }
    assertThat(problems).isEmpty()
  }

  @Test
  fun everyCharacterOfTheUiCopyIsInTheBundledFaces() {
    val problems = mutableListOf<String>()
    for (e in allStrings()) {
      val (prose, ipa) = withoutIpa(visible(e.value))
      prose.codePoints().forEach { cp ->
        val ok = when {
          Character.isWhitespace(cp) -> true
          cp in 0x2190..0x2194 -> charis.canDisplay(cp)
          else -> atkinson.canDisplay(cp)
        }
        if (!ok) problems += "${e.file} ${e.name}: U+%04X '%s' is not in the UI face".format(cp, String(Character.toChars(cp)))
      }
      ipa.joinToString("").codePoints().forEach { cp ->
        if (!Character.isWhitespace(cp) && !charis.canDisplay(cp)) {
          problems += "${e.file} ${e.name}: U+%04X is not in Charis".format(cp)
        }
      }
    }
    assertThat(problems).isEmpty()
  }

  @Test
  fun theCharacterMapsAreTheOnesDesignFourOneDescribes() {
    // The UI and display faces have no arrows, checks, crosses or the menu triangle; Charis has the three arrows.
    for (cp in listOf(0x2190, 0x2192, 0x2194, 0x2713, 0x2715, 0x25BE)) {
      assertWithMessage("Atkinson U+%04X".format(cp)).that(atkinson.canDisplay(cp)).isFalse()
      assertWithMessage("Newsreader U+%04X".format(cp)).that(newsreader.canDisplay(cp)).isFalse()
    }
    for (cp in listOf(0x2190, 0x2192, 0x2194, 0x027E, 0x0283, 0x203F)) {
      assertWithMessage("Charis U+%04X".format(cp)).that(charis.canDisplay(cp)).isTrue()
    }
    assertThat(atkinson.canDisplay('“'.code)).isTrue()
    assertThat(atkinson.canDisplay('·'.code)).isTrue()
    assertThat(atkinson.canDisplay('…'.code)).isTrue()
  }

  @Test
  fun phonoTextSetsOnlyTheArrowsInCharis() {
    val annotated = GlyphRule.annotate("wanna ← want to")
    assertThat(annotated.text).isEqualTo("wanna ← want to")
    assertThat(annotated.spanStyles).hasSize(1)
    assertThat(annotated.spanStyles.single().start).isEqualTo(6)
    assertThat(annotated.spanStyles.single().end).isEqualTo(7)
    assertThat(GlyphRule.annotate("Play the word").spanStyles).isEmpty()
  }
}
