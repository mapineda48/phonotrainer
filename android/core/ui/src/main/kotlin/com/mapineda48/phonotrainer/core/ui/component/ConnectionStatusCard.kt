package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.IconWell
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTrace
import com.mapineda48.phonotrainer.core.designsystem.trace.sampleContour
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R
import java.time.Instant

/**
 * What the status card's buttons and rows ask for (design 9.17). [ATTENTION] is the inline primary action of the
 * request that needs attention ([ConnectionCardExtras.attentionAction] says which); [SEE_REQUESTS] is a tap on the
 * request-count rows; [STORAGE] the storage line; [BATTERY_SETTINGS] the background line's system screen.
 */
enum class ConnectionAction {
  CONNECT,
  CONNECT_AGAIN,
  WHY,
  SEE_REQUESTS,
  SYNC_NOW,
  DETAILS,
  NEW_ADDRESS,
  ATTENTION,
  STORAGE,
  BATTERY_SETTINGS,
}

/** Test tags of the status card (`ui.connection.<element>`, work-plan 2.6). */
object ConnectionTags {
  /** The card's summary: the icon well, the title and the subtitle, read as the card's one sentence. */
  const val STATUS = "ui.connection.status"

  /** The button of [action]; the request-count rows carry [ConnectionAction.SEE_REQUESTS]. */
  fun action(action: ConnectionAction): String = "ui.connection.action.${action.name}"

  /** The row of [kind]. */
  fun row(kind: ConnectionRowKind): String = "ui.connection.row.${kind.name}"
}

/** The title of [status] (spec 7.5), the same words as the card's (`ConnectionStatusCardCopy.title`). */
@Composable
fun connectionStatusText(status: ComputerStatus): String {
  val resources = LocalResources.current
  return remember(status, resources) { ConnectionStatusCardCopy.title(status, SyncState.Idle, resources) }
}

/**
 * The computer status card of Home, Connection details and Settings > Your computer (WP-15; API frozen at M2,
 * work-plan 4.5; design 9.17): a 44 dp icon well (ink fill when connected, an ink ring otherwise), the title of every
 * `ComputerStatus` (spec 7.5; "Syncing · bringing 3 clips · 42 %" while a sync runs), its subtitle and the state's
 * action; then 52 dp rows with a hairline above: this phone's own request counts (one target that opens Requests,
 * with the failed request's primary action inline), the clips coming to this phone, the computer's low space, the
 * storage line and the background line. Every state is icon + words, never colour alone (F-03-AC2).
 *
 * TalkBack reads the summary as the whole card in one sentence ([ConnectionCardWords.sentence]); the rows' words are
 * not read a second time, and only the controls (the action, "See requests", the inline action, Storage, Battery
 * settings) are separate stops. Transitions are announced by the connectivity core, never by the card (architecture
 * 6.2 item 7).
 *
 * [extras] carries what the frozen four inputs cannot (times, the analyzing progress, the inline action, the clips
 * coming, storage and background); its defaults show the status and the counts only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionStatusCard(
  status: ComputerStatus,
  sync: SyncState,
  counts: RequestCounts,
  transfers: TransferProgress?,
  onAction: (ConnectionAction) -> Unit,
  modifier: Modifier = Modifier,
  extras: ConnectionCardExtras = ConnectionCardExtras(),
) {
  val resources = LocalResources.current
  val words = remember(status, sync, counts, transfers, extras, resources) {
    ConnectionStatusCardCopy.words(status, sync, counts, transfers, extras, resources)
  }
  val colors = PhonoTheme.colors
  PhonoCard(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag(ConnectionTags.STATUS)
        .clearAndSetSemantics { contentDescription = words.sentence },
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconWell(icon = words.icon, filled = words.connected, size = WELL_SIZE)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        PhonoText(words.title, style = MaterialTheme.typography.titleLarge, color = colors.ink)
        words.subtitle?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
        words.syncProgress?.let { progress ->
          // The trace follows the sync's own progress (content, not decoration; still under reduced motion).
          StylusTrace(sampleContour(), progress = progress, modifier = Modifier.fillMaxWidth().height(TRACE_HEIGHT), showNib = false)
        }
      }
    }
    if (words.actions.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      ) {
        words.actions.forEachIndexed { index, action ->
          PhonoButton(
            text = stringResource(action.labelRes()),
            onClick = { onAction(action) },
            modifier = Modifier.testTag(ConnectionTags.action(action)),
            kind = if (index == 0) PhonoButtonKind.SECONDARY else PhonoButtonKind.QUIET,
          )
        }
      }
    }
    val requestRows = words.rows.filter { it.isRequestCount }
    if (requestRows.isNotEmpty()) {
      val seeRequests = stringResource(R.string.connection_action_requests)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .focusRing(RoundedCornerShape(PhonoRadius.medium))
          .clip(RoundedCornerShape(PhonoRadius.medium))
          .clickable(onClickLabel = seeRequests, role = Role.Button) { onAction(ConnectionAction.SEE_REQUESTS) }
          .testTag(ConnectionTags.action(ConnectionAction.SEE_REQUESTS))
          // The summary already reads the counts: this stop is the action that opens Requests.
          .clearAndSetSemantics {
            contentDescription = seeRequests
            role = Role.Button
            onClick(seeRequests) { onAction(ConnectionAction.SEE_REQUESTS); true }
          },
      ) {
        requestRows.forEach { row -> RowLine(row, onAction = null) }
      }
      // The inline action of the attention row sits outside the clickable group, as its own stop.
      requestRows.firstOrNull { it.action == ConnectionAction.ATTENTION }?.let {
        extras.attentionAction?.let { action ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PhonoButton(
              text = stringResource(action.labelRes()),
              onClick = { onAction(ConnectionAction.ATTENTION) },
              modifier = Modifier.testTag(ConnectionTags.action(ConnectionAction.ATTENTION)),
              kind = PhonoButtonKind.PRIMARY,
            )
          }
        }
      }
    }
    words.rows.filterNot { it.isRequestCount }.forEach { row -> RowLine(row, onAction) }
  }
}

/** One row of the card (design 9.17): a hairline above, icon + words, the bar, and the row's own control. */
@Composable
private fun RowLine(row: ConnectionRow, onAction: ((ConnectionAction) -> Unit)?) {
  val colors = PhonoTheme.colors
  val critical = row.kind == ConnectionRowKind.ATTENTION
  val wholeRow = row.action == ConnectionAction.STORAGE && onAction != null
  val label = row.action?.let { stringResource(it.labelRes()) }
  Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = ROW_HEIGHT)
        .testTag(ConnectionTags.row(row.kind))
        .then(
          if (wholeRow && label != null) {
            Modifier
              .focusRing(RoundedCornerShape(PhonoRadius.medium))
              .clip(RoundedCornerShape(PhonoRadius.medium))
              .clickable(onClickLabel = label, role = Role.Button) { onAction?.invoke(ConnectionAction.STORAGE) }
              .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onAction?.invoke(ConnectionAction.STORAGE); true }
              }
          } else {
            // The words are part of the card's sentence; TalkBack does not read them twice.
            Modifier
          },
        )
        .padding(vertical = PhonoSpacing.s8),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().then(if (wholeRow) Modifier else Modifier.clearAndSetSemantics { }),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painterResource(row.icon),
          contentDescription = null,
          tint = if (critical) colors.critical else colors.ink,
          modifier = Modifier.size(PhonoSizes.iconButton),
        )
        PhonoText(row.text, style = MaterialTheme.typography.bodyMedium, color = colors.ink, modifier = Modifier.weight(1f))
      }
      row.progress?.let { progress ->
        Box(Modifier.clearAndSetSemantics { }) {
          PhonoProgressBar(progress = progress.takeIf { it >= 0f }, modifier = Modifier.fillMaxWidth().padding(start = ROW_INDENT))
        }
      }
      if (row.action == ConnectionAction.BATTERY_SETTINGS && onAction != null && label != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          PhonoButton(
            text = label,
            onClick = { onAction(ConnectionAction.BATTERY_SETTINGS) },
            modifier = Modifier.testTag(ConnectionTags.action(ConnectionAction.BATTERY_SETTINGS)),
            kind = PhonoButtonKind.SECONDARY,
          )
        }
      }
    }
  }
}

