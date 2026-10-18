package com.mapineda48.phonotrainer.feature.learn

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.IconWell
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/** What Learn's actions do (the route wires them to the shell's navigator). */
internal data class LearnActions(
  val onOpenChange: (ChangeLabel) -> Unit = {},
  val onIpaChart: () -> Unit = {},
  val onGlossary: () -> Unit = {},
  val onInsights: () -> Unit = {},
)

/**
 * Learn (T3, spec F-15 item 1, design 10.1 and 9.25, render `12`): the title, double rule and lede; "How to study a
 * change" with its three numbered steps; the entry tiles (IPA chart, Words we use, and Insights when it exists); one
 * section per family (its icon, its name in its line style and what the line says) with its change cards, then the
 * whole-word changes. A card shows its icon, its name in its line style, its description with IPA, the practice badge
 * and register, and the short count ("34 shorts in your clips", D-48) or "Not in your clips yet". The text is there at
 * once; the counts appear when the phone's index has been read.
 */
@Composable
internal fun LearnScreen(state: LearnUiState, actions: LearnActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.learn_title)
  val listState = rememberLazyListState()
  LazyColumn(
    state = listState,
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .testTag(LearnTags.LIST),
    contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    item(key = "header") { TabRootHeader(title = title, lede = stringResource(R.string.learn_lede)) }
    item(key = "how") { HowToStudy() }
    item(key = "tiles") { EntryTiles(state, actions) }
    countNotes(state)
    state.sections.forEach { section -> section(section, state.counts, actions) }
  }
}

/** The partial line and the error line of the counts (F-15 States), above the sections. */
private fun LazyListScope.countNotes(state: LearnUiState) {
  if (state.counts == CountsUi.Failed) {
    item(key = "counts-failed") { CountsFailedLine() }
  } else if (state.partial && state.counts is CountsUi.Ready) {
    item(key = "counted-here") {
      PhonoText(
        stringResource(R.string.learn_counted_here),
        style = MaterialTheme.typography.bodySmall,
        color = PhonoTheme.colors.ink2,
        modifier = Modifier.testTag(LearnTags.COUNTED_HERE),
      )
    }
  }
}

private fun LazyListScope.section(section: FamilySectionUi, counts: CountsUi, actions: LearnActions) {
  val name = section.family?.name ?: WHOLE
  item(key = "family:$name") {
    FamilyHeader(
      family = section.family,
      title = section.title ?: stringResource(R.string.learn_whole_word),
      modifier = Modifier.testTag(LearnTags.family(name)),
    )
  }
  items(section.cards, key = { "card:" + it.label.value }) { card ->
    ChangeCard(card, counts, onOpen = { actions.onOpenChange(card.label) })
  }
}

private const val WHOLE = "WHOLE"

/** "How to study a change" (spec F-15 item 1, web's three steps adapted). */
@Composable
private fun HowToStudy() {
  val colors = PhonoTheme.colors
  PaperCard(modifier = Modifier.fillMaxWidth().testTag(LearnTags.HOW)) {
    Kicker(stringResource(R.string.learn_how_title), modifier = Modifier.padding(bottom = PhonoSpacing.s8))
    val steps = listOf(R.string.learn_how_1, R.string.learn_how_2, R.string.learn_how_3)
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      steps.forEachIndexed { index, step ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
          Box(
            modifier = Modifier.size(StepCircle).border(PhonoSizes.ring, colors.ink, CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            PhonoText(
              (index + 1).toString(),
              style = PhonoTheme.type.accentItalic,
              color = colors.ink,
              textAlign = TextAlign.Center,
            )
          }
          PhonoText(
            stringResource(step),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            modifier = Modifier.weight(1f).padding(top = PhonoSpacing.s4),
          )
        }
      }
    }
  }
}

private val StepCircle = 32.dp

/**
 * The entry tiles (design 10.1 T3): IPA chart, Words we use and, when `:feature:insights` provides it, Insights. In one
 * row at normal text; one per line at large text, so a word is never cut (design A-06).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryTiles(state: LearnUiState, actions: LearnActions) {
  val large = LocalDensity.current.fontScale >= LARGE_TEXT
  val tiles = buildList {
    add(Tile(stringResource(R.string.learn_link_ipa), PhonoIcons.IpaChart, LearnTags.LINK_IPA, null, actions.onIpaChart))
    add(Tile(stringResource(R.string.learn_link_glossary), PhonoIcons.Glossary, LearnTags.LINK_GLOSSARY, null, actions.onGlossary))
    state.insights?.let {
      add(Tile(stringResource(R.string.learn_link_insights), PhonoIcons.Insights, LearnTags.LINK_INSIGHTS, it.teaser, actions.onInsights))
    }
  }
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    maxItemsInEachRow = if (large) 1 else tiles.size,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap),
  ) {
    tiles.forEach { tile ->
      PaperCard(
        modifier = Modifier.weight(1f).heightIn(min = PhonoSizes.minTarget).testTag(tile.tag),
        onClick = tile.onClick,
      ) {
        IconWell(tile.icon, size = 44.dp)
        PhonoText(
          tile.title,
          style = MaterialTheme.typography.titleMedium,
          color = PhonoTheme.colors.ink,
          modifier = Modifier.padding(top = PhonoSpacing.s8),
        )
        tile.teaser?.let {
          PhonoText(it, style = MaterialTheme.typography.bodySmall, color = PhonoTheme.colors.ink2)
        }
      }
    }
  }
}

private class Tile(val title: String, @param:DrawableRes val icon: Int, val tag: String, val teaser: String?, val onClick: () -> Unit)

/** Font scales from design 11's 1.3 up count as large text. */
internal const val LARGE_TEXT = 1.3f

/** A change card (design 9.25, render `12`). The whole card opens the phenomenon page. */
@Composable
private fun ChangeCard(card: ChangeCardUi, counts: CountsUi, onOpen: () -> Unit) {
  val colors = PhonoTheme.colors
  PaperCard(
    modifier = Modifier.fillMaxWidth().testTag(LearnTags.card(card.label)),
    onClick = onOpen,
    onClickLabel = stringResource(R.string.learn_open_change),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        Icon(
          painterResource(MarkLines.iconOf(card.family)),
          null,
          tint = colors.family.of(card.family),
          modifier = Modifier.size(PhonoSizes.iconBar),
        )
        MarkedWordSpan(text = card.name, family = card.family, style = MaterialTheme.typography.titleLarge)
      }
      card.description?.let { IpaText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink2) }
      card.advice?.let { PracticeBadge(it.advice, register = registerWord(it.register)) }
      CardCount(card.label, counts)
    }
  }
}

/** "34 shorts in your clips" (square-play; the short count, D-48) or the muted "Not in your clips yet". */
@Composable
private fun CardCount(label: ChangeLabel, counts: CountsUi) {
  val ready = counts as? CountsUi.Ready ?: return
  val colors = PhonoTheme.colors
  val shorts = ready.shorts(label)
  val text = if (shorts > 0) pluralStringResource(R.plurals.learn_shorts, shorts, shorts) else stringResource(R.string.learn_not_in_clips)
  Row(
    modifier = Modifier.testTag(LearnTags.cardCount(label)).clearAndSetSemantics { contentDescription = text },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    if (shorts > 0) {
      Icon(painterResource(PhonoIcons.TabWatch), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
    PhonoText(
      text,
      style = if (shorts > 0) MaterialTheme.typography.titleSmall.tabular() else MaterialTheme.typography.bodyMedium,
      color = if (shorts > 0) colors.ink else colors.inkMuted,
    )
  }
}
