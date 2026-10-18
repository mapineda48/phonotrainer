package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoProgressBar
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedControl
import com.mapineda48.phonotrainer.core.designsystem.component.SegmentedOption
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ArtifactLocalState
import com.mapineda48.phonotrainer.core.domain.model.ArtifactProgress
import com.mapineda48.phonotrainer.core.domain.model.ClipStorage
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.remote.ArtifactRole
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.component.ClipCardCopy
import com.mapineda48.phonotrainer.core.ui.component.IntrinsicFlowRow

/** What Storage's controls ask for. */
internal class StorageActions(
  val onBack: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onBudget: (StorageBudget) -> Unit = {},
  val onDownloadVideos: (Boolean) -> Unit = {},
  val onRemoveVideo: (JobId) -> Unit = {},
  val onRemoveFromPhone: (JobId) -> Unit = {},
  val onKeep: (JobId, Boolean) -> Unit = { _, _ -> },
  /** "Try again" after a file was damaged on the way (spec F-21 Error). */
  val onDownloadAgain: (JobId) -> Unit = {},
  val onRemoveAllVideos: () -> Unit = {},
  val onBatterySettings: () -> Unit = {},
)

/**
 * Settings > Storage (spec F-21, design 9.20): the total against the budget ("1.4 GB of 2 GB") and the free space,
 * the meter with its table twin, the notices of the limits and of a paused sync, the budget, "Download videos
 * automatically", the clips on this phone largest first with their three actions, and "Remove all videos". The two
 * removals ask first ("Remove “{title}” from this phone?" with the words of F-05 item 5; "Remove all videos?").
 */
@Composable
internal fun StorageScreen(state: StorageUiState, actions: StorageActions, modifier: Modifier = Modifier) {
  var removing by rememberSaveable { mutableStateOf<String?>(null) }
  var removingAll by rememberSaveable { mutableStateOf(false) }
  SettingsPage(
    title = stringResource(R.string.storage_title),
    lede = stringResource(R.string.storage_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.STORAGE_LIST,
    modifier = modifier,
  ) {
    when (val read = state.read) {
      StorageRead.Loading -> item(key = "loading") {
        Column(Modifier.testTag(SettingsTags.STORAGE_LOADING)) {
          LoadingGate(loading = true, label = stringResource(R.string.storage_loading)) {}
        }
      }
      StorageRead.Failed -> item(key = "error") {
        Notice(
          body = stringResource(R.string.storage_error),
          kind = NoticeKind.CAUTION,
          modifier = Modifier.testTag(SettingsTags.STORAGE_ERROR),
          action = {
            PhonoButton(
              text = stringResource(R.string.storage_retry),
              onClick = actions.onRetry,
              kind = PhonoButtonKind.SECONDARY,
              modifier = Modifier.testTag(SettingsTags.STORAGE_RETRY),
            )
          },
        )
      }
      is StorageRead.Ready -> ready(read.usage, state, actions, onRemove = { removing = it.value }, onRemoveAll = { removingAll = true })
    }
  }
  val clip = removing?.let { id -> state.usage?.perClip?.firstOrNull { it.id.value == id } }
  if (clip != null) {
    RemoveFromPhoneDialog(
      title = clip.title,
      onConfirm = {
        removing = null
        actions.onRemoveFromPhone(clip.id)
      },
      onDismiss = { removing = null },
    )
  }
  if (removingAll) {
    ConfirmDialog(
      title = stringResource(R.string.storage_remove_all_title),
      body = stringResource(R.string.storage_remove_all_body),
      cancelLabel = stringResource(R.string.storage_remove_all_cancel),
      confirmLabel = stringResource(R.string.storage_remove_all_confirm),
      onConfirm = {
        removingAll = false
        actions.onRemoveAllVideos()
      },
      onDismiss = { removingAll = false },
      tags = REMOVE_ALL_TAGS,
    )
  }
}

private fun LazyListScope.ready(
  usage: StorageUsage,
  state: StorageUiState,
  actions: StorageActions,
  onRemove: (JobId) -> Unit,
  onRemoveAll: () -> Unit,
) {
  item(key = "total") { Total(usage) }
  notices(usage, state, actions)
  item(key = "meter") { StorageMeter(usage) }
  item(key = "budget") {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      SettingLabel(stringResource(R.string.storage_budget), body = stringResource(R.string.storage_budget_body))
      SegmentedControl(
        options = listOf(
          SegmentedOption(stringResource(R.string.storage_budget_1)),
          SegmentedOption(stringResource(R.string.storage_budget_2)),
          SegmentedOption(stringResource(R.string.storage_budget_5)),
          SegmentedOption(stringResource(R.string.storage_budget_free)),
        ),
        selectedIndex = BUDGETS.indexOf(usage.budget).coerceAtLeast(0),
        onSelect = { actions.onBudget(BUDGETS[it]) },
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.STORAGE_BUDGET),
      )
    }
  }
  item(key = "download-videos") {
    PhonoSwitch(
      label = stringResource(R.string.storage_download_videos),
      description = stringResource(R.string.storage_download_videos_body),
      checked = state.downloadVideos,
      onCheckedChange = actions.onDownloadVideos,
      modifier = Modifier.testTag(SettingsTags.STORAGE_DOWNLOAD_VIDEOS),
    )
  }
  item(key = "per-clip") {
    Kicker(
      stringResource(R.string.storage_per_clip),
      count = stringResource(R.string.storage_per_clip_order),
      icon = PhonoIcons.AvailOnPhone,
      modifier = Modifier.padding(top = PhonoSpacing.s12),
    )
  }
  if (usage.perClip.isEmpty()) {
    item(key = "empty") {
      EmptyState(
        illustration = null,
        title = stringResource(R.string.storage_empty_title),
        body = stringResource(R.string.storage_empty_body),
        modifier = Modifier.testTag(SettingsTags.STORAGE_EMPTY),
      )
    }
  }
  // Largest first (spec F-21), whatever order the repository gives.
  val clips = usage.perClip.sortedByDescending { it.studyPackBytes + it.videoBytes }
  items(clips, key = { "clip:" + it.id.value }) { clip ->
    ClipStorageCard(
      clip,
      artifacts = state.artifacts[clip.id].orEmpty(),
      busy = clip.id in state.busy,
      actions = actions,
      onRemove = { onRemove(clip.id) },
    )
  }
  if (usage.videosBytes > 0 || usage.perClip.any { it.videoBytes > 0 }) {
    item(key = "remove-all") {
      PhonoButton(
        text = stringResource(R.string.storage_remove_all),
        onClick = onRemoveAll,
        kind = PhonoButtonKind.DANGER,
        fillWidth = true,
        modifier = Modifier.padding(top = PhonoSpacing.s12).testTag(SettingsTags.STORAGE_REMOVE_ALL),
      )
    }
  }
}

