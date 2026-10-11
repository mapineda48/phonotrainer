package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** The drag handle of a bottom sheet (design 9.9): a 32 × 4 dp lineStrong bar. Decorative. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.padding(vertical = PhonoSpacing.s10).size(32.dp, 4.dp).clip(CircleShape)
      .background(PhonoTheme.colors.lineStrong),
  )
}

/**
 * A dialog (design 9.23): a sheet with 28 dp corners and the floating depth, the title in Newsreader, the body, the
 * buttons right-aligned (quiet, then primary or danger). A [destructive] dialog is not dismissed by an outside tap and
 * puts the focus on [dismissLabel] ("Cancel") first.
 */
@Composable
fun PhonoDialog(
  title: String,
  body: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  dismissLabel: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  destructive: Boolean = false,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnClickOutside = !destructive, dismissOnBackPress = true),
  ) {
    DialogCard(title, body, confirmLabel, onConfirm, dismissLabel, onDismiss, modifier, destructive)
  }
}

/** The dialog's card on its own (previews and screen tests draw it without a window). */
@Composable
fun DialogCard(
  title: String,
  body: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  dismissLabel: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  destructive: Boolean = false,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.extraLarge)
  val cancelFocus = remember { FocusRequester() }
  Column(
    modifier = modifier
      .widthIn(max = 560.dp)
      .paperDepth(colors, shape, floating = true)
      .clip(shape)
      .background(colors.sheet)
      .semantics { paneTitle = title }
      .padding(PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
  ) {
    PhonoText(title, style = PhonoTheme.type.dialogTitle, color = colors.ink)
    PhonoText(body, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PhonoBaseButton(
        dismissLabel,
        onDismiss,
        emphasis = ButtonEmphasis.QUIET,
        modifier = Modifier.focusRequester(cancelFocus),
      )
      PhonoBaseButton(
        confirmLabel,
        onConfirm,
        emphasis = if (destructive) ButtonEmphasis.DANGER else ButtonEmphasis.PRIMARY,
      )
    }
  }
  if (destructive) {
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
  }
}

/**
 * The snackbar's look (design 9.23): the inverse surface, 12 dp corners, at least 56 dp, the words and one underlined
 * action. It is a polite live region; the host decides how long it stays (never under 10 s, waiting with TalkBack).
 */
@Composable
fun PhonoSnackbar(
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .background(colors.inverseSurface)
      .semantics { liveRegion = LiveRegionMode.Polite }
      .padding(start = PhonoSpacing.s16, end = PhonoSpacing.s4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PhonoText(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.inverseOnSurface,
      modifier = Modifier.weight(1f).padding(vertical = PhonoSpacing.s12),
    )
    if (actionLabel != null) {
      Box(
        modifier = Modifier
          .focusRing(RoundedCornerShape(PhonoRadius.medium))
          .heightIn(min = PhonoSizes.minTarget)
          .widthIn(min = PhonoSizes.minTarget)
          .clip(RoundedCornerShape(PhonoRadius.medium))
          .clickable(role = Role.Button, onClick = onAction)
          .padding(horizontal = PhonoSpacing.s12),
        contentAlignment = Alignment.Center,
      ) {
        PhonoText(
          actionLabel,
          style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
          color = colors.inverseOnSurface,
        )
      }
    }
  }
}

/** Where a coach mark's pointer sits. */
enum class CoachPointer { NONE, TOP, BOTTOM }

/**
 * The coach-mark slip (design 9.23): ink fill and onInk words, 12 dp corners, a 14 dp pointer toward its anchor, an
 * icon, the bold tip, one sentence and "Got it" (underlined, 48 dp). One at a time, never pausing, never on a timer;
 * TalkBack meets it as a pane titled by its tip.
 */
@Composable
fun CoachMarkSlip(
  title: String,
  body: String,
  gotItLabel: String,
  onGotIt: () -> Unit,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = PhonoIcons.Tip,
  pointer: CoachPointer = CoachPointer.NONE,
  gotItModifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  Column(modifier.semantics { paneTitle = title }) {
    if (pointer == CoachPointer.TOP) Pointer(colors.coachPlate, up = true)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(PhonoRadius.medium))
        .background(colors.coachPlate)
        .padding(start = PhonoSpacing.s14, top = PhonoSpacing.s12, end = PhonoSpacing.s4, bottom = PhonoSpacing.s4),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
    ) {
      icon?.let { Icon(painterResource(it), null, tint = colors.inverseOnSurface, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconButton)) }
      Column(Modifier.weight(1f)) {
        PhonoText(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = colors.inverseOnSurface)
        PhonoText(body, style = MaterialTheme.typography.bodyMedium, color = colors.inverseOnSurface)
        Box(
          modifier = gotItModifier
            .align(Alignment.End)
            .focusRing(RoundedCornerShape(PhonoRadius.medium))
            .heightIn(min = PhonoSizes.minTarget)
            .widthIn(min = PhonoSizes.minTarget)
            .clip(RoundedCornerShape(PhonoRadius.medium))
            .clickable(role = Role.Button, onClick = onGotIt)
            .padding(horizontal = PhonoSpacing.s12),
          contentAlignment = Alignment.Center,
        ) {
          PhonoText(
            gotItLabel,
            style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
            color = colors.inverseOnSurface,
          )
        }
      }
    }
    if (pointer == CoachPointer.BOTTOM) Pointer(colors.coachPlate, up = false)
  }
}

@Composable
private fun Pointer(color: Color, up: Boolean) {
  val triangle = remember(up) {
    GenericShape { size, _ ->
      if (up) {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
      } else {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width / 2f, size.height)
      }
      close()
    }
  }
  Box(Modifier.padding(start = PhonoSpacing.s24).size(14.dp, 8.dp).clip(triangle).background(color))
}

@PreviewLightDark
@Composable
internal fun SheetsPreview() {
  PhonoPreviewSurface {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
    DialogCard(
      title = "Forget this computer?",
      body = "This phone stops syncing with it. Your clips stay on this phone.",
      confirmLabel = "Forget",
      onConfirm = {},
      dismissLabel = "Cancel",
      onDismiss = {},
      destructive = true,
    )
    PhonoSnackbar("This request was removed.", actionLabel = "Undo")
    CoachMarkSlip(
      title = "Tap any word for its lesson.",
      body = "The line under a word tells you the kind of change.",
      gotItLabel = "Got it",
      onGotIt = {},
      icon = PhonoIcons.Hold,
      pointer = CoachPointer.TOP,
    )
  }
}
