package com.mapineda48.phonotrainer.core.designsystem.resources

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.DesignSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher, notification and vector rules of design 7.1 and 12.3, read from the converted XML: the adaptive icon
 * has its background, foreground and monochrome layers; the notification icon and the monochrome layer are one colour
 * (alpha only); icons are black, tinted at runtime; every `pathData` is under lint's 3,000-character `VectorPath`
 * limit (whitespace runs count once) and every intrinsic size within its 200 dp `VectorRaster` limit.
 */
class VectorResourcesTest {
  private val android = "http://schemas.android.com/apk/res/android"

  private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(file).documentElement

  private fun Element.descendants(): List<Element> {
    val out = ArrayList<Element>()
    val nodes = getElementsByTagName("*")
    for (i in 0 until nodes.length) out += nodes.item(i) as Element
    return out
  }

  private fun colorsOf(root: Element): Set<String> = (listOf(root) + root.descendants()).flatMap { el ->
    listOf("fillColor", "strokeColor").mapNotNull { el.getAttributeNS(android, it).takeIf(String::isNotEmpty) }
  }.toSet()

  private fun res(path: String) = DesignSources.res.resolve(path)

  @Test
  fun theAdaptiveIconHasBackgroundForegroundAndMonochromeLayers() {
    for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
      val root = parse(res("mipmap-anydpi/$name"))
      assertThat(root.tagName).isEqualTo("adaptive-icon")
      val layers = root.descendants().associate { it.tagName to it.getAttributeNS(android, "drawable") }
      assertThat(layers).containsExactly(
        "background", "@drawable/ic_launcher_background",
        "foreground", "@drawable/ic_launcher_foreground",
        "monochrome", "@drawable/ic_launcher_monochrome",
      )
      for (drawable in layers.values) assertThat(res("drawable/${drawable.removePrefix("@drawable/")}.xml").isFile).isTrue()
    }
    for (layer in listOf("background", "foreground", "monochrome")) {
      val root = parse(res("drawable/ic_launcher_$layer.xml"))
      assertThat(root.getAttributeNS(android, "width")).isEqualTo("108dp")
      assertThat(root.getAttributeNS(android, "viewportWidth")).isEqualTo("108")
    }
  }

  @Test
  fun theNotificationIconAndTheMonochromeLayerAreOneColour() {
    val notification = parse(res("drawable/ic_notification.xml"))
    assertThat(notification.tagName).isEqualTo("vector")
    assertThat(notification.getAttributeNS(android, "width")).isEqualTo("24dp")
    assertThat(colorsOf(notification)).containsExactly("#FFFFFFFF")
    assertThat(notification.descendants().none { it.tagName == "gradient" }).isTrue()
    val monochrome = parse(res("drawable/ic_launcher_monochrome.xml"))
    assertThat(colorsOf(monochrome)).hasSize(1)
    // The frozen alias points at it (work-plan 4.1).
    assertThat(res("values/brand_aliases.xml").readText()).contains("<drawable name=\"ic_stat_phono\">@drawable/ic_notification</drawable>")
  }

  @Test
  fun iconsAreBlackAndTintedAtRuntime() {
    val icons = DesignSources.filesUnder(res("drawable"))
      .filter { it.startsWith("ic_") && !it.startsWith("ic_launcher") && it != "ic_notification.xml" }
    assertThat(icons).hasSize(122)
    for (icon in icons) {
      val root = parse(res("drawable/$icon"))
      assertWithMessage(icon).that(root.getAttributeNS(android, "width")).isEqualTo("24dp")
      assertWithMessage(icon).that(colorsOf(root)).containsExactly("#FF000000")
    }
  }

  @Test
  fun everyVectorIsWithinLintsVectorPathAndVectorRasterLimits() {
    var paths = 0
    var longest = 0
    for (folder in listOf("drawable", "drawable-night")) {
      for (name in DesignSources.filesUnder(res(folder))) {
        val root = parse(res("$folder/$name"))
        for (el in listOf(root) + root.descendants()) {
          if (el.tagName == "vector") {
            for (attr in listOf("width", "height")) {
              val dp = el.getAttributeNS(android, attr).removeSuffix("dp").toFloat()
              assertWithMessage("$folder/$name $attr").that(dp).isAtMost(200f)
            }
          }
          val data = el.getAttributeNS(android, "pathData")
          if (data.isNotEmpty()) {
            val length = data.replace(Regex("\\s+"), " ").length
            paths += 1
            longest = maxOf(longest, length)
            assertWithMessage("$folder/$name pathData length").that(length).isLessThan(3000)
          }
        }
      }
    }
    // design 12.3 / tools/lint_vectors.py: 1,037 paths, the longest 2,976 characters.
    assertThat(paths).isEqualTo(1037)
    assertThat(longest).isEqualTo(2976)
  }

  @Test
  fun theAnimatedSplashDrawsTheStrokeThenTheBlotOnce() {
    val text = res("drawable/splash_mark_animated.xml").readText()
    assertThat(text).contains("<animated-vector")
    assertThat(text).contains("android:name=\"stylus_stroke\"")
    assertThat(text).contains("android:name=\"stylus_blot\"")
    assertThat(text).contains("trimPathEnd")
    assertThat(text).doesNotContain("repeatCount=\"-1\"")
    assertThat(text).doesNotContain("repeatCount=\"infinite\"")
  }
}