private fun LazyListScope.notices(usage: StorageUsage, state: StorageUiState, actions: StorageActions) {
  if (usage.belowFloor || state.paused == PauseReason.FREE_SPACE_FLOOR) {
    item(key = "floor") {
      Notice(stringResource(R.string.storage_floor), kind = NoticeKind.CAUTION, modifier = Modifier.testTag(SettingsTags.STORAGE_FLOOR))
    }
  } else if (usage.budgetReached || state.paused == PauseReason.BUDGET_REACHED) {
    item(key = "budget-reached") {
      Notice(
        stringResource(R.string.storage_budget_reached),
        kind = NoticeKind.CAUTION,
        modifier = Modifier.testTag(SettingsTags.STORAGE_BUDGET_REACHED),
      )
    }
  }
  if (state.paused == PauseReason.NOT_CONNECTED) {
    item(key = "paused") {
      Notice(stringResource(R.string.storage_paused), modifier = Modifier.testTag(SettingsTags.STORAGE_PAUSED))
    }
  }
  if (state.backgroundRestricted) {
    item(key = "background") {
      Notice(
        stringResource(R.string.storage_background),
        kind = NoticeKind.CAUTION,
        modifier = Modifier.testTag(SettingsTags.STORAGE_BACKGROUND),
        action = {
          PhonoButton(
            text = stringResource(R.string.storage_battery),
            onClick = actions.onBatterySettings,
            kind = PhonoButtonKind.SECONDARY,
            leadingIcon = PhonoIcons.BackgroundLimited,
            modifier = Modifier.testTag(SettingsTags.STORAGE_BATTERY),
          )
        },
      )
    }
  }
}

