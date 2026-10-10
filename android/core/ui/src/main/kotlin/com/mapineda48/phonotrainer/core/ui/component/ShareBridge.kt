package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.StatusLine

/** The phone → computer bridge's state (design 9.22): where the request is going and whether it can go now. */
@Immutable
data class ShareBridgeState(val status: ComputerStatus)

/** The bridge of the share sheet and Add a clip (WP-23; API frozen at M2, work-plan 4.5). Placeholder. */
@Composable
fun ShareBridge(state: ShareBridgeState, modifier: Modifier = Modifier) {
  Row(modifier = modifier) {
    Text(stringResource(R.string.bridge_phone))
    Text(
      stringResource(
        if (state.status is ComputerStatus.Connected) R.string.bridge_sending else R.string.bridge_waiting,
      ),
    )
    Text(stringResource(R.string.bridge_computer))
  }
}

/** The connection line under the bridge, mapped from [status] (design 9.22). */
@Composable
fun ConnectionLine(status: ComputerStatus, modifier: Modifier = Modifier) {
  StatusLine(text = connectionStatusText(status), modifier = modifier)
}

@Preview
@Composable
private fun ShareBridgePreview() {
  PhonoTheme { ShareBridge(ShareBridgeState(ComputerStatus.Offline)) }
}
