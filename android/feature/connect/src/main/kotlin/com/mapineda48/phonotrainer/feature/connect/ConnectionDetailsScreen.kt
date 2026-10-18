package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionCardExtras
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCard
import com.mapineda48.phonotrainer.core.ui.component.ConnectionStatusCardCopy
import com.mapineda48.phonotrainer.core.ui.component.ProbeOutcomeCopy
import kotlinx.coroutines.launch

/** What Connection details' controls ask for. */
internal class DetailsActions(
  val onBack: () -> Unit = {},
  /** A control of the status card that leads elsewhere ("Connect", "Connect again", "Enter the new address", …). */
  val onCard: (ConnectionAction) -> Unit = {},
  val onCheckNow: () -> Unit = {},
  /** "Copy diagnostics": the text to put on the clipboard (no address, no id, no credential). */
  val onCopyDiagnostics: (String) -> Unit = {},
)

/**
 * Connection details (spec 7.5 "Why?", F-22 "Connection details", design 10.2 P-07): `ill_no_computer` when the
 * computer is not reachable; the status card (9.17); the last check in words (`ProbeOutcomeCopy`, D-42's words for
 * phone access off) with "Check now"; "Your computer's address changed" when it did (spec 7.3 item 5); the "Why?"
 * checklist, numbered; the facts: the computer's address, "This phone on this Wi-Fi: {address}" (D-39, F-22-AC4), the
 * short server id, the API version, the pending counts and the background limit; the decoders, each hardware or
 * software (architecture 11.7); and "Copy diagnostics", whose text holds no address, no id and no credential.
 * "Why?" and "Details" on the card scroll to the checklist and the facts of this same page.
 */
@Composable
internal fun ConnectionDetailsScreen(
  state: DetailsUiState,
  platform: PlatformInfo,
  actions: DetailsActions,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val d = state.diagnostics
  val onCard: (ConnectionAction) -> Unit = { action ->
    when (action) {
      ConnectionAction.WHY -> scope.launch { listState.animateScrollToItem(itemIndex(state, WHY)) }
      ConnectionAction.DETAILS -> scope.launch { listState.animateScrollToItem(itemIndex(state, FACTS)) }
      else -> actions.onCard(action)
    }
  }
  ConnectPage(
    title = stringResource(R.string.details_title),
    onBack = actions.onBack,
    listTag = ConnectTags.DETAILS,
    modifier = modifier,
    state = listState,
  ) {
    items(sections(state), key = { it }) { section ->
      when (section) {
        LEDE -> Lede(stringResource(R.string.details_lede))
        ILLUSTRATION -> Illustration(PhonoIllustrations.NoComputer)
        CARD -> ConnectionStatusCard(
          status = state.status,
          sync = state.sync,
          counts = NO_COUNTS,
          transfers = null,
          onAction = onCard,
          extras = ConnectionCardExtras(now = state.now, checkedAt = d.lastProbeAt),
        )
        LAST_PROBE -> LastProbe(state, actions.onCheckNow)
        ADDRESS_CHANGED -> {
          val changed = d.addressChangedAt
          val now = state.now
          if (changed != null && now != null) {
            Notice(
              body = stringResource(R.string.details_address_changed, ConnectionStatusCardCopy.ago(changed, now, resources)),
              modifier = Modifier.testTag(ConnectTags.DETAILS_ADDRESS_CHANGED),
            )
          }
        }
        WHY -> Why()
        FACTS -> Facts(state)
        DECODERS -> Decoders(state)
        COPY -> CopyDiagnostics(state, platform, actions.onCopyDiagnostics)
      }
    }
  }
}

/** The sections of the page, in order; the illustration and the address change only when they apply. */
private fun sections(state: DetailsUiState): List<Int> = buildList {
  add(LEDE)
  if (state.status is ComputerStatus.NotReachable) add(ILLUSTRATION)
  add(CARD)
  add(LAST_PROBE)
  if (state.diagnostics.addressChangedAt != null) add(ADDRESS_CHANGED)
  add(WHY)
  add(FACTS)
  add(DECODERS)
  add(COPY)
}

private fun itemIndex(state: DetailsUiState, section: Int): Int = sections(state).indexOf(section).coerceAtLeast(0)

/** The last probe in words ("No answer after 3 s · 2 min ago") and "Check now" when a computer is paired. */
@Composable
private fun LastProbe(state: DetailsUiState, onCheckNow: () -> Unit) {
  val resources = LocalResources.current
  val d = state.diagnostics
  val words = remember(d.lastProbe, d.lastProbeAt, state.now, resources) {
    val probe = d.lastProbe
    val at = d.lastProbeAt
    val now = state.now
    when {
      probe == null -> resources.getString(R.string.details_never_checked)
      at != null && now != null ->
        resources.getString(R.string.details_last_probe, ProbeOutcomeCopy.of(probe, resources), ConnectionStatusCardCopy.ago(at, now, resources))
      else -> ProbeOutcomeCopy.of(probe, resources)
    }
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    GroupTitle(stringResource(R.string.details_last_probe_title))
    IconLine(PhonoIcons.ConnChecking, words, Modifier.testTag(ConnectTags.DETAILS_LAST_PROBE), live = true)
    if (state.paired) {
      PhonoButton(
        text = stringResource(R.string.details_check_now),
        onClick = onCheckNow,
        kind = PhonoButtonKind.SECONDARY,
        busy = state.checking,
        enabled = !state.checking,
        leadingIcon = PhonoIcons.Sync,
        modifier = Modifier.testTag(ConnectTags.DETAILS_CHECK_NOW),
      )
    }
  }
}

