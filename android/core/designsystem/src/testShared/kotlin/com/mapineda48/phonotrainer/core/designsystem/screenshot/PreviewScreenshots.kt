package com.mapineda48.phonotrainer.core.designsystem.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * One component preview of a module's catalog (design 12.5: every component has light and dark previews): the name
 * of its `@Preview` function and the function itself. A preview of a whole screen ([screen], one that fills and
 * scrolls the space it is given) is drawn on a phone's screen, 412 × 915 dp like the mockups (design 14); any other
 * at its own height.
 */
class CatalogPreview(val name: String, val screen: Boolean = false, val content: @Composable () -> Unit) {
  override fun toString(): String = name
}

/**
 * The screenshot tests of design 11 ("Checks WP-03 adds") over a module's preview catalog, shared by the two modules
 * of the design system (`:core:designsystem`, `:core:ui`; this directory is a test source root of both).
 *
 * Each preview is drawn inside the app root at every variant of [ScreenVariant.MATRIX] (font scale 1.0, 1.3 and 2.0 ×
 * light and dark × Standard and colour-vision-friendly palettes; the previews' `PhonoPreviewSurface` follows the
 * root's palette) on Robolectric's native graphics, and compared with its golden image under
 * `src/test/screenshots/<preview>/<variant>.png` of the module. A golden is recorded or refreshed with
 * `./gradlew :core:<module>:testDebugUnitTest -Pphono.screenshots=record` and reviewed like code; a run in the default
 * mode never writes one. When an image differs, the run writes the new image and a diff (changed pixels in red over a
 * faded golden) under `build/outputs/screenshots/` of the module.
 *
 * Goldens are drawn at mdpi (one pixel per dp, `@Config(qualifiers = WINDOW)`) on a 412 dp wide window, the width of
 * the mockups (design 14); the window is tall enough for every preview at font scale 2.0, and a preview that would
 * reach its bottom fails rather than being cut.
 */
object PreviewScreenshots {
  /** The Robolectric window of the screenshot tests: a phone's width, a pixel per dp, room for the tallest preview. */
  const val WINDOW = "w412dp-h5000dp-mdpi"

  /** The window of the design sign-off sheets: the same width at two pixels per dp, like the mockups. */
  const val SHEET_WINDOW = "w412dp-h3000dp-xhdpi"

  /** Where a module's goldens live, relative to the module (the test task's working directory). */
  val GOLDENS = File("src/test/screenshots")

  private val OUTPUTS = File("build/outputs/screenshots")
  private const val SHOT = "screenshot.preview"

  /** A channel may differ by this much (anti-aliasing noise) before the pixel counts as changed. */
  private const val CHANNEL_TOLERANCE = 24

  /** Changed pixels a comparison tolerates: about 0.005 % of the image, at least 8. One changed glyph is more. */
  private fun allowedChanges(pixels: Int): Int = max(8, pixels / 20_000)

  /** Whether this run records goldens (`-Pphono.screenshots=record`) instead of comparing with them. */
  val recording: Boolean
    get() = System.getProperty("phono.screenshots") == "record"

  /** The golden's file name of [variant]: `1.3-dark-cvd.png`. */
  fun fileName(variant: ScreenVariant): String = buildString {
    append(String.format(Locale.ROOT, "%.1f", variant.fontScale))
    append(if (variant.dark) "-dark" else "-light")
    append(if (variant.colors == ColorChoice.COLOR_VISION_FRIENDLY) "-cvd" else "-standard")
    append(".png")
  }

  /**
   * The preview as the tests draw it: across the window's width (its page colour too), at its own height measured
   * without the window's limit, or at a phone screen's height for a [CatalogPreview.screen].
   */
  @Composable
  fun Shot(preview: CatalogPreview) {
    val height = when {
      preview.screen -> Modifier.height(SCREEN_HEIGHT)
      else -> Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
    }
    Box(Modifier.fillMaxWidth().then(height).testTag(SHOT), propagateMinConstraints = true) { preview.content() }
  }

  private val SCREEN_HEIGHT = 915.dp

  /** What is on screen now, as drawn; fails when the preview comes near the bottom of the window. */
  fun capture(screen: PhonoScreenRule): Bitmap {
    val window = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.heightPixels
    val shot = screen.compose.onNodeWithTag(SHOT)
    val height = shot.fetchSemanticsNode().size.height
    check(height < window * 9 / 10) { "The preview is $height px tall in a $window px window; make the window taller" }
    return shot.captureToImage().asAndroidBitmap()
  }

