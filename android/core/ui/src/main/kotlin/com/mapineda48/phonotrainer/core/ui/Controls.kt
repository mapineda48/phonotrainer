package com.mapineda48.phonotrainer.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.ButtonEmphasis
import com.mapineda48.phonotrainer.core.designsystem.component.ButtonSize
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBaseButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SheetHandle
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.SheetShape

/**
 * Button emphasis (design 9.1): the primary action (ink fill, one per view), a secondary one (the default ring), a
 * tonal one (galley, "Next change"), a quiet text action, and the danger outline ("Remove": critical ring, ink words,
 * never red text or fill).
 */
enum class PhonoButtonKind { PRIMARY, SECONDARY, QUIET, TONAL, DANGER }

/**
 * The app's button (design 9.1): at least 48 dp (56 dp when [large]), a visible focus ring, 45 % when disabled. Icons
 * are decorative; a trailing arrow is [trailingIcon] = `PhonoIcons.ArrowRight`, never a text arrow (design 4.1).
 */
@Composable
fun PhonoButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  kind: PhonoButtonKind = PhonoButtonKind.PRIMARY,
  enabled: Boolean = true,
  @DrawableRes leadingIcon: Int? = null,
  @DrawableRes trailingIcon: Int? = null,
  large: Boolean = false,
  busy: Boolean = false,
  fillWidth: Boolean = false,
) {
  val emphasis = when (kind) {
    PhonoButtonKind.PRIMARY -> ButtonEmphasis.PRIMARY
    PhonoButtonKind.SECONDARY -> ButtonEmphasis.SECONDARY
    PhonoButtonKind.QUIET -> ButtonEmphasis.QUIET
    PhonoButtonKind.TONAL -> ButtonEmphasis.TONAL
    PhonoButtonKind.DANGER -> ButtonEmphasis.DANGER
  }
  PhonoBaseButton(
    text = text,
    onClick = onClick,
    modifier = modifier,
    emphasis = emphasis,
    buttonSize = if (large) ButtonSize.LARGE else ButtonSize.MEDIUM,
    enabled = enabled,
    busy = busy,
    leadingIcon = leadingIcon ?: if (kind == PhonoButtonKind.DANGER) PhonoIcons.Remove else null,
    trailingIcon = trailingIcon,
    fillWidth = fillWidth,
  )
}

/**
 * A card of the app (design 9.1): a sheet of paper on the page with its depth; [variant] adds the stack, crop marks or
 * the alert bar; [onClick] makes the whole card one target.
 */
@Composable
fun PhonoCard(
  modifier: Modifier = Modifier,
  variant: CardVariant = CardVariant.PLAIN,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  PaperCard(modifier = modifier, variant = variant, onClick = onClick, content = content)
}

/**
 * A modal bottom sheet of the app (design 9.9, 9.12): sheet paper with 28 dp top corners and its handle, the scrim of
 * the theme (46 % / 62 %), no tonal tint. [title] is announced as the pane's title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonoSheet(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = PhonoTheme.colors
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier.then(if (title != null) Modifier.semantics { paneTitle = title } else Modifier),
    shape = SheetShape,
    containerColor = colors.sheet,
    contentColor = colors.ink,
    tonalElevation = 0.dp,
    scrimColor = colors.scrim,
    dragHandle = { SheetHandle() },
  ) {
    Column(
      modifier = Modifier.padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s8),
      content = content,
    )
  }
}

@PreviewLightDark
@Composable
internal fun ControlsPreview() {
  PhonoPreviewSurface {
    PhonoCard {
      PhonoText("Today's set", style = MaterialTheme.typography.titleLarge)
      PhonoButton(text = "Start", onClick = {}, large = true, fillWidth = true)
      PhonoButton(text = "From the start", onClick = {}, kind = PhonoButtonKind.SECONDARY)
      PhonoButton(text = "Next change", onClick = {}, kind = PhonoButtonKind.TONAL, trailingIcon = PhonoIcons.ArrowRight)
      PhonoButton(text = "Not now", onClick = {}, kind = PhonoButtonKind.QUIET)
      PhonoButton(text = "Remove", onClick = {}, kind = PhonoButtonKind.DANGER)
    }
    PhonoCard(variant = CardVariant.ALERT, onClick = {}) {
      PhonoText("Needs your attention", style = MaterialTheme.typography.titleLarge)
    }
  }
}
