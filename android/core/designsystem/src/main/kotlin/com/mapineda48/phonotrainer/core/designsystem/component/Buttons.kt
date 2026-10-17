package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** The emphasis of a button (design 9.1). Selection and state are never hue: ink fill, ring or wash. */
enum class ButtonEmphasis {
  /** Ink fill, onInk label, a 3 dp letterpress bite at the bottom. One per view. */
  PRIMARY,

  /** Sheet fill with a 1.5 dp ring: the default. */
  SECONDARY,

  /** Galley fill ("Next change"). */
  TONAL,

  /** Text only, a 48 dp target. */
  QUIET,

  /** Sheet fill, a 1.5 dp critical ring and an ink label with the trash icon ("Remove"); never red text or fill. */
  DANGER,
}

/** Small and medium buttons are 48 dp tall, large ones 56 dp. */
enum class ButtonSize { MEDIUM, LARGE }

private val buttonShape = RoundedCornerShape(PhonoRadius.medium)
private val biteHeight = 3.dp

private fun PhonoColors.fillOf(emphasis: ButtonEmphasis, pressed: Boolean): Color = when (emphasis) {
  ButtonEmphasis.PRIMARY -> if (pressed) ink2 else ink
  ButtonEmphasis.SECONDARY, ButtonEmphasis.DANGER -> if (pressed) deepSheet else sheet
  ButtonEmphasis.TONAL -> if (pressed) deepSheet else galley
  ButtonEmphasis.QUIET -> if (pressed) galley else Color.Transparent
}

private fun PhonoColors.ringOf(emphasis: ButtonEmphasis): Color? = when (emphasis) {
  ButtonEmphasis.SECONDARY -> lineStrong
  ButtonEmphasis.DANGER -> critical
  else -> null
}

/**
 * The button of design 9.1. The label is the accessible name; icons are decorative (`contentDescription = null`): a
 * trailing arrow ("All clips →") is [trailingIcon] = `PhonoIcons.ArrowRight`, never a text arrow (design 4.1).
 * [busy] keeps the label and draws a static hairline under it (no spinner without words).
 */
@Composable
fun PhonoBaseButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  emphasis: ButtonEmphasis = ButtonEmphasis.SECONDARY,
  buttonSize: ButtonSize = ButtonSize.MEDIUM,
  enabled: Boolean = true,
  busy: Boolean = false,
  @DrawableRes leadingIcon: Int? = if (emphasis == ButtonEmphasis.DANGER) PhonoIcons.Remove else null,
  @DrawableRes trailingIcon: Int? = null,
  fillWidth: Boolean = false,
) {
  val colors = PhonoTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val fill = colors.fillOf(emphasis, pressed && enabled)
  val ring = colors.ringOf(emphasis)
  val content = if (emphasis == ButtonEmphasis.PRIMARY) colors.onInk else colors.ink
  val bite = emphasis == ButtonEmphasis.PRIMARY && !pressed
  Box(
    modifier = modifier
      .focusRing(buttonShape)
      .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
      .widthIn(min = PhonoSizes.minTarget)
      .heightIn(min = if (buttonSize == ButtonSize.LARGE) PhonoSizes.buttonHeightLarge else PhonoSizes.buttonHeight)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .offset { IntOffset(0, if (pressed && emphasis == ButtonEmphasis.PRIMARY) 1.dp.roundToPx() else 0) }
      .clip(buttonShape)
      .background(fill)
      .then(if (ring != null) Modifier.border(PhonoSizes.ring, ring, buttonShape) else Modifier)
      .drawWithContent {
        drawContent()
        if (bite) {
          val h = biteHeight.toPx()
          drawRect(Color.Black.copy(alpha = BITE_ALPHA), Offset(0f, size.height - h), Size(size.width, h))
        }
      }
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = if (emphasis == ButtonEmphasis.QUIET) PhonoSpacing.s12 else PhonoSpacing.s20),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = PhonoSpacing.s12),
      ) {
        leadingIcon?.let {
          Icon(painterResource(it), contentDescription = null, tint = content, modifier = Modifier.size(PhonoSizes.iconButton))
        }
        PhonoText(
          text = text,
          style = MaterialTheme.typography.labelLarge,
          color = content,
          textAlign = TextAlign.Center,
        )
        trailingIcon?.let {
          Icon(painterResource(it), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
      }
      if (busy) {
        Box(Modifier.padding(bottom = PhonoSpacing.s6).width(32.dp).height(PhonoSizes.hairline).background(content))
      }
    }
  }
}

private const val BITE_ALPHA = 0.28f

/** A text link ("Learn more →"): a quiet, underlined action with an optional trailing arrow icon (design 4.1). */
@Composable
fun LinkButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailingArrow: Boolean = false,
  enabled: Boolean = true,
) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .focusRing(buttonShape)
      .heightIn(min = PhonoSizes.minTarget)
      .widthIn(min = PhonoSizes.minTarget)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(buttonShape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    PhonoText(
      text = text,
      style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
      color = colors.ink,
    )
    if (trailingArrow) {
      Icon(
        painterResource(PhonoIcons.ArrowRight),
        contentDescription = null,
        tint = colors.ink,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/** A button that opens a menu ("Mix ▾"): the words, then a decorative chevron (the role says it opens a menu). */
@Composable
fun MenuButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = null,
  secondaryText: String? = null,
) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .focusRing(buttonShape)
      .heightIn(min = PhonoSizes.minTarget)
      .clip(buttonShape)
      .clickable(role = Role.DropdownList, onClick = onClick)
      .padding(horizontal = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    icon?.let { Icon(painterResource(it), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton)) }
    PhonoText(text, style = MaterialTheme.typography.labelLarge, color = colors.ink)
    secondaryText?.let { PhonoText(it, style = MaterialTheme.typography.labelLarge, color = colors.ink2) }
    Icon(painterResource(PhonoIcons.ChevronDown), null, tint = colors.ink, modifier = Modifier.size(18.dp))
  }
}

@PreviewLightDark
@Composable
internal fun ButtonsPreview() {
  PhonoPreviewSurface {
    PhonoBaseButton("Start", {}, emphasis = ButtonEmphasis.PRIMARY, buttonSize = ButtonSize.LARGE, fillWidth = true)
    PhonoBaseButton("All clips", {}, emphasis = ButtonEmphasis.SECONDARY, trailingIcon = PhonoIcons.ArrowRight)
    PhonoBaseButton("Next change", {}, emphasis = ButtonEmphasis.TONAL)
    PhonoBaseButton("Not now", {}, emphasis = ButtonEmphasis.QUIET)
    PhonoBaseButton("Remove", {}, emphasis = ButtonEmphasis.DANGER)
    PhonoBaseButton("Sending", {}, emphasis = ButtonEmphasis.PRIMARY, busy = true)
    PhonoBaseButton("Disabled", {}, enabled = false)
    LinkButton("Learn more", {}, trailingArrow = true)
    MenuButton("Mix", {}, icon = PhonoIcons.ModeMix, secondaryText = "· Today's set")
  }
}