  /**
   * Shows [preview] at every one of [variants] (with the accessibility audit the harness runs after each) and compares
   * each image with its golden, or records it. Every difference is reported at once, each with its variant.
   */
  fun verify(screen: PhonoScreenRule, preview: CatalogPreview, variants: List<ScreenVariant> = ScreenVariant.MATRIX) {
    val problems = mutableListOf<String>()
    screen.checkEach(variants = variants, content = { Shot(preview) }) { variant ->
      val golden = File(File(GOLDENS, preview.name), fileName(variant))
      compare(capture(screen), golden, "${preview.name}/${fileName(variant)}")?.let { problems += "$variant: $it" }
    }
    check(problems.isEmpty()) {
      "${problems.size} of ${variants.size} screenshots of ${preview.name} differ from their goldens " +
        "(record with -Pphono.screenshots=record after reviewing build/outputs/screenshots/):\n" +
        problems.joinToString("\n") { "- $it" }
    }
  }

  /** Null when [actual] matches [golden] (or was recorded there), else what differs. */
  private fun compare(actual: Bitmap, golden: File, name: String): String? {
    val expected = if (golden.isFile) BitmapFactory.decodeFile(golden.path) else null
    val changed = expected?.let { changedPixels(actual, it) }
    if (recording) {
      // An image within tolerance keeps its golden, so a record run rewrites only what really changed.
      if (changed == null || changed > allowedChanges(actual.width * actual.height)) write(actual, golden)
      return null
    }
    val problem = when {
      expected == null -> "no golden $golden"
      changed == null -> "size ${actual.width} × ${actual.height} px, golden ${expected.width} × ${expected.height} px"
      changed > allowedChanges(actual.width * actual.height) -> "$changed pixels changed"
      else -> return null
    }
    val stem = name.removeSuffix(".png")
    write(actual, File(OUTPUTS, "$stem.actual.png"))
    if (expected != null && changed != null) write(diff(actual, expected), File(OUTPUTS, "$stem.diff.png"))
    return problem
  }

  /** Pixels whose colour differs beyond [CHANNEL_TOLERANCE] in any channel; null when the sizes differ. */
  private fun changedPixels(a: Bitmap, b: Bitmap): Int? {
    if (a.width != b.width || a.height != b.height) return null
    val pa = pixels(a)
    val pb = pixels(b)
    return pa.indices.count { differs(pa[it], pb[it]) }
  }

  private fun differs(x: Int, y: Int): Boolean =
    (0..24 step 8).any { shift -> abs((x ushr shift and 0xFF) - (y ushr shift and 0xFF)) > CHANNEL_TOLERANCE }

  private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
  }

  private fun diff(actual: Bitmap, expected: Bitmap): Bitmap {
    val pa = pixels(actual)
    val pe = pixels(expected)
    val out = IntArray(pa.size) { i ->
      if (differs(pa[i], pe[i])) Color.RED else blend(pe[i])
    }
    return Bitmap.createBitmap(out, actual.width, actual.height, Bitmap.Config.ARGB_8888)
  }

  /** [color] faded three quarters of the way to white. */
  private fun blend(color: Int): Int {
    fun fade(c: Int) = 255 - (255 - c) / 4
    return Color.rgb(fade(Color.red(color)), fade(Color.green(color)), fade(Color.blue(color)))
  }

  fun write(bitmap: Bitmap, file: File) {
    file.parentFile?.mkdirs()
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not write $file" } }
  }

  /**
   * The design sign-off sheets (work-plan WP-03 "Done when"): every preview of [catalog] drawn at font scale 1.0 in
   * light and in dark (Standard palette, as the IDE shows a `@PreviewLightDark` preview), side by side under its name,
   * on pages of `build/outputs/previews/<module>-NN.png`. Returns each preview's light and dark images.
   */
  fun sheets(
    screen: PhonoScreenRule,
    module: String,
    catalog: List<CatalogPreview>,
  ): Map<String, Pair<Bitmap, Bitmap>> {
    val light = ScreenVariant()
    val dark = ScreenVariant(dark = true)
    val shown = mutableIntStateOf(0)
    screen.setContent(light) { Shot(catalog[shown.intValue]) }
    val shots = LinkedHashMap<String, Pair<Bitmap, Bitmap>>()
    catalog.forEachIndexed { index, preview ->
      shown.intValue = index
      screen.show(light)
      val l = capture(screen)
      screen.show(dark)
      val d = capture(screen)
      shots[preview.name] = l to d
    }
    val out = File("build/outputs/previews")
    out.listFiles { file -> file.name.startsWith("$module-") }?.forEach { it.delete() }
    SheetLayout(module).pages(shots).forEachIndexed { i, page ->
      write(page, File(out, "$module-${String.format(Locale.ROOT, "%02d", i + 1)}.png"))
    }
    return shots
  }
}

