package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.ui.R
import java.time.Duration
import java.time.Instant

/**
 * What the status card knows besides the four inputs of its frozen API (work-plan 4.5): every field has a default, so
 * a host that has none of them (Connection details, Settings > Your computer) shows the status and the counts only.
 *
 * - [now] and [checkedAt]: "Checked 10 s ago" (`ConnectionDiagnostics.lastProbeAt`) and "last seen 3 h ago";
 * - [analyzing]: the progress of this phone's one analyzing request, "1 analyzing · 40 % · Hear the phones";
 * - [attentionAction]: the primary action of the request that needs attention, inline in its row ("Try again");
 * - [clipsOnPhone] of [clipsTotal]: "Bringing your clips to this phone: 3 of 10" while a sync brings clips (F-03);
 * - [storageNearlyFull]: `StorageUsage.budgetReached` or `belowFloor` (the line opens Storage);
 * - [backgroundRestricted]: `ComputerStatusRepository.backgroundRestricted` ("Battery settings").
 */
@Immutable
data class ConnectionCardExtras(
  val now: Instant? = null,
  val checkedAt: Instant? = null,
  val analyzing: RequestProgress? = null,
  val attentionAction: RequestAction? = null,
  val clipsOnPhone: Int? = null,
  val clipsTotal: Int? = null,
  val storageNearlyFull: Boolean = false,
  val backgroundRestricted: Boolean = false,
)

/** The kinds of row under the card's title (design 9.17), in the order they are drawn. */
enum class ConnectionRowKind { WAITING, ANALYZING, IN_LINE, ON_COMPUTER, ATTENTION, BRINGING, LOW_SPACE, STORAGE, BACKGROUND }

/** One row: its words, its icon, a bar when [progress] is not null (-1 = working, no percent) and its inline action. */
@Immutable
data class ConnectionRow(
  val kind: ConnectionRowKind,
  val text: String,
  @DrawableRes val icon: Int,
  val progress: Float? = null,
  val action: ConnectionAction? = null,
) {
  /** The request-count rows open Requests together (spec F-03 item 2). */
  val isRequestCount: Boolean
    get() = kind in REQUEST_COUNTS

  private companion object {
    val REQUEST_COUNTS = setOf(
      ConnectionRowKind.WAITING,
      ConnectionRowKind.ANALYZING,
      ConnectionRowKind.IN_LINE,
      ConnectionRowKind.ON_COMPUTER,
      ConnectionRowKind.ATTENTION,
    )
  }
}

/** Everything the card shows, in words (one mapping for the card, its tests and its TalkBack sentence). */
@Immutable
data class ConnectionCardWords(
  val title: String,
  val subtitle: String?,
  @DrawableRes val icon: Int,
  val connected: Boolean,
  /** The sync's own progress under the title (0 to 1), or null. */
  val syncProgress: Float?,
  val actions: List<ConnectionAction>,
  val rows: List<ConnectionRow>,
  /** The one sentence TalkBack reads for the whole card (design 9.17). */
  val sentence: String,
)

/**
 * The words of the computer status card (design 9.17, spec 7.5 and F-03 item 2): the title and subtitle of every
 * `ComputerStatus` with the `SyncState`, the header actions, the rows and the one-sentence summary. Pure, given the
 * [Resources], so a table test covers every state.
 */
object ConnectionStatusCardCopy {
  fun words(
    status: ComputerStatus,
    sync: SyncState,
    counts: RequestCounts,
    transfers: TransferProgress?,
    extras: ConnectionCardExtras,
    resources: Resources,
  ): ConnectionCardWords {
    val title = title(status, sync, resources)
    val subtitle = subtitle(status, sync, extras, resources)
    val rows = rows(status, sync, counts, transfers, extras, resources)
    return ConnectionCardWords(
      title = title,
      subtitle = subtitle,
      icon = icon(status),
      connected = status is ComputerStatus.Connected,
      syncProgress = (sync as? SyncState.Active)?.takeIf { status is ComputerStatus.Connected }?.percent
        ?.let { it.coerceIn(0, 100) / 100f },
      actions = actions(status, sync),
      rows = rows,
      sentence = sentence(listOfNotNull(title, subtitle) + rows.map { it.text }, resources),
    )
  }

