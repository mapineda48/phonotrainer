package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/**
 * A text field (design 9.1): 56 dp, sheet fill, a 1.5 dp outline ring, a leading icon and an inkMuted placeholder.
 * Focused: a 2.5 dp ink ring. Invalid: a 2 dp critical ring with a critical icon and the [error] message in ink below
 * ("That doesn't look like a link."); never red text. [label] is the accessible name.
 */
@Composable
fun PhonoTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  @DrawableRes leadingIcon: Int? = null,
  error: String? = null,
  enabled: Boolean = true,
  singleLine: Boolean = true,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  val colors = PhonoTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val (ringWidth, ringColor) = when {
    error != null -> 2.dp to colors.critical
    focused -> 2.5.dp to colors.ink
    else -> PhonoSizes.ring to colors.outline
  }
  val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink)
  Column(modifier.alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = singleLine,
      textStyle = textStyle,
      cursorBrush = SolidColor(colors.ink),
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      interactionSource = interaction,
      modifier = Modifier
        .fillMaxWidth()
        .semantics {
          contentDescription = label
          if (error != null) error(error)
        },
      decorationBox = { inner ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhonoSizes.fieldHeight)
            .background(colors.sheet, shape)
            .border(ringWidth, ringColor, shape)
            .padding(horizontal = PhonoSpacing.s14, vertical = PhonoSpacing.s12),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
        ) {
          leadingIcon?.let { Icon(painterResource(it), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconButton)) }
          Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
              PhonoText(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
            }
            inner()
          }
          if (error != null) {
            Icon(painterResource(PhonoIcons.StatusAttention), null, tint = colors.critical, modifier = Modifier.size(PhonoSizes.iconButton))
          }
        }
      },
    )
    if (error != null) {
      PhonoText(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
    }
  }
}

@PreviewLightDark
@Composable
internal fun TextFieldPreview() {
  PhonoPreviewSurface {
    PhonoTextField("", {}, label = "Link", placeholder = "Paste a YouTube or X link", leadingIcon = PhonoIcons.Link)
    PhonoTextField("https://example.org/watch", {}, label = "Link", leadingIcon = PhonoIcons.Link)
    PhonoTextField("not a link", {}, label = "Link", leadingIcon = PhonoIcons.Link, error = "That doesn't look like a link.")
  }
}
