package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * An icon button (design 9.1): a 48 dp circular target with a 20 to 24 dp icon. The label is required: it is the
 * content description, since the icon alone is never a cue. Pressed: a galley circle. [toggled] (a two-state
 * control, through [onToggle]) fills the circle with ink.
 */
@Composable
fun PhonoIconButton(
  @DrawableRes icon: Int,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconSize: Dp = PhonoSizes.iconBar,
  tint: Color = Color.Unspecified,
) {
  val colors = PhonoTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  Box(
    modifier = modifier
      .focusRing(CircleShape)
      .size(PhonoSizes.minTarget)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(CircleShape)
      .background(if (pressed && enabled) colors.galley else Color.Transparent)
      .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(icon),
      contentDescription = null,
      tint = if (tint == Color.Unspecified) colors.ink else tint,
      modifier = Modifier.size(iconSize),
    )
  }
}

/** A two-state icon button ("Save" / "Saved"): toggled = an ink-filled circle with an onInk icon. */
@Composable
fun PhonoIconToggle(
  @DrawableRes icon: Int,
  contentDescription: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconSize: Dp = PhonoSizes.iconBar,
) {
  val colors = PhonoTheme.colors
  Box(
    modifier = modifier
      .focusRing(CircleShape)
      .size(PhonoSizes.minTarget)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(CircleShape)
      .background(if (checked) colors.ink else Color.Transparent)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(icon),
      contentDescription = null,
      tint = if (checked) colors.onInk else colors.ink,
      modifier = Modifier.size(iconSize),
    )
  }
}

@PreviewLightDark
@Composable
private fun IconButtonsPreview() {
  PhonoPreviewSurface {
    Row {
      PhonoIconButton(PhonoIcons.Back, "Back", {})
      PhonoIconButton(PhonoIcons.MoreVertical, "More actions", {})
      PhonoIconButton(PhonoIcons.Help, "What's this: schwa?", {}, iconSize = PhonoSizes.iconButton)
      PhonoIconToggle(PhonoIcons.Save, "Save", checked = false, onCheckedChange = {})
      PhonoIconToggle(PhonoIcons.Saved, "Saved", checked = true, onCheckedChange = {})
      PhonoIconButton(PhonoIcons.Close, "Close", {}, enabled = false)
    }
  }
}
