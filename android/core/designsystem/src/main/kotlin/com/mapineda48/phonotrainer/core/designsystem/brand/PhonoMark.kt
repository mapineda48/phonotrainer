package com.mapineda48.phonotrainer.core.designsystem.brand

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.R
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * The stylus P (design 7.1), drawn from its master geometry (`design-src/svg/brand/mark-stylus-p.svg`, a 100 × 100
 * box): one stroke rises as the stem of a P, closes the bowl and leaves as a decaying wave ending in an ink blot. For
 * the masthead and the About screen; tinted at the place of use.
 */
object PhonoMark {
  /** The master stroke, width 10 with round caps and joins. */
  const val STROKE_PATH: String =
    "M38 52H62C76 52 82 44 82 35C82 25 75 18 62 18H38V78C38 84 41 87 45 85C50 82 51 72 56 72C61 72 61 84 66 84" +
      "C70 84 71 77 74 76"
  const val STROKE_WIDTH: Float = 10f

  /** The blot: a circle at (80, 75.4) of radius 7.2. */
  const val BLOT_PATH: String = "M87.2 75.4A7.2 7.2 0 1 1 72.8 75.4A7.2 7.2 0 1 1 87.2 75.4Z"

  val vector: ImageVector by lazy {
    ImageVector.Builder(
      name = "PhonoMark",
      defaultWidth = 48.dp,
      defaultHeight = 48.dp,
      viewportWidth = 100f,
      viewportHeight = 100f,
    ).addPath(
      pathData = PathParser().parsePathString(STROKE_PATH).toNodes(),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = STROKE_WIDTH,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    ).addPath(
      pathData = PathParser().parsePathString(BLOT_PATH).toNodes(),
      fill = SolidColor(Color.Black),
    ).build()
  }
}

/** The brand drawables of design 7.1 under their resource names (the frozen aliases live in `brand_aliases.xml`). */
object PhonoBrandArt {
  @DrawableRes val LauncherBackground: Int = R.drawable.ic_launcher_background
  @DrawableRes val LauncherForeground: Int = R.drawable.ic_launcher_foreground
  @DrawableRes val LauncherMonochrome: Int = R.drawable.ic_launcher_monochrome

  /** The notification's small icon: white on transparent, alpha only. */
  @DrawableRes val Notification: Int = R.drawable.ic_notification
  @DrawableRes val SplashMark: Int = R.drawable.splash_mark
  @DrawableRes val SplashMarkAnimated: Int = R.drawable.splash_mark_animated

  /** The page (window and splash background), light or dark through values-night. */
  @ColorRes val Page: Int = R.color.phono_page

  /** Ink (bone in dark): the splash mark, and the only accent a notification may set (design 12.3: never kraft). */
  @ColorRes val Ink: Int = R.color.phono_ink

  /** The launcher tile's kraft, the same in both themes. */
  @ColorRes val LauncherTile: Int = R.color.phono_launcher_tile
}

/** The stylus P in [color] (ink by default), [size] square. Decorative: the words beside it name the app. */
@Composable
fun PhonoMarkImage(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = Color.Unspecified) {
  Image(
    imageVector = PhonoMark.vector,
    contentDescription = null,
    colorFilter = ColorFilter.tint(if (color == Color.Unspecified) PhonoTheme.colors.ink else color),
    modifier = modifier.size(size),
  )
}

@PreviewLightDark
@Composable
internal fun PhonoMarkPreview() {
  PhonoPreviewSurface {
    Row {
      PhonoMarkImage(size = 64.dp)
      Image(painterResource(PhonoBrandArt.LauncherBackground), null, Modifier.size(72.dp))
      Image(painterResource(PhonoBrandArt.SplashMark), null, Modifier.size(72.dp))
    }
  }
}
