package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.IpaColumn
import com.mapineda48.phonotrainer.core.domain.model.IpaGridRow
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer

/** What the IPA chart's actions do (the route wires them to its ViewModel and the shell's navigator). */
internal data class IpaChartActions(
  val onBack: () -> Unit = {},
  /** A symbol tapped (its sheet opens), or null when the sheet closes. */
  val onSelect: (String?) -> Unit = {},
  val onPlay: (Occurrence) -> Unit = {},
  val onOpenLesson: (Occurrence) -> Unit = {},
  val onOpenChange: (ChangeLabel) -> Unit = {},
)

/**
 * The IPA chart (P-09, spec F-16, design 9.25): the top bar, the lede, "Choose a symbol" while nothing is selected, and
 * the sections (consonants by manner with their places in plain words and the technical term, "Sounds that replace t
 * and d", syllabic consonants, vowels by height with the weak ones marked, then diphthongs, vowels with r and the
 * other glides) as wrapping groups of symbol buttons, so the page never scrolls sideways, even at the largest text on
 * a 360 dp screen (F-16-AC1). Each button is at least 56 dp, Charis at about 24 sp, read by its name (F-16-AC2); at large text the
 * name is written under it. A selected symbol opens its sheet ([SymbolSheet]).
 */
@Composable
internal fun IpaChartScreen(
  state: IpaChartUiState,
  actions: IpaChartActions,
  modifier: Modifier = Modifier,
  mouth: MouthRenderer? = null,
) {
  val title = stringResource(R.string.ipa_title)
  val listState = rememberLazyListState()
  val layout = state.layout
  val large = LocalDensity.current.fontScale >= LARGE_TEXT
  val buttons = SymbolButtons(state, large, actions.onSelect)
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    LearnTopBar(title = title, backLabel = stringResource(R.string.ipa_back), onBack = actions.onBack, scrolled = listState.canScrollBackward)
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().testTag(LearnTags.IPA_LIST),
      contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
    ) {
      item(key = "lede") {
        PhonoText(stringResource(R.string.ipa_lede), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink2)
      }
      if (state.selected == null) item(key = "choose") { ChooseASymbol() }
      item(key = "consonants") {
        ChartSection(stringResource(R.string.ipa_consonants), stringResource(R.string.ipa_consonants_lede), "consonants") {
          GridRows(layout.consonantRows, layout.places, buttons)
        }
      }
      item(key = "allophones") {
        ChartSection(stringResource(R.string.ipa_allophones), stringResource(R.string.ipa_allophones_lede), "allophones") {
          GridRows(layout.allophoneRows, layout.allophonePlaces, buttons)
          SymbolGroup(stringResource(R.string.ipa_syllabics), stringResource(R.string.ipa_syllabics_lede), layout.syllabics, buttons, "syllabics")
        }
      }
      item(key = "vowels") {
        ChartSection(stringResource(R.string.ipa_vowels), stringResource(R.string.ipa_vowels_lede), "vowels") {
          GridRows(layout.vowelRows, layout.vowelColumns, buttons)
          layout.glidingGroups.forEachIndexed { index, group ->
            SymbolGroup(group.label, group.detail, group.symbols, buttons, "glides.$index")
          }
        }
      }
      if (layout.unplaced.isNotEmpty()) {
        item(key = "other") {
          ChartSection(stringResource(R.string.ipa_other), stringResource(R.string.ipa_other_lede), "other") {
            SymbolGroup(null, null, layout.unplaced, buttons, "other.symbols")
          }
        }
      }
    }
  }
  state.detail?.let { detail ->
    SymbolSheet(detail = detail, mouth = mouth, actions = actions)
  }
}

/** Draws the symbol buttons of one chart, all with the same state. */
private class SymbolButtons(val state: IpaChartUiState, val large: Boolean, val onSelect: (String?) -> Unit)