/** The "Why?" checklist (spec 7.5), numbered, the phone-access item as on Connect (D-42). */
@Composable
private fun Why() {
  val phoneOn = stringResource(R.string.connect_command_phone_on)
  val items = listOf(
    withCommand(stringResource(R.string.details_check_awake), ""),
    withCommand(stringResource(R.string.connect_check_phone_access, phoneOn), phoneOn),
    withCommand(stringResource(R.string.details_check_same_wifi), ""),
    withCommand(stringResource(R.string.connect_check_vpn), ""),
    withCommand(stringResource(R.string.connect_check_routers), ""),
  )
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    GroupTitle(stringResource(R.string.details_why_title))
    PaperCard(modifier = Modifier.fillMaxWidth().testTag(ConnectTags.DETAILS_WHY)) { NumberedList(items) }
  }
}

/** The facts (design 10.2 P-07), each a label over its value; addresses and the id can be selected. */
@Composable
private fun Facts(state: DetailsUiState) {
  val d = state.diagnostics
  val unknown = stringResource(R.string.details_fact_unknown)
  Column(
    modifier = Modifier.fillMaxWidth().testTag(ConnectTags.DETAILS_FACTS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    GroupTitle(stringResource(R.string.details_facts_title))
    FactRow(
      stringResource(R.string.details_fact_address),
      d.address?.let { "${it.host}:${it.port}" } ?: unknown,
      Modifier.testTag(ConnectTags.DETAILS_ADDRESS),
      selectable = d.address != null,
    )
    Hairline()
    FactRow(
      stringResource(R.string.details_fact_phone_address),
      d.phoneAddress ?: stringResource(R.string.details_fact_phone_address_none),
      Modifier.testTag(ConnectTags.DETAILS_PHONE_ADDRESS),
      selectable = d.phoneAddress != null,
    )
    Hairline()
    FactRow(
      stringResource(R.string.details_fact_server_id),
      d.shortServerId ?: unknown,
      Modifier.testTag(ConnectTags.DETAILS_SERVER_ID),
      selectable = d.shortServerId != null,
    )
    Hairline()
    FactRow(stringResource(R.string.details_fact_api), d.apiVersion?.toString() ?: unknown, Modifier.testTag(ConnectTags.DETAILS_API))
    Hairline()
    Column(Modifier.fillMaxWidth().testTag(ConnectTags.DETAILS_COUNTS)) {
      FactRow(stringResource(R.string.details_fact_waiting), d.waitingRequests.toString())
      FactRow(stringResource(R.string.details_fact_on_computer), d.requestsOnComputer.toString())
      FactRow(stringResource(R.string.details_fact_wanted), d.wantedDownloads.toString())
    }
    Hairline()
    FactRow(
      stringResource(R.string.details_fact_background),
      stringResource(if (d.backgroundRestricted) R.string.details_background_limited else R.string.details_background_free),
      Modifier.testTag(ConnectTags.DETAILS_BACKGROUND),
    )
  }
}

/** "Video this phone can play": one line per codec, hardware or software (architecture 11.7). */
@Composable
private fun Decoders(state: DetailsUiState) {
  val resources = LocalResources.current
  val decoders = state.decoders ?: return
  Column(
    modifier = Modifier.fillMaxWidth().testTag(ConnectTags.DETAILS_DECODERS),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    GroupTitle(stringResource(R.string.details_decoders_title))
    if (decoders.isEmpty()) {
      PhonoText(stringResource(R.string.details_decoders_none), style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink)
    }
    decoders.forEach { decoder ->
      val words = remember(decoder, resources) { DecoderWords.of(decoder, resources) }
      IconLine(PhonoIcons.AvailVideo, words)
    }
  }
}

/** "Copy diagnostics" (F-22-AC4) and what the copied text holds. */
@Composable
private fun CopyDiagnostics(state: DetailsUiState, platform: PlatformInfo, onCopy: (String) -> Unit) {
  val resources = LocalResources.current
  val hint = stringResource(R.string.details_copy_hint)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Hairline()
    PhonoButton(
      text = stringResource(R.string.details_copy),
      onClick = { onCopy(DiagnosticsText.build(state, platform, resources)) },
      kind = PhonoButtonKind.SECONDARY,
      leadingIcon = PhonoIcons.Log,
      fillWidth = true,
      modifier = Modifier.testTag(ConnectTags.DETAILS_COPY),
    )
    if (state.copied) {
      IconLine(PhonoIcons.StepDone, stringResource(R.string.details_copied), Modifier.testTag(ConnectTags.DETAILS_COPIED), live = true)
    } else {
      PhonoText(hint, style = MaterialTheme.typography.bodySmall, color = PhonoTheme.colors.ink2)
    }
  }
}

private val NO_COUNTS = RequestCounts(waiting = 0, onComputer = 0, needsAttention = 0, readyNew = 0)

private const val LEDE = 0
private const val ILLUSTRATION = 1
private const val CARD = 2
private const val LAST_PROBE = 3
private const val ADDRESS_CHANGED = 4
private const val WHY = 5
private const val FACTS = 6
private const val DECODERS = 7
private const val COPY = 8
