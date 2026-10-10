package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.StatusLine
import com.mapineda48.phonotrainer.core.ui.StatusTone

/** What the status card's buttons ask for (design 9.17). */
enum class ConnectionAction { CONNECT, CONNECT_AGAIN, WHY, SEE_REQUESTS }

/** Test tags of the status card. */
object ConnectionTags {
  const val STATUS = "ui.connection.status"

  /** The button of [action]. */
  fun action(action: ConnectionAction): String = "ui.connection.action.${action.name}"
}

/** The words of [status] (spec 7.5). */
@Composable
fun connectionStatusText(status: ComputerStatus): String = when (status) {
  ComputerStatus.NotConnected -> stringResource(R.string.connection_not_connected)
  ComputerStatus.Offline -> stringResource(R.string.connection_offline)
  ComputerStatus.NotOnWifi -> stringResource(R.string.connection_not_on_wifi)
  ComputerStatus.Looking -> stringResource(R.string.connection_looking)
  ComputerStatus.Checking -> stringResource(R.string.connection_checking)
  is ComputerStatus.Connected -> stringResource(R.string.connection_connected, status.name)
  is ComputerStatus.NotReachable -> stringResource(R.string.connection_not_reachable)
  is ComputerStatus.DifferentComputer -> stringResource(R.string.connection_different)
  ComputerStatus.ConnectAgain -> stringResource(R.string.connection_connect_again)
  ComputerStatus.ComputerTooOld -> stringResource(R.string.connection_computer_too_old)
  ComputerStatus.AppTooOld -> stringResource(R.string.connection_app_too_old)
}

/**
 * The computer status card of Home, Connection details and Settings > Your computer (WP-15; API frozen at M2,
 * work-plan 4.5, design 9.17): every `ComputerStatus`, the sync state and the request counts. Placeholder.
 */
@Composable
fun ConnectionStatusCard(
  status: ComputerStatus,
  sync: SyncState,
  counts: RequestCounts,
  transfers: TransferProgress?,
  onAction: (ConnectionAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  PhonoCard(modifier = modifier) {
    StatusLine(
      text = connectionStatusText(status),
      modifier = Modifier.testTag(ConnectionTags.STATUS),
      tone = if (status is ComputerStatus.Connected) StatusTone.GOOD else StatusTone.NEUTRAL,
    )
    if (sync is SyncState.Active) Text(pluralStringResource(R.plurals.connection_syncing, sync.bringing, sync.bringing))
    transfers?.let { Text(it.title) }
    Text(stringResource(R.string.connection_counts, counts.waiting, counts.onComputer, counts.needsAttention))
    val actions = buildList {
      when (status) {
        ComputerStatus.NotConnected -> add(ConnectionAction.CONNECT)
        ComputerStatus.ConnectAgain -> add(ConnectionAction.CONNECT_AGAIN)
        is ComputerStatus.NotReachable -> add(ConnectionAction.WHY)
        else -> Unit
      }
      if (counts.waiting + counts.onComputer + counts.needsAttention > 0) add(ConnectionAction.SEE_REQUESTS)
    }
    actions.forEach { action ->
      PhonoButton(
        text = stringResource(
          when (action) {
            ConnectionAction.CONNECT -> R.string.connection_action_connect
            ConnectionAction.CONNECT_AGAIN -> R.string.connection_action_connect_again
            ConnectionAction.WHY -> R.string.connection_action_why
            ConnectionAction.SEE_REQUESTS -> R.string.connection_action_requests
          },
        ),
        onClick = { onAction(action) },
        modifier = Modifier.testTag(ConnectionTags.action(action)),
        kind = PhonoButtonKind.SECONDARY,
      )
    }
  }
}

@Preview
@Composable
private fun ConnectionStatusCardPreview() {
  PhonoTheme {
    ConnectionStatusCard(
      status = ComputerStatus.NotConnected,
      sync = SyncState.Idle,
      counts = RequestCounts(waiting = 1, onComputer = 0, needsAttention = 0, readyNew = 0),
      transfers = null,
      onAction = {},
    )
  }
}