/** "Choose a symbol — Each symbol is one sound. Start with [ɾ], [t̚] or [ə]" (spec F-16, web). */
@Composable
private fun ChooseASymbol() {
  PaperCard(modifier = Modifier.fillMaxWidth().testTag(LearnTags.IPA_CHOOSE)) {
    PhonoText(
      stringResource(R.string.ipa_choose_title),
      style = MaterialTheme.typography.headlineSmall,
      color = PhonoTheme.colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    IpaText(stringResource(R.string.ipa_choose_body), style = MaterialTheme.typography.bodyLarge, color = PhonoTheme.colors.ink2)
  }
}

/** A card of the chart: its title (a heading), what its groups mean, then [content]. */
@Composable
private fun ChartSection(title: String, lede: String, tag: String, content: @Composable () -> Unit) {
  LearnCard(modifier = Modifier.testTag(LearnTags.ipaSection(tag))) {
    PhonoText(title, style = MaterialTheme.typography.headlineSmall, color = PhonoTheme.colors.ink, modifier = Modifier.semantics { heading() })
    IpaText(lede, style = MaterialTheme.typography.bodyMedium, color = PhonoTheme.colors.ink2)
    content()
  }
}

/**
 * The rows of a grid, reflowed for a phone (design 9.25): each row (a manner, a height) is a group head with its plain
 * meaning, then its cells as wrapping groups, each headed by its place ("Gum ridge · alveolar") and holding its symbols.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GridRows(rows: List<IpaGridRow>, columns: List<IpaColumn>, buttons: SymbolButtons) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    rows.forEach { row ->
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
        Box(Modifier.fillMaxWidth().heightIn(min = PhonoSizes.hairline).background(colors.hairline))
        PhonoText(row.label, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
        if (row.detail.isNotBlank()) PhonoText(row.detail, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s16),
          verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
        ) {
          columns.forEach { column ->
            val symbols = row.cells[column.key].orEmpty()
            if (symbols.isNotEmpty()) {
              Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
                val place = if (column.detail.isBlank()) column.label else stringResource(R.string.ipa_place, column.label, column.detail)
                PhonoText(place, style = MaterialTheme.typography.labelMedium, color = colors.ink2)
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
                  verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
                ) {
                  symbols.forEach { SymbolButton(it, buttons) }
                }
              }
            }
          }
        }
      }
    }
  }
}

/** A titled group of symbols: syllabic consonants, diphthongs, vowels with r, the other glides, other symbols. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolGroup(title: String?, detail: String?, symbols: List<String>, buttons: SymbolButtons, tag: String) {
  val colors = PhonoTheme.colors
  Column(Modifier.testTag(LearnTags.ipaSection(tag)), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
    Box(Modifier.fillMaxWidth().heightIn(min = PhonoSizes.hairline).background(colors.hairline))
    title?.let { PhonoText(it, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.semantics { heading() }) }
    detail?.takeIf { it.isNotBlank() }?.let { PhonoText(it, style = MaterialTheme.typography.bodySmall, color = colors.ink2) }
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
    ) {
      symbols.forEach { SymbolButton(it, buttons) }
    }
  }
}

/**
 * A symbol button (design 9.25): at least 56 × 56 dp and growing with its text (never clipped), the symbol in Charis
 * (the type scale's Compare form, 25 sp); a weak vowel has a dotted underline and the word "weak"; at large text the
 * sound's name is under it; selected = ink fill. TalkBack reads "[ɾ] alveolar tap" (F-16-AC2).
 */
@Composable
private fun SymbolButton(symbol: String, buttons: SymbolButtons) {
  val colors = PhonoTheme.colors
  val state = buttons.state
  val selected = state.selected == symbol
  val weak = symbol in state.weak
  val name = state.names[symbol]
  val label = when {
    name == null -> "[$symbol]"
    weak -> stringResource(R.string.ipa_symbol_weak_label, symbol, name)
    else -> stringResource(R.string.ipa_symbol_label, symbol, name)
  }
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val ink = if (selected) colors.onInk else colors.ink
  Column(
    modifier = Modifier
      .focusRing(shape)
      .widthIn(min = SymbolMin, max = if (buttons.large) SymbolMaxLarge else SymbolMax)
      .heightIn(min = SymbolMin)
      .clip(shape)
      .background(if (selected) colors.ink else colors.sheet)
      .border(PhonoSizes.ring, if (selected) colors.ink else colors.lineStrong, shape)
      .clickable(role = Role.Button) { buttons.onSelect(symbol) }
      .semantics(mergeDescendants = true) {
        contentDescription = label
        this.selected = selected
      }
      .testTag(LearnTags.symbol(symbol))
      .padding(horizontal = PhonoSpacing.s8, vertical = PhonoSpacing.s6),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2, Alignment.CenterVertically),
  ) {
    // No text alignment: the column centres it, and the text keeps its own width (a centred text takes the whole width
    // it is offered, which reads as overflow when it is measured).
    Text(
      text = symbol,
      style = PhonoTheme.type.ipaForm,
      color = ink,
      modifier = if (weak) Modifier.dottedUnderline(ink) else Modifier,
    )
    if (weak) PhonoText(stringResource(R.string.ipa_weak), style = MaterialTheme.typography.labelSmall, color = ink)
    if (buttons.large && name != null) {
      PhonoText(name, style = MaterialTheme.typography.bodySmall, color = ink, textAlign = TextAlign.Center)
    }
  }
}