/** Light and dark images side by side under each preview's name, on pages of a bounded height. */
private class SheetLayout(private val module: String) {
  private val gap = 32
  private val label = 56
  private val maxHeight = 3600

  private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 30f
    color = Color.rgb(0x1C, 0x1A, 0x17)
  }
  private val frame = Paint().apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
    color = Color.rgb(0x9A, 0x93, 0x88)
  }

  fun pages(shots: Map<String, Pair<Bitmap, Bitmap>>): List<Bitmap> {
    val width = gap * 3 + 2 * shots.values.maxOf { (l, d) -> max(l.width, d.width) }
    val rows = shots.map { (name, images) ->
      Triple(name, images, label + max(images.first.height, images.second.height) + gap)
    }
    val pages = mutableListOf<List<Triple<String, Pair<Bitmap, Bitmap>, Int>>>()
    var current = mutableListOf<Triple<String, Pair<Bitmap, Bitmap>, Int>>()
    var height = label + gap
    for (row in rows) {
      if (current.isNotEmpty() && height + row.third > maxHeight) {
        pages += current
        current = mutableListOf()
        height = label + gap
      }
      current += row
      height += row.third
    }
    if (current.isNotEmpty()) pages += current
    return pages.mapIndexed { index, page ->
      draw(page, width, "$module · ${index + 1} of ${pages.size} · light | dark")
    }
  }

  private fun draw(rows: List<Triple<String, Pair<Bitmap, Bitmap>, Int>>, width: Int, heading: String): Bitmap {
    val height = label + gap + rows.sumOf { it.third }
    val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(page)
    canvas.drawColor(Color.rgb(0xE9, 0xE4, 0xDA))
    canvas.drawText(heading, gap.toFloat(), label - 16f, title)
    var y = label + gap
    for ((name, images, rowHeight) in rows) {
      canvas.drawText(name, gap.toFloat(), y + label - 18f, title)
      val (light, dark) = images
      val top = (y + label).toFloat()
      canvas.drawBitmap(light, gap.toFloat(), top, null)
      canvas.drawRect(gap.toFloat(), top, (gap + light.width).toFloat(), top + light.height, frame)
      val x = (gap * 2 + light.width).toFloat()
      canvas.drawBitmap(dark, x, top, null)
      canvas.drawRect(x, top, x + dark.width, top + dark.height, frame)
      y += rowHeight
    }
    return page
  }
}

/** The `@Preview` functions of a module's sources, so a catalog cannot silently miss one. */
object PreviewSources {
  private val PREVIEW = Regex(
    """@Preview\w*(?:\([^)]*\))?\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public)\s+)?fun\s+(\w+)\s*\(""",
  )

  /** The preview functions under [root] (`src/main/kotlin`), but in files whose path contains one of [excluded]. */
  fun previewFunctions(root: File, excluded: List<String> = emptyList()): Set<String> =
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }
      .filterNot { file -> excluded.any { it in file.invariantSeparatorsPath } }
      .flatMap { file -> PREVIEW.findAll(file.readText()).map { it.groupValues[1] } }
      .toSortedSet()

  /** Golden files that belong to no preview of [catalog] or to no variant of [ScreenVariant.MATRIX]. */
  fun orphanGoldens(catalog: List<CatalogPreview>): List<String> {
    val expected = catalog.flatMap { preview ->
      ScreenVariant.MATRIX.map { "${preview.name}/${PreviewScreenshots.fileName(it)}" }
    }.toSet()
    val goldens = PreviewScreenshots.GOLDENS
    if (!goldens.isDirectory) return emptyList()
    return goldens.walkTopDown().filter { it.isFile }.map { it.relativeTo(goldens).invariantSeparatorsPath }
      .filterNot { it in expected }.sorted().toList()
  }
}
