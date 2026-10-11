package com.mapineda48.phonotrainer.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The radii of design 5.1. */
object PhonoRadius {
  /** Slip top corners, inner fills. */
  val extraSmall: Dp = 4.dp

  /** The playing-word fill. */
  val word: Dp = 6.dp

  /** Posters, thumbnails, word highlight. */
  val small: Dp = 8.dp

  /** Type sorts. */
  val sort: Dp = 10.dp

  /** Buttons, fields, segmented controls, wells, notices, icon wells, snackbars, coach marks. */
  val medium: Dp = 12.dp

  /** Cards, slips (bottom corners), the sound card. */
  val large: Dp = 14.dp

  /** Bottom sheets (top corners), dialogs. */
  val extraLarge: Dp = 28.dp
}

/** Material's shape roles (design 5.1); `full` (pills, chips, badges) is `CircleShape`. */
val phonoShapes: Shapes = Shapes(
  extraSmall = RoundedCornerShape(PhonoRadius.extraSmall),
  small = RoundedCornerShape(PhonoRadius.small),
  medium = RoundedCornerShape(PhonoRadius.medium),
  large = RoundedCornerShape(PhonoRadius.large),
  extraLarge = RoundedCornerShape(PhonoRadius.extraLarge),
)

/** A bottom sheet's outline: 28 dp top corners, square bottom. */
val SheetShape: Shape = RoundedCornerShape(topStart = PhonoRadius.extraLarge, topEnd = PhonoRadius.extraLarge)

/**
 * The slip (design 1.3, 5.1): a ticket torn from the set. Its top edge is perforated (half-holes of [holeDiameter]
 * every [pitch], centred on the edge), its top corners are 4 dp and its bottom corners [bottomRadius].
 */
class PerforatedTopShape(
  private val holeDiameter: Dp = 7.dp,
  private val pitch: Dp = 12.dp,
  private val topRadius: Dp = PhonoRadius.extraSmall,
  private val bottomRadius: Dp = PhonoRadius.large,
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val top = with(density) { topRadius.toPx() }
    val bottom = with(density) { bottomRadius.toPx() }
    val card = Path().apply {
      addRoundRect(
        RoundRect(
          rect = Rect(0f, 0f, size.width, size.height),
          topLeft = CornerRadius(top),
          topRight = CornerRadius(top),
          bottomRight = CornerRadius(bottom),
          bottomLeft = CornerRadius(bottom),
        ),
      )
    }
    val hole = with(density) { holeDiameter.toPx() }
    val step = with(density) { pitch.toPx() }
    if (hole <= 0f || step <= hole || size.width <= 2 * (top + step)) return Outline.Generic(card)
    val holes = Path()
    // Holes from one corner to the other, centred so both ends keep the same margin.
    val usable = size.width - 2 * top
    val count = (usable / step).toInt()
    val start = top + (usable - (count - 1) * step) / 2f
    for (i in 0 until count) {
      val cx = start + i * step
      holes.addOval(Rect(cx - hole / 2f, -hole / 2f, cx + hole / 2f, hole / 2f))
    }
    return Outline.Generic(Path.combine(PathOperation.Difference, card, holes))
  }

  override fun equals(other: Any?): Boolean = other is PerforatedTopShape && other.holeDiameter == holeDiameter &&
    other.pitch == pitch && other.topRadius == topRadius && other.bottomRadius == bottomRadius

  override fun hashCode(): Int = listOf(holeDiameter, pitch, topRadius, bottomRadius).hashCode()
}

/** A type sort of Compare (design 1.3, 5.1): a 10 dp rounded block; the nick under its base is drawn by `TypeSort`. */
val SortShape: Shape = RoundedCornerShape(PhonoRadius.sort)
