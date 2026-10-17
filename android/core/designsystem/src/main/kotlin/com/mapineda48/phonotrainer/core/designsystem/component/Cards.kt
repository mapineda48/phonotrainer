package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** Variants of a card (design 9.1). */
enum class CardVariant {
  PLAIN,

  /** Two sheets behind it, offset and slightly rotated: hero cards (Today's set, the summary, the question). */
  STACK,

  /** Crop marks outside its corners. */
  CROP_MARKS,

  /** A 4 dp ink bar at the start: failures and the banner. */
  ALERT,
}

/**
 * Paper depth (design 5.3): in light, a hairline ring and a soft ink drop; in dark, no shadow, a ring ([floating]
 * uses lineStrong, as dialogs and coach marks do).
 */
fun Modifier.paperDepth(colors: PhonoColors, shape: Shape, floating: Boolean = false): Modifier =
  if (colors.isDark) {
    border(PhonoSizes.hairline, if (floating) colors.lineStrong else colors.hairline, shape)
  } else {
    shadow(
      elevation = if (floating) 10.dp else 6.dp,
      shape = shape,
      ambientColor = Color.Transparent,
      spotColor = colors.ink.copy(alpha = if (floating) 0.45f else 0.38f),
    ).border(PhonoSizes.hairline, colors.hairline, shape)
  }

/**
 * A card of the app (design 9.1): a sheet of paper on the page, 14 dp corners, padding 14 to 16 dp, with the depth of
 * design 5.3. [onClick] makes the whole card one target.
 */
@Composable
fun PaperCard(
  modifier: Modifier = Modifier,
  variant: CardVariant = CardVariant.PLAIN,
  onClick: (() -> Unit)? = null,
  onClickLabel: String? = null,
  contentPadding: Dp = PhonoSpacing.cardPadding,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.large)
  Box(
    modifier = modifier
      .then(if (variant == CardVariant.STACK) Modifier.padding(end = 11.dp, bottom = 11.dp) else Modifier)
      .then(if (variant == CardVariant.CROP_MARKS) Modifier.padding(7.dp).cropMarks(colors.ink) else Modifier),
  ) {
    if (variant == CardVariant.STACK) {
      StackSheet(Modifier.matchParentSize().offset(9.dp, 11.dp), 1.1f, shape)
      StackSheet(Modifier.matchParentSize().offset(5.dp, 6.dp), 0.6f, shape)
    }
    val clickable = if (onClick != null) {
      Modifier.focusRing(shape).clip(shape).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
      Modifier
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(clickable)
        .paperDepth(colors, shape)
        .clip(shape)
        .background(colors.sheet)
        .height(IntrinsicSize.Min),
    ) {
      if (variant == CardVariant.ALERT) Box(Modifier.width(4.dp).fillMaxHeight().background(colors.ink))
      Column(
        modifier = Modifier.weight(1f).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
        content = content,
      )
    }
  }
}

@Composable
private fun StackSheet(modifier: Modifier, degrees: Float, shape: Shape) {
  val colors = PhonoTheme.colors
  Box(modifier.rotate(degrees).paperDepth(colors, shape).clip(shape).background(colors.sheet))
}

/** Crop marks (design 5.1): 14 dp corner ticks of 1 dp ink at 55 %, 7 dp outside the card. Decorative. */
fun Modifier.cropMarks(ink: Color): Modifier = drawBehind {
  val gap = 7.dp.toPx()
  val len = 14.dp.toPx()
  val stroke = 1.dp.toPx()
  val c = ink.copy(alpha = 0.55f)
  val corners = listOf(Offset(0f, 0f) to Offset(-1f, -1f), Offset(size.width, 0f) to Offset(1f, -1f),
    Offset(0f, size.height) to Offset(-1f, 1f), Offset(size.width, size.height) to Offset(1f, 1f))
  for ((corner, dir) in corners) {
    // A horizontal tick beside the corner and a vertical one above or below it, both outside the card.
    drawLine(c, Offset(corner.x + dir.x * gap, corner.y), Offset(corner.x + dir.x * (gap + len), corner.y), stroke)
    drawLine(c, Offset(corner.x, corner.y + dir.y * gap), Offset(corner.x, corner.y + dir.y * (gap + len)), stroke)
  }
}

/**
 * A kicker or group head (design 9.1): small caps words (labelSmall, upper-cased here), an optional italic Newsreader
 * [count], and a 1 dp lineStrong rule to the end. It is a heading for TalkBack.
 */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, count: String? = null, @DrawableRes icon: Int? = null) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    icon?.let { Icon(painterResource(it), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline)) }
    PhonoText(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.ink)
    count?.let { PhonoText(it, style = PhonoTheme.type.accentItalic, color = colors.ink2) }
    Box(Modifier.weight(1f).height(1.dp).background(colors.lineStrong))
  }
}

/** The double rule (design 5.4): 2 dp ink, a 3 dp gap, 1 dp ink; under mastheads and tab-root titles. Decorative. */
@Composable
fun DoubleRule(modifier: Modifier = Modifier) {
  val ink = PhonoTheme.colors.ink
  Canvas(modifier.fillMaxWidth().height(6.dp)) {
    val thick = 2.dp.toPx()
    val gap = 3.dp.toPx()
    val thin = 1.dp.toPx()
    drawRect(ink, size = androidx.compose.ui.geometry.Size(size.width, thick))
    drawRect(ink, topLeft = Offset(0f, thick + gap), size = androidx.compose.ui.geometry.Size(size.width, thin))
  }
}

/** A round icon well (design 9.17, 9.25): an ink fill with an onInk icon when [filled], an ink ring otherwise. */
@Composable
fun IconWell(@DrawableRes icon: Int, modifier: Modifier = Modifier, filled: Boolean = false, size: Dp = 44.dp) {
  val colors = PhonoTheme.colors
  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .background(if (filled) colors.ink else colors.sheet)
      .then(if (filled) Modifier else Modifier.border(PhonoSizes.ring, colors.ink, CircleShape)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(icon), null, tint = if (filled) colors.onInk else colors.ink, modifier = Modifier.size(size / 2))
  }
}

@PreviewLightDark
@Composable
internal fun CardsPreview() {
  PhonoPreviewSurface {
    Kicker("Listen for", count = "3 of 8")
    PaperCard { PhonoText("A plain card", style = MaterialTheme.typography.titleLarge) }
    PaperCard(variant = CardVariant.STACK) { PhonoText("Today's set", style = MaterialTheme.typography.titleLarge) }
    PaperCard(variant = CardVariant.CROP_MARKS) { PhonoText("Summary", style = MaterialTheme.typography.titleLarge) }
    PaperCard(variant = CardVariant.ALERT, onClick = {}) {
      PhonoText("Needs your attention", style = MaterialTheme.typography.titleLarge)
    }
    DoubleRule()
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      IconWell(PhonoIcons.ConnConnected, filled = true)
      IconWell(PhonoIcons.ConnUnreachable)
    }
  }
}