/** The words of a card control. */
private fun ConnectionAction.labelRes(): Int = when (this) {
  ConnectionAction.CONNECT -> R.string.connection_action_connect
  ConnectionAction.CONNECT_AGAIN -> R.string.connection_action_connect_again
  ConnectionAction.WHY -> R.string.connection_action_why
  ConnectionAction.SEE_REQUESTS -> R.string.connection_action_requests
  ConnectionAction.SYNC_NOW -> R.string.connection_action_sync_now
  ConnectionAction.DETAILS -> R.string.connection_action_details
  ConnectionAction.NEW_ADDRESS -> R.string.connection_action_new_address
  ConnectionAction.STORAGE -> R.string.connection_action_storage
  ConnectionAction.BATTERY_SETTINGS -> R.string.connection_action_battery
  // The inline action's words are the request action's own (RequestAction.labelRes), never this fallback.
  ConnectionAction.ATTENTION -> R.string.connection_action_requests
}

private val WELL_SIZE = 44.dp
private val ROW_HEIGHT = 52.dp
private val ROW_INDENT = 30.dp
private val TRACE_HEIGHT = 18.dp

@PreviewLightDark
@Composable
private fun ConnectionStatusCardPreview() {
  val now = Instant.parse("2026-10-04T10:30:00Z")
  PhonoPreviewSurface {
    ConnectionStatusCard(
      status = ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 2, lowOnSpace = false),
      sync = SyncState.Idle,
      counts = RequestCounts(waiting = 2, onComputer = 3, needsAttention = 1, readyNew = 0, analyzing = 1, inLine = 2),
      transfers = null,
      onAction = {},
      extras = ConnectionCardExtras(
        now = now,
        checkedAt = now.minusSeconds(10),
        analyzing = RequestProgress(Stage.PHONES, 40, null, null, null, null),
        attentionAction = RequestAction.TRY_AGAIN,
      ),
    )
    ConnectionStatusCard(
      status = ComputerStatus.Connected("Desk PC", analyzing = 0, inLine = 0, lowOnSpace = true),
      sync = SyncState.Active(bringing = 2, percent = 42, sending = 0, doneBytes = 34_000_000, totalBytes = 61_000_000),
      counts = RequestCounts(0, 0, 0, 0),
      transfers = null,
      onAction = {},
      extras = ConnectionCardExtras(now = now, clipsOnPhone = 3, clipsTotal = 10, storageNearlyFull = true),
    )
    ConnectionStatusCard(
      status = ComputerStatus.NotReachable("Desk PC", now.minusSeconds(10_800), addressMayHaveChanged = true, ProbeOutcome.NoAnswer(3)),
      sync = SyncState.Idle,
      counts = RequestCounts(waiting = 2, onComputer = 0, needsAttention = 0, readyNew = 0),
      transfers = null,
      onAction = {},
      extras = ConnectionCardExtras(now = now, backgroundRestricted = true),
    )
    ConnectionStatusCard(
      status = ComputerStatus.NotConnected,
      sync = SyncState.Idle,
      counts = RequestCounts(waiting = 1, onComputer = 0, needsAttention = 0, readyNew = 0),
      transfers = null,
      onAction = {},
    )
  }
}
