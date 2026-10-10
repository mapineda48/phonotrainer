package com.mapineda48.phonotrainer.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.StatusLine

/** What a request card shows (design 9.19); [compact] is the Library and Home mini-row. */
@Immutable
data class RequestRowState(val request: Request, val compact: Boolean = false)

/** Test tags of the request row. */
object RequestRowTags {
  const val OPEN = "ui.request.open"

  /** The button of [action]: "ui.request.action.SEND_NOW". */
  fun action(action: RequestAction): String = "ui.request.action.${action.name}"
}

/** The label of a request action, the same words everywhere it appears. */
@StringRes
fun RequestAction.labelRes(): Int = when (this) {
  RequestAction.CANCEL_PREPARING -> R.string.request_action_cancel_preparing
  RequestAction.SEND_NOW -> R.string.request_action_send_now
  RequestAction.STOP_SENDING -> R.string.request_action_stop_sending
  RequestAction.REMOVE -> R.string.request_action_remove
  RequestAction.CANCEL -> R.string.request_action_cancel
  RequestAction.UNDO_CANCEL -> R.string.request_action_undo_cancel
  RequestAction.TRY_AGAIN -> R.string.request_action_try_again
  RequestAction.TRY_AUDIO_ONLY -> R.string.request_action_try_audio_only
  RequestAction.OPEN_LINK -> R.string.request_action_open_link
  RequestAction.SHOW_DETAILS -> R.string.request_action_show_details
  RequestAction.CONNECT_AGAIN -> R.string.request_action_connect_again
  RequestAction.WHY -> R.string.request_action_why
}

/**
 * A request card (WP-22; API frozen at M2, work-plan 4.5): title, state, and the actions `Request.actions` allows,
 * which is the one authority on what a row offers (architecture 8.4). Placeholder visuals.
 */
@Composable
fun RequestRow(
  state: RequestRowState,
  onAction: (RequestAction) -> Unit,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val openLabel = stringResource(R.string.request_open, state.request.title)
  Column(
    modifier = modifier.heightIn(min = 56.dp).clickable(onClick = onOpen).testTag(RequestRowTags.OPEN)
      .semantics { contentDescription = openLabel },
  ) {
    Text(state.request.title, style = MaterialTheme.typography.titleMedium)
    StatusLine(text = state.request.state.name)
    if (!state.compact) {
      RequestAction.entries.filter { it in state.request.actions }.forEach { action ->
        PhonoButton(
          text = stringResource(action.labelRes()),
          onClick = { onAction(action) },
          modifier = Modifier.testTag(RequestRowTags.action(action)),
          kind = PhonoButtonKind.SECONDARY,
        )
      }
    }
  }
}

@Preview
@Composable
private fun RequestRowPreview() {
  PhonoTheme { RequestRow(RequestRowState(PreviewData.request), onAction = {}, onOpen = {}) }
}