  /** The title of [status] (spec 7.5); "Connected" with an active sync reads "Syncing · bringing 3 clips · 42 %". */
  fun title(status: ComputerStatus, sync: SyncState, resources: Resources): String = when (status) {
    ComputerStatus.NotConnected -> resources.getString(R.string.connection_not_connected)
    ComputerStatus.Offline -> resources.getString(R.string.connection_offline)
    ComputerStatus.NotOnWifi -> resources.getString(R.string.connection_not_on_wifi)
    ComputerStatus.Looking -> resources.getString(R.string.connection_looking)
    ComputerStatus.Checking -> resources.getString(R.string.connection_checking)
    is ComputerStatus.Connected -> when {
      sync is SyncState.Active && sync.bringing > 0 && sync.percent != null -> {
        val percent = sync.percent ?: 0
        resources.getQuantityString(R.plurals.connection_syncing_percent, sync.bringing, sync.bringing, percent.coerceIn(0, 100))
      }
      sync is SyncState.Active && sync.bringing > 0 ->
        resources.getQuantityString(R.plurals.connection_syncing, sync.bringing, sync.bringing)
      sync is SyncState.Active -> resources.getString(R.string.connection_syncing_plain)
      else -> resources.getString(R.string.connection_connected, status.name)
    }
    is ComputerStatus.NotReachable -> resources.getString(R.string.connection_not_reachable)
    is ComputerStatus.DifferentComputer -> resources.getString(R.string.connection_different)
    ComputerStatus.ConnectAgain -> resources.getString(R.string.connection_connect_again)
    ComputerStatus.ComputerTooOld -> resources.getString(R.string.connection_computer_too_old)
    ComputerStatus.AppTooOld -> resources.getString(R.string.connection_app_too_old)
  }