private val SymbolMin = 56.dp
private val SymbolMax = 160.dp
private val SymbolMaxLarge = 220.dp

/** The weak vowel's dotted underline (web: `decoration-dotted decoration-2`). */
private fun Modifier.dottedUnderline(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
  val y = size.height - 1.dp.toPx()
  drawLine(
    color = color,
    start = Offset(0f, y),
    end = Offset(size.width, y),
    strokeWidth = 2.dp.toPx(),
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
  )
}

/**
 * The symbol sheet (P-09, spec F-16): the symbol in Charis 40, its name, the mouth (WP-29's drawing from rest to the
 * sound with "Play the movement"; in v1-core what the mouth does, in words), the glossary text with the learner note,
 * "As in …", the change it signals, and "In your clips" (up to 6 playable rows). Closing it returns to the chart, the
 * focus on the symbol.
 */
@Composable
private fun SymbolSheet(detail: SymbolDetailUi, mouth: MouthRenderer?, actions: IpaChartActions) {
  val colors = PhonoTheme.colors
  val title = detail.name?.let { stringResource(R.string.ipa_symbol_label, detail.symbol, it) } ?: "[${detail.symbol}]"
  PhonoSheet(onDismiss = { actions.onSelect(null) }, title = title, modifier = Modifier.testTag(LearnTags.IPA_SHEET)) {
    Column(
      modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      Text(
        detail.symbol,
        style = PhonoTheme.type.ipaSymbol,
        color = colors.ink,
        modifier = Modifier.testTag(LearnTags.IPA_SHEET_SYMBOL).semantics { contentDescription = title },
      )
      detail.name?.let {
        PhonoText(
          it,
          style = MaterialTheme.typography.headlineSmall,
          color = colors.ink,
          modifier = Modifier.testTag(LearnTags.IPA_SHEET_NAME).semantics { heading() },
        )
      }
      if (mouth != null) SymbolMouth(detail.symbol, detail.name, mouth)
      detail.cue?.let {
        IpaText(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(LearnTags.IPA_SHEET_CUE))
      }
      detail.body?.let {
        IpaText(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(LearnTags.IPA_SHEET_BODY))
      }
      detail.asIn?.let {
        IpaText(
          stringResource(R.string.ipa_as_in, it),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(LearnTags.IPA_SHEET_AS_IN),
        )
      }
      detail.change?.let { change ->
        LinkButton(
          text = stringResource(R.string.ipa_signals, change.name),
          onClick = { actions.onOpenChange(change.label) },
          trailingArrow = true,
          modifier = Modifier.testTag(LearnTags.IPA_SHEET_CHANGE),
        )
      }
      SectionTitle(stringResource(R.string.ipa_in_your_clips))
      ExampleList(
        examples = detail.examples,
        loadingLabel = stringResource(R.string.ipa_examples_loading),
        onPlay = actions.onPlay,
        onOpenLesson = actions.onOpenLesson,
        modifier = Modifier.testTag(LearnTags.IPA_SHEET_EXAMPLES),
      ) {
        PhonoText(
          stringResource(R.string.ipa_no_examples),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(LearnTags.IPA_SHEET_NO_EXAMPLES),
        )
      }
      PhonoButton(
        text = stringResource(R.string.ipa_close),
        onClick = { actions.onSelect(null) },
        kind = PhonoButtonKind.QUIET,
        modifier = Modifier.testTag(LearnTags.IPA_SHEET_CLOSE),
      )
    }
  }
}
