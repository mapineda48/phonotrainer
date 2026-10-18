package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoSheet

/** What the licences screen's controls ask for. */
internal class LicencesActions(
  val onBack: () -> Unit = {},
  val onRetry: () -> Unit = {},
  val onOpen: (LicenceEntry) -> Unit = {},
  val onClose: () -> Unit = {},
)

/**
 * Open-source licences (spec F-24, D-50; design 10.2 P-12): the app, then "Fonts and icons" (the three font families
 * and the Lucide set, from `bundled.json`), then every runtime library of the build's report, each row with its version
 * and licence; a row opens the licence's text in a sheet. Everything is read from the APK (F-24-AC1).
 */
@Composable
internal fun LicencesScreen(state: LicencesUiState, actions: LicencesActions, modifier: Modifier = Modifier) {
  SettingsPage(
    title = stringResource(R.string.licences_title),
    lede = stringResource(R.string.licences_lede),
    onBack = actions.onBack,
    listTag = SettingsTags.LICENCES_LIST,
    modifier = modifier,
  ) {
    when (val read = state.read) {
      LicencesRead.Loading -> item(key = "loading") {
        Column(Modifier.testTag(SettingsTags.LICENCES_LOADING)) {
          LoadingGate(loading = true, label = stringResource(R.string.licences_loading)) {}
        }
      }
      LicencesRead.Failed -> item(key = "error") {
        Notice(
          body = stringResource(R.string.licences_error),
          kind = NoticeKind.CAUTION,
          modifier = Modifier.testTag(SettingsTags.LICENCES_ERROR),
          action = {
            PhonoButton(stringResource(R.string.licences_retry), onClick = actions.onRetry, kind = PhonoButtonKind.SECONDARY)
          },
        )
      }
      is LicencesRead.Ready -> catalogue(read.catalogue, actions)
    }
  }
  state.open?.let { open -> LicenceSheet(open, onDismiss = actions.onClose) }
}

private fun LazyListScope.catalogue(catalogue: LicenceCatalogue, actions: LicencesActions) {
  item(key = "app-head") { Kicker(stringResource(R.string.licences_app), icon = PhonoIcons.Licences) }
  item(key = catalogue.app.id) { LicenceRow(catalogue.app, actions.onOpen) }
  if (catalogue.bundled.isNotEmpty()) {
    item(key = "bundled-head") {
      Kicker(
        stringResource(R.string.licences_bundled),
        count = catalogue.bundled.size.toString(),
        icon = PhonoIcons.Colors,
        modifier = Modifier.padding(top = PhonoSpacing.s12),
      )
    }
    items(catalogue.bundled, key = { it.id }) { LicenceRow(it, actions.onOpen) }
  }
  if (catalogue.libraries.isNotEmpty()) {
    item(key = "libraries-head") {
      Kicker(
        stringResource(R.string.licences_libraries),
        count = catalogue.libraries.size.toString(),
        icon = PhonoIcons.Glossary,
        modifier = Modifier.padding(top = PhonoSpacing.s12),
      )
    }
    items(catalogue.libraries, key = { it.id }) { LicenceRow(it, actions.onOpen) }
  }
}

/** The words of an entry's row under its name: "1.13.0 · Apache-2.0", "Font · 2 font files · OFL-1.1". */
@Composable
internal fun licenceLine(entry: LicenceEntry): String {
  val resources = LocalResources.current
  val parts = buildList {
    when (entry.kind) {
      LicenceKind.FONT -> {
        add(stringResource(R.string.licences_font))
        entry.files?.let { add(resources.getQuantityString(R.plurals.licences_font_files, it, it)) }
      }
      LicenceKind.ICONS -> {
        add(stringResource(R.string.licences_icons))
        entry.icons?.let { add(resources.getQuantityString(R.plurals.licences_icon_count, it, it)) }
      }
      LicenceKind.APP, LicenceKind.LIBRARY -> Unit
    }
    entry.version?.takeIf { it.isNotBlank() }?.let(::add)
    add(entry.licenceLine)
  }
  return parts.joinToString(" · ")
}

/** One entry: its name, version and licence, and who made it; the whole row opens the licence's text. */
@Composable
private fun LicenceRow(entry: LicenceEntry, onOpen: (LicenceEntry) -> Unit) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .focusRing(shape)
      .heightIn(min = PhonoSizes.row)
      .clip(shape)
      .clickable(role = Role.Button, onClickLabel = stringResource(R.string.about_read_licence)) { onOpen(entry) }
      .testTag(SettingsTags.licence(entry.id))
      .padding(vertical = PhonoSpacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
      PhonoText(entry.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
      PhonoText(licenceLine(entry), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      entry.holder?.let { PhonoText(it, style = MaterialTheme.typography.bodySmall, color = colors.ink2) }
    }
    Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconBar))
  }
}

/**
 * The licence sheet: the entry's name and version, who holds it and where it comes from (as text), then each
 * licence's name and its text in paragraphs that reflow to the screen; a standard MIT or BSD text says that its
 * copyright line stands for the holder named above.
 */
@Composable
internal fun LicenceSheet(open: LicenceTextUi, onDismiss: () -> Unit) {
  PhonoSheet(onDismiss = onDismiss, title = open.entry.name, modifier = Modifier.testTag(SettingsTags.LICENCE_SHEET)) {
    LicenceText(open, onDismiss)
  }
}

/** The sheet's content on its own (previews and screen tests). */
@Composable
internal fun LicenceText(open: LicenceTextUi, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  val entry = open.entry
  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag(SettingsTags.LICENCE_TEXT),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    item(key = "head") {
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        PhonoText(
          listOfNotNull(entry.name, entry.version).joinToString(" "),
          style = MaterialTheme.typography.headlineSmall,
          color = colors.ink,
          modifier = Modifier.semantics { heading() },
        )
        entry.holder?.let { PhonoText(stringResource(R.string.licences_by, it), style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
        entry.source?.let { PhonoText(stringResource(R.string.licences_source, it), style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
        PhonoButton(
          text = stringResource(R.string.licences_close),
          onClick = onClose,
          kind = PhonoButtonKind.QUIET,
          leadingIcon = PhonoIcons.Close,
          modifier = Modifier.testTag(SettingsTags.LICENCE_CLOSE),
        )
      }
    }
    val sections = open.sections
    if (sections == null) {
      item(key = "reading") {
        PhonoText(stringResource(R.string.licences_reading), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
    } else {
      sections.forEachIndexed { index, section ->
        item(key = "licence:$index") {
          PhonoText(
            stringResource(R.string.licences_licence, section.licence.name),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            modifier = Modifier.padding(top = PhonoSpacing.s8).semantics { heading() },
          )
        }
        val paragraphs = section.paragraphs
        if (paragraphs == null) {
          item(key = "missing:$index") {
            val url = section.licence.url
            PhonoText(
              if (url != null) stringResource(R.string.licences_missing, url) else stringResource(R.string.licences_text_missing),
              style = MaterialTheme.typography.bodyMedium,
              color = colors.ink2,
            )
          }
        } else {
          if (section.licence.template) {
            item(key = "template:$index") { Notice(stringResource(R.string.licences_template)) }
          }
          paragraphs.forEachIndexed { number, paragraph ->
            item(key = "p:$index:$number") {
              PhonoText(paragraph, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
          }
        }
      }
    }
    item(key = "end") { Row(Modifier.padding(bottom = PhonoSpacing.s24)) {} }
  }
}
