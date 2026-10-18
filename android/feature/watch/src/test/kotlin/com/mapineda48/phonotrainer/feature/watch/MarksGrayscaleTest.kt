package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.CaptionMode
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-07-AC10: in a grayscale rendering, a marked word's family is identifiable by its line style (and, with patterns,
 * its icon), never by hue alone. The same word is drawn once per family on the captions; each drawing is reduced to
 * its ink mask (what differs from the paper, whatever the hue), and every family's mask must differ from every other
 * family's. TalkBack reads each word's change by name (A-04). Run in both palettes and both themes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class MarksGrayscaleTest(private val variant: ScreenVariant) {
  @get:Rule
  val screen = PhonoScreenRule(UserSettings(captions = CaptionMode.MARKS))

  private val families = listOf(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY, Family.LEXICAL, Family.OTHER)
  private val job = WatchPreviewData.job

  private fun content(): ShortContent {
    val words = families.mapIndexed { i, family ->
      CaptionWord(
        ref = WordRef(job, 4, i),
        text = "water",
        startMs = 50_000L + i * 400,
        endMs = 50_000L + i * 400 + 300,
        family = family,
        changes = listOf("change ${family.name.lowercase()}"),
        advice = null,
        lowConfidence = false,
        stressed = false,
        linksToNext = false,
        dictIpa = null,
        realizedIpa = null,
        contraction = null,
        spotlit = false,
      )
    }
    return WatchPreviewData.content().copy(
      phrases = listOf(CaptionPhrase(4, 42_000, 56_000, words, badge = null)),
      spotlight = null,
    )
  }

  @Test
  fun everyFamilyHasItsOwnLineStyleInGrayscale() {
    screen.setContent(variant) {
      Captions(
        content = content(),
        playhead = remember { mutableLongStateOf(42_000) },
        mode = CaptionMode.MARKS,
        listenFirst = false,
        revealed = emptySet(),
        compact = true,
        onWord = {},
        onLoopPhrase = {},
        onShowWords = {},
        onShowCaptions = {},
        onExplain = {},
      )
    }
    val masks = families.indices.map { i -> mask(screen.compose.onNodeWithTag(WatchTags.word(4, i)).captureToImage()) }
    for (a in families.indices) {
      for (b in families.indices) {
        if (a >= b) continue
        val differing = masks[a].indices.count { masks[a][it] != masks[b][it] }
        assertWithMessage("pixels that tell ${families[a]} from ${families[b]}").that(differing).isGreaterThan(MIN_DIFFERENT_PIXELS)
      }
    }
    families.forEachIndexed { i, family ->
      screen.compose.onNode(
        hasTestTag(WatchTags.word(4, i)) and hasContentDescription("change ${family.name.lowercase()}", substring = true),
      ).assertExists()
    }
  }

  /** The ink mask of [image] in grayscale: pixels whose luminance differs from the paper's (its top-left corner). */
  private fun mask(image: ImageBitmap): BooleanArray {
    val pixels = image.toPixelMap()
    val paper = luminance(pixels[0, 0])
    val out = BooleanArray(MASK_W * MASK_H)
    for (y in 0 until MASK_H) {
      for (x in 0 until MASK_W) {
        val px = (x * pixels.width / MASK_W).coerceAtMost(pixels.width - 1)
        val py = (y * pixels.height / MASK_H).coerceAtMost(pixels.height - 1)
        out[y * MASK_W + x] = kotlin.math.abs(luminance(pixels[px, py]) - paper) > INK_THRESHOLD
      }
    }
    return out
  }

  private fun luminance(color: androidx.compose.ui.graphics.Color): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

  companion object {
    private const val MASK_W = 120
    private const val MASK_H = 60
    private const val INK_THRESHOLD = 0.12f
    private const val MIN_DIFFERENT_PIXELS = 12

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun variants(): List<Array<Any>> = listOf(
      ScreenVariant(),
      ScreenVariant(dark = true),
      ScreenVariant(colors = ColorChoice.COLOR_VISION_FRIENDLY),
      ScreenVariant(dark = true, colors = ColorChoice.COLOR_VISION_FRIENDLY),
    ).map { arrayOf<Any>(it) }
  }
}
