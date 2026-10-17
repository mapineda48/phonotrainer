package com.mapineda48.phonotrainer.core.ui.screenshot

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.screenshot.PreviewScreenshots
import com.mapineda48.phonotrainer.core.designsystem.screenshot.PreviewSources
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The catalog of the screenshot tests is every preview of the `:core:ui` primitives, and each draws in light and in
 * dark: the sheets the design track signs off (work-plan WP-03) are written to `build/outputs/previews/ui-NN.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PreviewScreenshots.SHEET_WINDOW)
class UiPreviewsTest {
  @get:Rule
  val screen = PhonoScreenRule()

  @Test
  fun theCatalogHoldsEveryPreviewOfThePrimitives() {
    assertThat(UiCatalog.map { it.name })
      .containsExactlyElementsIn(PreviewSources.previewFunctions(File("src/main/kotlin"), excluded = OtherOwners))
    assertThat(UiCatalog.map { it.name }).containsNoDuplicates()
  }

  @Test
  fun everyGoldenBelongsToAPreviewAndAVariant() {
    assertThat(PreviewSources.orphanGoldens(UiCatalog)).isEmpty()
  }

  @Test
  fun everyPreviewDrawsInLightAndInDark() {
    val shots = PreviewScreenshots.sheets(screen, "ui", UiCatalog)
    shots.forEach { (name, images) ->
      val (light, dark) = images
      assertWithMessage("$name: light and dark draw the same image").that(light.sameAs(dark)).isFalse()
    }
  }
}
