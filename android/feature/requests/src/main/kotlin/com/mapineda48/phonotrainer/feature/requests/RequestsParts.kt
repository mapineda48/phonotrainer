package com.mapineda48.phonotrainer.feature.requests

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mapineda48.phonotrainer.core.designsystem.component.DialogCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request

/** The words of the screen's connection line for [status] (spec 7.5's status words). */
@Composable
internal fun connectionWords(status: ComputerStatus): String = when (status) {
  ComputerStatus.NotConnected -> stringResource(R.string.requests_line_not_connected)
  ComputerStatus.Offline -> stringResource(R.string.requests_line_offline)
  ComputerStatus.NotOnWifi -> stringResource(R.string.requests_line_not_on_wifi)
  ComputerStatus.Looking -> stringResource(R.string.requests_line_looking)
  ComputerStatus.Checking -> stringResource(R.string.requests_line_checking)
  is ComputerStatus.Connected -> stringResource(R.string.requests_line_connected, status.name)
  is ComputerStatus.NotReachable -> stringResource(R.string.requests_line_unreachable)
  is ComputerStatus.DifferentComputer -> stringResource(R.string.requests_line_different)
  ComputerStatus.ConnectAgain -> stringResource(R.string.requests_line_connect_again)
  ComputerStatus.ComputerTooOld -> stringResource(R.string.requests_line_computer_too_old)
  ComputerStatus.AppTooOld -> stringResource(R.string.requests_line_app_too_old)
}

/** The icon of [status] (design 6, connectivity). */
@DrawableRes
internal fun connectionIcon(status: ComputerStatus): Int = when (status) {
  ComputerStatus.NotConnected -> PhonoIcons.ConnNotConnected
  ComputerStatus.Offline -> PhonoIcons.ConnOffline
  ComputerStatus.NotOnWifi -> PhonoIcons.ConnNotOnWifi
  ComputerStatus.Looking, ComputerStatus.Checking -> PhonoIcons.ConnChecking
  is ComputerStatus.Connected -> PhonoIcons.ConnConnected
  is ComputerStatus.NotReachable -> PhonoIcons.ConnUnreachable
  is ComputerStatus.DifferentComputer -> PhonoIcons.ConnDifferent
  ComputerStatus.ConnectAgain -> PhonoIcons.ConnConnectAgain
  ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld -> PhonoIcons.ConnUpdate
}

/**
 * The connection line under the lede (design 10.2 P-03, render `17`): the computer's state in spec 7.5's words, one
 * node for TalkBack and a polite live region, so a change ("Connected to Desk PC") is announced once.
 */
@Composable
internal fun ConnectionStateLine(status: ComputerStatus, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val words = connectionWords(status)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = PhonoSpacing.s6)
      .clearAndSetSemantics {
        contentDescription = words
        liveRegion = LiveRegionMode.Polite
      },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(painterResource(connectionIcon(status)), null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(PhonoSizes.iconButton))
    PhonoText(words, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.ink)
  }
}

/**
 * "Stop analyzing “{title}” on your computer?" (spec F-20 actions table; design 9.23): a destructive dialog, so an
 * outside tap does not dismiss it and "Keep analyzing" takes the focus first. While the computer is not reachable the
 * body says the cancel waits for it and can be undone until then.
 */
@Composable
internal fun CancelDialog(request: Request, connected: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
    DialogCard(
      title = stringResource(R.string.requests_cancel_title, request.title),
      body = stringResource(if (connected) R.string.requests_cancel_body else R.string.requests_cancel_body_offline),
      confirmLabel = stringResource(R.string.requests_cancel_confirm),
      onConfirm = onConfirm,
      dismissLabel = stringResource(R.string.requests_cancel_dismiss),
      onDismiss = onDismiss,
      modifier = Modifier.testTag(RequestsTags.CANCEL_DIALOG),
      destructive = true,
    )
  }
}

/**
 * "Remove “{title}”?" (F-20-AC6: "When Remove is confirmed"; spec F-20 actions table and item 4): the body says what
 * goes with it (the file's copy on this phone; a failed job on the computer). Destructive like the cancel.
 */
@Composable
internal fun RemoveDialog(request: Request, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
    DialogCard(
      title = stringResource(R.string.requests_remove_title, request.title),
      body = stringResource(RequestsRules.removeBody(request)),
      confirmLabel = stringResource(R.string.requests_remove_confirm),
      onConfirm = onConfirm,
      dismissLabel = stringResource(R.string.requests_remove_dismiss),
      onDismiss = onDismiss,
      modifier = Modifier.testTag(RequestsTags.REMOVE_DIALOG),
      destructive = true,
    )
  }
}