  /** The subtitle (design 9.17): the time, the computer's queue, or what the state means for this phone. */
  fun subtitle(status: ComputerStatus, sync: SyncState, extras: ConnectionCardExtras, resources: Resources): String? {
    val parts: List<String> = when (status) {
      ComputerStatus.NotConnected -> listOf(resources.getString(R.string.connection_not_connected_sub))
      is ComputerStatus.Connected -> if (sync is SyncState.Active) {
        listOfNotNull(arriving(extras, resources))
      } else {
        listOfNotNull(
          checkedAgo(extras, resources),
          status.analyzing.takeIf { it > 0 }?.let { resources.getString(R.string.connection_analyzing_short, it) },
          status.inLine.takeIf { it > 0 }?.let { resources.getString(R.string.connection_in_line_short, it) },
        )
      }
      is ComputerStatus.NotReachable -> listOfNotNull(
        status.lastSeen?.let { seen -> extras.now?.let { resources.getString(R.string.connection_last_seen, ago(seen, it, resources)) } },
        resources.getString(R.string.connection_work_offline),
      )
      else -> emptyList()
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(resources.getString(R.string.connection_separator))
  }

  /** The header actions: one per state, plus "Enter the new address" after a network change (design 9.17). */
  fun actions(status: ComputerStatus, sync: SyncState): List<ConnectionAction> = when (status) {
    ComputerStatus.NotConnected -> listOf(ConnectionAction.CONNECT)
    is ComputerStatus.Connected -> if (sync is SyncState.Active) emptyList() else listOf(ConnectionAction.SYNC_NOW)
    is ComputerStatus.NotReachable ->
      listOf(ConnectionAction.WHY) + if (status.addressMayHaveChanged) listOf(ConnectionAction.NEW_ADDRESS) else emptyList()
    is ComputerStatus.DifferentComputer -> listOf(ConnectionAction.DETAILS)
    ComputerStatus.ConnectAgain -> listOf(ConnectionAction.CONNECT_AGAIN)
    ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld -> listOf(ConnectionAction.WHY)
    ComputerStatus.Offline, ComputerStatus.NotOnWifi, ComputerStatus.Looking, ComputerStatus.Checking -> emptyList()
  }

  /** The rows of design 9.17, in order; browser-started jobs never reach [counts] (F-03-AC9). */
  fun rows(
    status: ComputerStatus,
    sync: SyncState,
    counts: RequestCounts,
    transfers: TransferProgress?,
    extras: ConnectionCardExtras,
    resources: Resources,
  ): List<ConnectionRow> = buildList {
    if (counts.waiting > 0) {
      add(ConnectionRow(ConnectionRowKind.WAITING, plural(resources, R.plurals.connection_row_waiting, counts.waiting), PhonoIcons.StatusWaiting))
    }
    if (counts.analyzing > 0) {
      val progress = extras.analyzing?.takeIf { counts.analyzing == 1 }
      val parts = listOfNotNull(
        plural(resources, R.plurals.connection_row_analyzing, counts.analyzing),
        progress?.percent?.let { resources.getString(R.string.connection_row_percent, it.coerceIn(0, 100)) },
        progress?.stage?.let { resources.getString(RequestRowStages.name(it)) },
      )
      add(
        ConnectionRow(
          ConnectionRowKind.ANALYZING,
          parts.joinToString(resources.getString(R.string.connection_separator)),
          PhonoIcons.StatusAnalyzing,
          progress = progress?.percent?.let { it.coerceIn(0, 100) / 100f } ?: WORKING,
        ),
      )
    }
    if (counts.inLine > 0) {
      add(ConnectionRow(ConnectionRowKind.IN_LINE, plural(resources, R.plurals.connection_row_in_line, counts.inLine), PhonoIcons.StatusWaiting))
    }
    // A host whose counts do not split the computer's queue still says how many are there.
    val unsplit = counts.onComputer - counts.analyzing - counts.inLine
    if (counts.analyzing == 0 && counts.inLine == 0 && unsplit > 0) {
      add(ConnectionRow(ConnectionRowKind.ON_COMPUTER, plural(resources, R.plurals.connection_row_on_computer, unsplit), PhonoIcons.StatusSending))
    }
    if (counts.needsAttention > 0) {
      add(
        ConnectionRow(
          ConnectionRowKind.ATTENTION,
          plural(resources, R.plurals.connection_row_attention, counts.needsAttention),
          PhonoIcons.StatusAttention,
          action = extras.attentionAction?.let { ConnectionAction.ATTENTION },
        ),
      )
    }
    bringing(status, sync, transfers, resources)?.let(::add)
    if (status is ComputerStatus.Connected && status.lowOnSpace) {
      add(ConnectionRow(ConnectionRowKind.LOW_SPACE, resources.getString(R.string.connection_row_low_space), PhonoIcons.ConnLowSpace))
    }
    if (extras.storageNearlyFull) {
      add(
        ConnectionRow(
          ConnectionRowKind.STORAGE,
          resources.getString(R.string.connection_row_storage),
          PhonoIcons.ConnLowSpace,
          action = ConnectionAction.STORAGE,
        ),
      )
    }
    if (extras.backgroundRestricted) {
      add(
        ConnectionRow(
          ConnectionRowKind.BACKGROUND,
          resources.getString(R.string.connection_row_background),
          PhonoIcons.BackgroundLimited,
          action = ConnectionAction.BATTERY_SETTINGS,
        ),
      )
    }
  }

  /** "Bringing 2 clips to this phone · 34 MB of 61 MB", from the sync's bytes or else the transfer in progress. */
  private fun bringing(status: ComputerStatus, sync: SyncState, transfers: TransferProgress?, resources: Resources): ConnectionRow? {
    val active = sync as? SyncState.Active
    val items: Int
    val done: Long
    val total: Long?
    when {
      active != null && active.bringing > 0 && active.doneBytes != null -> {
        items = active.bringing
        done = active.doneBytes ?: 0L
        total = active.totalBytes
      }
      transfers != null && status is ComputerStatus.Connected -> {
        items = transfers.items.coerceAtLeast(1)
        done = transfers.doneBytes
        total = transfers.totalBytes
      }
      else -> return null
    }
    val text = if (total != null && total > 0) {
      resources.getQuantityString(R.plurals.connection_row_bringing, items, items, megabytes(done, resources), megabytes(total, resources))
    } else {
      resources.getQuantityString(R.plurals.connection_row_bringing_plain, items, items, megabytes(done, resources))
    }
    val progress = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else WORKING
    return ConnectionRow(ConnectionRowKind.BRINGING, text, PhonoIcons.StatusDownloading, progress = progress)
  }

  /** "34 MB" (whole megabytes of 1,000,000 bytes, as the web's sizes; at least "0 MB"). */
  fun megabytes(bytes: Long, resources: Resources): String =
    resources.getString(R.string.connection_megabytes, (bytes.coerceAtLeast(0) / BYTES_PER_MB).toString())

  /** "10 s ago", "3 min ago", "3 h ago", "2 days ago"; "just now" under a second (and for a clock that went back). */
  fun ago(at: Instant, now: Instant, resources: Resources): String {
    val elapsed = Duration.between(at, now)
    val seconds = elapsed.seconds
    return when {
      seconds < 1 -> resources.getString(R.string.connection_ago_now)
      seconds < SECONDS_PER_MINUTE -> resources.getString(R.string.connection_ago_seconds, seconds.toInt())
      elapsed.toMinutes() < MINUTES_PER_HOUR -> resources.getString(R.string.connection_ago_minutes, elapsed.toMinutes().toInt())
      elapsed.toHours() < HOURS_PER_DAY -> resources.getString(R.string.connection_ago_hours, elapsed.toHours().toInt())
      else -> elapsed.toDays().toInt().let { resources.getQuantityString(R.plurals.connection_ago_days, it, it) }
    }
  }

  /**
   * The card as one sentence (design 9.17): its parts joined with semicolons, the visual separator " · " read as a
   * comma, one full stop at the end.
   */
  fun sentence(parts: List<String>, resources: Resources): String {
    val separator = resources.getString(R.string.connection_separator)
    val spoken = parts.map { part -> part.replace(separator, ", ").trim().trimEnd('.') }.filter { it.isNotEmpty() }
    return spoken.joinToString("; ").let { if (it.isEmpty()) it else "$it." }
  }

  @DrawableRes
  fun icon(status: ComputerStatus): Int = when (status) {
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

  private fun checkedAgo(extras: ConnectionCardExtras, resources: Resources): String? {
    val at = extras.checkedAt ?: return null
    val now = extras.now ?: return null
    return resources.getString(R.string.connection_checked, ago(at, now, resources))
  }

  private fun arriving(extras: ConnectionCardExtras, resources: Resources): String? {
    val onPhone = extras.clipsOnPhone ?: return null
    val total = extras.clipsTotal ?: return null
    if (total <= 0 || onPhone >= total) return null
    return resources.getString(R.string.connection_clips_arriving, onPhone.coerceAtLeast(0), total)
  }

  private fun plural(resources: Resources, id: Int, count: Int): String = resources.getQuantityString(id, count, count)

  /** A bar with no percent: the work is going on (it slides only while motion is allowed). */
  const val WORKING: Float = -1f

  private const val BYTES_PER_MB = 1_000_000L
  private const val SECONDS_PER_MINUTE = 60L
  private const val MINUTES_PER_HOUR = 60L
  private const val HOURS_PER_DAY = 24L
}
