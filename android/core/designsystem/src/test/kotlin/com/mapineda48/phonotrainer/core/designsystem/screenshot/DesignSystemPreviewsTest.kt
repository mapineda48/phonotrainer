package com.mapineda48.phonotrainer.core.designsystem.screenshot

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The catalog of the screenshot tests is the module's previews, all of them, and each draws in light and in dark: the
 * sheets the design track signs off (work-plan WP-03) are written to `build/outputs/previews/designsystem-NN.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PreviewScreenshots.SHEET_WINDOW)
class DesignSystemPreviewsTest {
  @get:Rule
  val screen = PhonoScreenRule()

  @Test
  fun theCatalogHoldsEveryPreviewOfTheModule() {
    assertThat(DesignSystemCatalog.map { it.name })
      .containsExactlyElementsIn(PreviewSources.previewFunctions(File("src/main/kotlin")))
    assertThat(DesignSystemCatalog.map { it.name }).containsNoDuplicates()
  }

  @Test
  fun everyGoldenBelongsToAPreviewAndAVariant() {
    assertThat(PreviewSources.orphanGoldens(DesignSystemCatalog)).isEmpty()
  }

  @Test
  fun everyPreviewDrawsInLightAndInDark() {
    val shots = PreviewScreenshots.sheets(screen, "designsystem", DesignSystemCatalog)
    shots.forEach { (name, images) ->
      val (light, dark) = images
      assertWithMessage("$name: light and dark draw the same image").that(light.sameAs(dark)).isFalse()
    }
  }
}