/** "1.4 GB of 2 GB" (displaySmall) and the free space under it (design 9.20). */
@Composable
private fun Total(usage: StorageUsage) {
  val resources = LocalResources.current
  val colors = PhonoTheme.colors
  val used = ClipCardCopy.bytes(usage.clipsBytes + usage.videosBytes + usage.waitingCopiesBytes + usage.cacheBytes, resources)
  val total = when (val budget = usage.budget) {
    is StorageBudget.Limit -> stringResource(R.string.storage_total, used, ClipCardCopy.bytes(budget.bytes, resources))
    StorageBudget.FreeSpaceOnly -> stringResource(R.string.storage_total_unlimited, used)
  }
  Column(
    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.STORAGE_TOTAL).semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    PhonoText(total, style = MaterialTheme.typography.displaySmall, color = colors.ink, modifier = Modifier.semantics { heading() })
    PhonoText(
      stringResource(R.string.storage_free, ClipCardCopy.bytes(usage.freeBytes.coerceAtLeast(0), resources)),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
    )
  }
}

/** One clip on this phone (spec F-21): its sizes, whether it is kept, and its three actions. */
@Composable
private fun ClipStorageCard(clip: ClipStorage, artifacts: List<ArtifactProgress>, busy: Boolean, actions: StorageActions, onRemove: () -> Unit) {
  val resources = LocalResources.current
  val colors = PhonoTheme.colors
  PhonoCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTags.storageClip(clip.id))) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
      PhonoText(clip.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      PhonoText(
        stringResource(R.string.storage_clip_data, ClipCardCopy.bytes(clip.studyPackBytes, resources)),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
      )
      if (clip.videoBytes > 0) {
        PhonoText(
          stringResource(R.string.storage_clip_video, ClipCardCopy.bytes(clip.videoBytes, resources)),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
        )
      }
      DownloadLine(artifacts, Modifier.testTag(SettingsTags.storageDownload(clip.id)))
      if (artifacts.any { it.verifyFailures >= DAMAGED_AFTER }) {
        Notice(
          body = stringResource(R.string.storage_damaged),
          kind = NoticeKind.CAUTION,
          modifier = Modifier.testTag(SettingsTags.storageDamaged(clip.id)),
          action = {
            PhonoButton(
              text = stringResource(R.string.storage_try_again),
              onClick = { actions.onDownloadAgain(clip.id) },
              kind = PhonoButtonKind.SECONDARY,
              enabled = !busy,
              modifier = Modifier.testTag(SettingsTags.storageTryAgain(clip.id)),
            )
          },
        )
      }
      if (clip.kept) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
          Icon(painterResource(PhonoIcons.Keep), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
          PhonoText(stringResource(R.string.storage_clip_kept), style = MaterialTheme.typography.labelLarge, color = colors.ink)
        }
      }
      // Not Compose's FlowRow: inside a card (which takes its content's intrinsic height) it claims fewer lines than
      // it lays out on a device and the actions are cut off (WP-21's finding); IntrinsicFlowRow measures what it takes.
      IntrinsicFlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s4),
        horizontalSpacing = PhonoSpacing.s8,
        verticalSpacing = PhonoSpacing.s4,
      ) {
        if (clip.videoBytes > 0) {
          PhonoButton(
            text = stringResource(R.string.storage_remove_video),
            onClick = { actions.onRemoveVideo(clip.id) },
            kind = PhonoButtonKind.SECONDARY,
            leadingIcon = PhonoIcons.AvailVideo,
            enabled = !busy,
            modifier = Modifier.testTag(SettingsTags.storageRemoveVideo(clip.id)),
          )
        }
        PhonoButton(
          text = stringResource(if (clip.kept) R.string.storage_stop_keeping else R.string.storage_keep),
          onClick = { actions.onKeep(clip.id, !clip.kept) },
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.Keep,
          enabled = !busy,
          modifier = Modifier.testTag(SettingsTags.storageKeep(clip.id)),
        )
        PhonoButton(
          text = stringResource(R.string.storage_remove),
          onClick = onRemove,
          kind = PhonoButtonKind.QUIET,
          leadingIcon = PhonoIcons.Remove,
          enabled = !busy,
          modifier = Modifier.testTag(SettingsTags.storageRemove(clip.id)),
        )
      }
    }
  }
}

/** The files the per-clip download line speaks of, in its order (design 9.20). */
private val LINE_ROLES = listOf(ArtifactRole.CLIP_DATA, ArtifactRole.AUDIO_MIX, ArtifactRole.VIDEO)

