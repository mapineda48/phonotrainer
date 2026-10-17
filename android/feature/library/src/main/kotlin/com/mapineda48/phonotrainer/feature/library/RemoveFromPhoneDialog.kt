package com.mapineda48.phonotrainer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.paperDepth
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

/**
 * "Remove “{title}” from this phone?" (spec F-05 item 5, F-05-AC11; design 9.23, 9.26), the one confirmation of the
 * Clip page and the Library's menu (Storage words it the same in `:feature:settings`). A destructive dialog: an
 * outside tap does not dismiss it and "Cancel" has the focus first; the confirm is the primary button, not the danger
 * one, because the clip can be downloaded again (design 9.26). Back dismisses it.
 */
@Composable
internal fun RemoveFromPhoneDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
    RemoveFromPhoneCard(title, onConfirm, onDismiss)
  }
}

/** The confirmation's card on its own, for previews and screen tests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoveFromPhoneCard(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.extraLarge)
  val heading = stringResource(R.string.library_remove_title, title)
  val cancelFocus = remember { FocusRequester() }
  Column(
    modifier = modifier
      .widthIn(max = DIALOG_MAX_WIDTH)
      .paperDepth(colors, shape, floating = true)
      .clip(shape)
      .background(colors.sheet)
      .semantics { paneTitle = heading }
      .testTag(LibraryTags.REMOVE_DIALOG)
      .padding(PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
  ) {
    // The words scroll under the buttons at large text (design 9.26 "Large text").
    Column(
      modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
    ) {
      PhonoText(heading, style = PhonoTheme.type.dialogTitle, color = colors.ink, modifier = Modifier.testTag(LibraryTags.REMOVE_TITLE))
      PhonoText(
        stringResource(R.string.library_remove_body),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.ink2,
        modifier = Modifier.testTag(LibraryTags.REMOVE_BODY),
      )
    }
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      PhonoButton(
        text = stringResource(R.string.library_remove_cancel),
        onClick = onDismiss,
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.focusRequester(cancelFocus).testTag(LibraryTags.REMOVE_CANCEL),
      )
      PhonoButton(
        text = stringResource(R.string.library_remove_confirm),
        onClick = onConfirm,
        kind = PhonoButtonKind.PRIMARY,
        modifier = Modifier.testTag(LibraryTags.REMOVE_CONFIRM),
      )
    }
  }
  LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

private val DIALOG_MAX_WIDTH = 560.dp

@PreviewLightDark
@Composable
private fun RemoveFromPhonePreview() {
  PhonoPreviewSurface { RemoveFromPhoneCard("Coffee shop small talk", onConfirm = {}, onDismiss = {}) }
}
