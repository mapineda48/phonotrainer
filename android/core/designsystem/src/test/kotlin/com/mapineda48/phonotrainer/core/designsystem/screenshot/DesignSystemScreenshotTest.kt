package com.mapineda48.phonotrainer.core.designsystem.screenshot

import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshot tests of design 11 for `:core:designsystem`: each component preview at font scale 1.0, 1.3 and 2.0,
 * light and dark, Standard and colour-vision-friendly, against its goldens ([PreviewScreenshots]), with the
 * accessibility audit at every variant.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PreviewScreenshots.WINDOW)
class DesignSystemScreenshotTest(private val preview: CatalogPreview) {
  @get:Rule
  val screen = PhonoScreenRule()

  @Test
  fun matchesItsGoldensAtEveryVariant() = PreviewScreenshots.verify(screen, preview)

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun previews(): List<Array<Any>> = DesignSystemCatalog.map { arrayOf(it) }
  }
}