/** A file verified this many times without matching is damaged on the way (architecture 7; `ArtifactProgress`). */
internal const val DAMAGED_AFTER = 2

/**
 * The parts of design 9.20's per-clip download line ("Clip data ✓ · Audio 60 % · Video waiting"), in its order; empty
 * when every file the clip needs is on this phone. A video this phone does not want (downloads off, or removed by the
 * learner) is not waited for, so it is left out.
 */
internal fun downloadParts(artifacts: List<ArtifactProgress>): List<ArtifactProgress> {
  val shown = artifacts
    .filter { it.role in LINE_ROLES }
    .filterNot { it.state == ArtifactLocalState.ABSENT && it.pauseReason == PauseReason.NOT_WANTED }
    .sortedBy { LINE_ROLES.indexOf(it.role) }
  return if (shown.all { it.state == ArtifactLocalState.READY }) emptyList() else shown
}

/** The per-clip download line with its thin bar, while a file of the clip is still on its way. */
@Composable
private fun DownloadLine(artifacts: List<ArtifactProgress>, modifier: Modifier = Modifier) {
  val parts = downloadParts(artifacts)
  if (parts.isEmpty()) return
  val shown = parts.map { artifactWords(it, spoken = false) }.joinToString(" · ")
  val spoken = parts.map { artifactWords(it, spoken = true) }.joinToString(", ")
  val progress = parts.map {
    when (it.state) {
      ArtifactLocalState.READY -> 1f
      ArtifactLocalState.DOWNLOADING -> (it.percent ?: 0) / 100f
      else -> 0f
    }
  }.average().toFloat()
  Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    PhonoText(shown, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink)
    PhonoProgressBar(progress = progress)
  }
}

@Composable
private fun artifactWords(artifact: ArtifactProgress, spoken: Boolean): String {
  val name = stringResource(
    when (artifact.role) {
      ArtifactRole.CLIP_DATA -> R.string.storage_artifact_clip_data
      ArtifactRole.VIDEO -> R.string.storage_artifact_video
      else -> R.string.storage_artifact_audio
    },
  )
  val percent = artifact.percent
  return when (artifact.state) {
    ArtifactLocalState.READY -> stringResource(if (spoken) R.string.storage_artifact_ready_spoken else R.string.storage_artifact_ready, name)
    ArtifactLocalState.DOWNLOADING -> if (percent != null) {
      stringResource(if (spoken) R.string.storage_artifact_percent_spoken else R.string.storage_artifact_percent, name, percent)
    } else {
      stringResource(R.string.storage_artifact_downloading, name)
    }
    ArtifactLocalState.QUEUED, ArtifactLocalState.ABSENT -> stringResource(R.string.storage_artifact_waiting, name)
    ArtifactLocalState.PAUSED -> stringResource(R.string.storage_artifact_paused, name)
    ArtifactLocalState.FAILED -> stringResource(R.string.storage_artifact_failed, name)
  }
}

/** The test tags of "Remove “{title}” from this phone?". */
internal val REMOVE_TAGS = DialogTags(
  dialog = SettingsTags.REMOVE_DIALOG,
  title = SettingsTags.REMOVE_TITLE,
  body = SettingsTags.REMOVE_BODY,
  cancel = SettingsTags.REMOVE_CANCEL,
  confirm = SettingsTags.REMOVE_CONFIRM,
)

/** The test tags of "Remove all videos?". */
internal val REMOVE_ALL_TAGS = DialogTags(
  dialog = SettingsTags.REMOVE_ALL_DIALOG,
  title = SettingsTags.REMOVE_ALL_TITLE,
  body = SettingsTags.REMOVE_ALL_BODY,
  cancel = SettingsTags.REMOVE_ALL_CANCEL,
  confirm = SettingsTags.REMOVE_ALL_CONFIRM,
)

/**
 * "Remove “{title}” from this phone?" (spec F-05 item 5 and F-05-AC11, design 9.23; the same words as the Clip page's
 * and the Library's): "Cancel" first, then "Remove from this phone" (primary: the clip can be downloaded again).
 */
@Composable
internal fun RemoveFromPhoneDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  ConfirmDialog(
    title = stringResource(R.string.storage_remove_title, title),
    body = stringResource(R.string.storage_remove_body),
    cancelLabel = stringResource(R.string.storage_remove_cancel),
    confirmLabel = stringResource(R.string.storage_remove_confirm),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    tags = REMOVE_TAGS,
  )
}
