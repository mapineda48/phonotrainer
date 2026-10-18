package com.mapineda48.phonotrainer.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTopBar
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.component.paperDepth
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

/**
 * The frame of every page of :feature:settings (design 9.24, 10.2 P-12): the top bar with the title and Back, then
 * one vertical list that starts with the one-sentence lede (F-25 item 1). One list scrolls the whole page, so its
 * saveable state keeps the position across Back.
 */
@Composable
internal fun SettingsPage(
  title: String,
  lede: String,
  onBack: () -> Unit,
  listTag: String,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  content: LazyListScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .semantics { paneTitle = title }
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    PhonoTopBar(
      title = title,
      onBack = onBack,
      backLabel = stringResource(R.string.settings_back),
      scrolled = state.canScrollBackward,
    )
    LazyColumn(
      state = state,
      modifier = Modifier.fillMaxSize().testTag(listTag),
      contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      item(key = "lede") {
        PhonoText(lede, style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink2)
      }
      content()
    }
  }
}

/** A setting's name and, optionally, one sentence about it, above its control (render `22`). */
@Composable
internal fun SettingLabel(title: String, modifier: Modifier = Modifier, body: String? = null) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth().padding(top = PhonoSpacing.s8), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    PhonoText(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
    body?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
  }
}

/**
 * A row that opens a sub-page or carries out one action (design 9.25): at least 56 dp, a 40 dp icon well (a rounded
 * square with an ink ring), the title, an optional status line or sentence, and a chevron (or [trailing]). The whole
 * row is one target; TalkBack reads its words once.
 */
@Composable
internal fun SettingsRow(
  @DrawableRes icon: Int,
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  status: String? = null,
  @DrawableRes trailing: Int? = PhonoIcons.ChevronRight,
  enabled: Boolean = true,
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .focusRing(shape)
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(vertical = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s14),
  ) {
    Box(
      modifier = Modifier
        .size(ICON_WELL)
        .border(PhonoSizes.ring, colors.ink, RoundedCornerShape(PhonoRadius.medium)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
      PhonoText(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      status?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
    }
    trailing?.let { Icon(painterResource(it), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconBar)) }
  }
}

/** A hairline between rows of one group. Decorative. */
@Composable
internal fun RowRule(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(PhonoSizes.hairline).background(PhonoTheme.colors.hairline))
}

/** A fact as a label and its value (Your computer, About): the value in ink, the label above it in ink2. */
@Composable
internal fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = PhonoSpacing.s4),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2),
  ) {
    PhonoText(label, style = MaterialTheme.typography.labelLarge, color = colors.ink2)
    PhonoText(value, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
  }
}

/** The test tags of one confirmation dialog: the card, its title, body and two buttons. */
internal data class DialogTags(val dialog: String, val title: String, val body: String, val cancel: String, val confirm: String)

/**
 * A destructive confirmation (design 9.23): a sheet with 28 dp corners and the floating depth, the title in Newsreader,
 * the body, optional consequence lines under a hairline (v1-complete adds them to "Forget this computer?", design
 * 9.23 rule (3)), and the buttons at the end: "Cancel" (quiet) takes the focus first, and the confirm is the primary
 * button, because every confirmation of this module stays on this phone (design 9.23 rule (1)). An outside tap does
 * not dismiss it; Back does.
 */
@Composable
internal fun ConfirmDialog(
  title: String,
  body: String,
  cancelLabel: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  tags: DialogTags,
  consequences: List<String> = emptyList(),
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
    ConfirmCard(title, body, cancelLabel, confirmLabel, onConfirm, onDismiss, tags, consequences = consequences)
  }
}

/** The confirmation's card on its own, for previews and screen tests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConfirmCard(
  title: String,
  body: String,
  cancelLabel: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  tags: DialogTags,
  modifier: Modifier = Modifier,
  consequences: List<String> = emptyList(),
) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.extraLarge)
  val cancelFocus = remember { FocusRequester() }
  Column(
    modifier = modifier
      .widthIn(max = DIALOG_MAX_WIDTH)
      .paperDepth(colors, shape, floating = true)
      .clip(shape)
      .background(colors.sheet)
      .semantics { paneTitle = title }
      .testTag(tags.dialog)
      .padding(PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
  ) {
    // The words scroll above the buttons at large text, so the buttons are always reachable.
    Column(
      modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
    ) {
      PhonoText(title, style = PhonoTheme.type.dialogTitle, color = colors.ink, modifier = Modifier.testTag(tags.title))
      PhonoText(body, style = MaterialTheme.typography.bodyLarge, color = colors.ink2, modifier = Modifier.testTag(tags.body))
      consequences.forEach { line -> Consequence(line) }
    }
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      PhonoButton(
        text = cancelLabel,
        onClick = onDismiss,
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.focusRequester(cancelFocus).testTag(tags.cancel),
      )
      PhonoButton(
        text = confirmLabel,
        onClick = onConfirm,
        kind = PhonoButtonKind.PRIMARY,
        modifier = Modifier.testTag(tags.confirm),
      )
    }
  }
  LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

/** One consequence line of a confirmation (design 9.23 rule (3)): a hairline above, read after the body. */
@Composable
private fun ColumnScope.Consequence(text: String) {
  RowRule()
  PhonoText(text, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
}

private val ICON_WELL = 40.dp
private val DIALOG_MAX_WIDTH = 560.dp
