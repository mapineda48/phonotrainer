package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionCardExtras
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCard
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCardCopy

/** What Your computer's controls ask for. */
internal class YourComputerActions(
  val onBack: () -> Unit = {},
  /** A control of the status card (design 9.17). */
  val onCard: (ConnectionAction) -> Unit = {},
  val onCheckNow: () -> Unit = {},
  val onDetails: () -> Unit = {},
  val onNewAddress: () -> Unit = {},
  val onConnectAgain: () -> Unit = {},
  val onForget: () -> Unit = {},
  val onConnect: () -> Unit = {},
)

/**
 * Settings > Your computer (spec F-22, design 10.2 P-12 and 9.17): the status card, the facts (name, last seen, last
 * synced, this phone's name), the ways on ("Check now", "Connection details", "Enter the new address", "Connect
 * again") and "Forget this computer…" with its confirmation (D-60: title "Forget this computer?", F-22's body,
 * "Cancel" first). Not connected: the empty state with "Connect to your computer".
 */
@Composable
internal fun YourComputerScreen(state: YourComputerUiState, actions: YourComputerActions, modifier: Modifier = Modifier) {
  var confirming by rememberSaveable { mutableStateOf(false) }
  val paired = state.paired
  SettingsPage(
    title = stringResource(R.string.computer_title),
    lede = stringResource(R.string.computer_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.COMPUTER_LIST,
    modifier = modifier,
  ) {
    val forgotten = state.forget as? ForgetOutcome.Forgotten
    if (paired == null) {
      if (forgotten != null) {
        item(key = "forgotten") {
          Notice(
            body = stringResource(R.string.computer_forgotten, forgotten.name),
            live = true,
            modifier = Modifier.testTag(SettingsTags.COMPUTER_FORGOTTEN),
          )
        }
      }
      item(key = "none") {
        EmptyState(
          illustration = PhonoIllustrations.NoComputer,
          title = stringResource(R.string.computer_none_title),
          body = stringResource(R.string.computer_none_body),
          actions = listOf(EmptyStateAction(stringResource(R.string.computer_connect), actions.onConnect)),
          modifier = Modifier.testTag(SettingsTags.COMPUTER_NONE),
        )
      }
      return@SettingsPage
    }
    item(key = "card") {
      ConnectionStatusCard(
        status = state.status,
        sync = state.sync,
        counts = NO_COUNTS,
        transfers = null,
        onAction = actions.onCard,
        extras = ConnectionCardExtras(now = state.now, checkedAt = state.checkedAt, backgroundRestricted = state.backgroundRestricted),
      )
    }
    (state.forget as? ForgetOutcome.Failed)?.let { failed ->
      item(key = "forget-failed") {
        Notice(
          body = stringResource(R.string.computer_forget_failed, failed.name),
          kind = NoticeKind.CAUTION,
          live = true,
          modifier = Modifier.testTag(SettingsTags.COMPUTER_FORGET_FAILED),
        )
      }
    }
    item(key = "facts") {
      val resources = LocalResources.current
      val notYet = stringResource(R.string.computer_not_yet)
      val now = state.now
      fun ago(at: java.time.Instant?): String = if (at == null || now == null) notYet else ConnectionStatusCardCopy.ago(at, now, resources)
      Column(Modifier.testTag(SettingsTags.COMPUTER_FACTS), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        Kicker(stringResource(R.string.computer_facts), modifier = Modifier.padding(top = PhonoSpacing.s8))
        FactRow(stringResource(R.string.computer_name), paired.name)
        FactRow(stringResource(R.string.computer_last_seen), ago(paired.lastSeen))
        FactRow(stringResource(R.string.computer_last_sync), ago(state.lastSynced))
        FactRow(stringResource(R.string.computer_phone_name), paired.deviceName)
      }
    }
    item(key = "actions") {
      Kicker(stringResource(R.string.computer_actions), modifier = Modifier.padding(top = PhonoSpacing.s8))
    }
    item(key = "check") {
      SettingsRow(
        icon = PhonoIcons.Sync,
        title = stringResource(R.string.computer_check),
        status = stringResource(if (state.checking) R.string.computer_checking else R.string.computer_check_body),
        onClick = actions.onCheckNow,
        enabled = !state.checking,
        trailing = null,
        modifier = Modifier.testTag(SettingsTags.COMPUTER_CHECK),
      )
    }
    item(key = "details") {
      SettingsRow(
        icon = PhonoIcons.Details,
        title = stringResource(R.string.computer_details),
        status = stringResource(R.string.computer_details_body),
        onClick = actions.onDetails,
        modifier = Modifier.testTag(SettingsTags.COMPUTER_DETAILS),
      )
    }
    item(key = "new-address") {
      SettingsRow(
        icon = PhonoIcons.Address,
        title = stringResource(R.string.computer_new_address),
        status = stringResource(R.string.computer_new_address_body),
        onClick = actions.onNewAddress,
        modifier = Modifier.testTag(SettingsTags.COMPUTER_NEW_ADDRESS),
      )
    }
    item(key = "connect-again") {
      SettingsRow(
        icon = PhonoIcons.ConnConnectAgain,
        title = stringResource(R.string.computer_connect_again),
        status = stringResource(R.string.computer_connect_again_body),
        onClick = actions.onConnectAgain,
        modifier = Modifier.testTag(SettingsTags.COMPUTER_CONNECT_AGAIN),
      )
    }
    item(key = "forget") {
      PhonoButton(
        text = stringResource(R.string.computer_forget),
        onClick = { confirming = true },
        kind = PhonoButtonKind.DANGER,
        busy = state.forgetting,
        enabled = !state.forgetting,
        fillWidth = true,
        modifier = Modifier.padding(top = PhonoSpacing.s16).testTag(SettingsTags.COMPUTER_FORGET),
      )
    }
  }
  if (confirming && paired != null) {
    ForgetComputerDialog(
      name = paired.name,
      onConfirm = {
        confirming = false
        actions.onForget()
      },
      onDismiss = { confirming = false },
    )
  }
}

/** The test tags of "Forget this computer?". */
internal val FORGET_TAGS = DialogTags(
  dialog = SettingsTags.FORGET_DIALOG,
  title = SettingsTags.FORGET_TITLE,
  body = SettingsTags.FORGET_BODY,
  cancel = SettingsTags.FORGET_CANCEL,
  confirm = SettingsTags.FORGET_CONFIRM,
)

/**
 * "Forget this computer?" (spec D-60 and F-22; design 9.23, render `31` specimen 4): F-22's body with the computer's
 * name, "Cancel" (quiet, the initial focus) and "Forget this computer" (primary). [consequences] are the two
 * v1-complete lines of design 9.23 rule (3) (WP-34); v1-core has none.
 */
@Composable
internal fun ForgetComputerDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit, consequences: List<String> = emptyList()) {
  ConfirmDialog(
    title = stringResource(R.string.computer_forget_title),
    body = stringResource(R.string.computer_forget_body, name),
    cancelLabel = stringResource(R.string.computer_forget_cancel),
    confirmLabel = stringResource(R.string.computer_forget_confirm),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    tags = FORGET_TAGS,
    consequences = consequences,
  )
}

private val NO_COUNTS = RequestCounts(waiting = 0, onComputer = 0, needsAttention = 0, readyNew = 0)
