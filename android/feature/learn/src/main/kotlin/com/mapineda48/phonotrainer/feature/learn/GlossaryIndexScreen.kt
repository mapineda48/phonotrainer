package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTextField
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.ui.ExplainSheet
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle

/** What "Words we use" does (the route wires it to its ViewModel and the shell's navigator). */
internal data class GlossaryActions(
  val onBack: () -> Unit = {},
  val onSearch: (String) -> Unit = {},
  val onClear: () -> Unit = {},
  val onLearnMore: (LinkTarget) -> Unit = {},
)

/**
 * "Words we use" (F-14, design 10.2, copy D-47): the top bar, the lede, the search field "Search the words we use…"
 * with the live count "{n} of {total} terms" under it (announced, as the Library's), the groups "Terms" and
 * "Measures" as 56 dp rows (the term and the first sentence of its explanation, a chevron), each opening the same
 * "What's this?" sheet; with no match, "No term matches “{text}”." and "Clear the search" (no illustration: it is not
 * an empty state, design 9.21).
 */
@Composable
internal fun GlossaryIndexScreen(
  state: GlossaryUiState,
  actions: GlossaryActions,
  modifier: Modifier = Modifier,
  entry: (String) -> GlossaryEntry? = { null },
) {
  val title = stringResource(R.string.glossary_title)
  val listState = rememberLazyListState()
  var open by rememberSaveable { mutableStateOf<String?>(null) }
  val termsTitle = stringResource(R.string.glossary_terms)
  val measuresTitle = stringResource(R.string.glossary_measures)
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .imePadding(),
  ) {
    LearnTopBar(title = title, backLabel = stringResource(R.string.glossary_back), onBack = actions.onBack, scrolled = listState.canScrollBackward)
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().testTag(LearnTags.GLOSSARY_LIST),
      contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      item(key = "lede") {
        PhonoText(
          stringResource(R.string.glossary_lede),
          style = MaterialTheme.typography.bodyLarge,
          color = PhonoTheme.colors.ink2,
          modifier = Modifier.padding(bottom = PhonoSpacing.s12),
        )
      }
      item(key = "search") { Search(state, actions) }
      if (state.shown == 0) {
        item(key = "no-match") { NoMatch(state.query, actions.onClear) }
      } else {
        group("terms", termsTitle, LearnTags.GLOSSARY_TERMS, state.terms) { open = it }
        group("measures", measuresTitle, LearnTags.GLOSSARY_MEASURES, state.measures) { open = it }
      }
    }
  }
  open?.let(entry)?.let { shown ->
    ExplainSheet(
      entry = shown,
      onDismiss = { open = null },
      onLearnMore = {
        open = null
        actions.onLearnMore(it)
      },
    )
  }
}

/** The search field and, under it, the live count "{n} of {total} terms". */
@Composable
private fun Search(state: GlossaryUiState, actions: GlossaryActions) {
  val label = stringResource(R.string.glossary_search)
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Box(Modifier.testTag(LearnTags.GLOSSARY_SEARCH)) {
      PhonoTextField(
        value = state.query,
        onValueChange = actions.onSearch,
        label = label,
        placeholder = label,
        leadingIcon = PhonoIcons.Search,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      )
    }
    PhonoText(
      pluralStringResource(R.plurals.glossary_count, state.total, state.shown, state.total),
      style = MaterialTheme.typography.bodyMedium.tabular(),
      color = PhonoTheme.colors.ink2,
      modifier = Modifier
        .semantics { liveRegion = LiveRegionMode.Polite }
        .testTag(LearnTags.GLOSSARY_COUNT),
    )
  }
}

/** A group ("Terms", "Measures") with its kicker and rows; nothing when the search leaves none of it. */
private fun LazyListScope.group(key: String, title: String, tag: String, hits: List<GlossaryHit>, onOpen: (String) -> Unit) {
  if (hits.isEmpty()) return
  item(key = "group:$key") {
    Kicker(title, count = hits.size.toString(), modifier = Modifier.padding(top = PhonoSpacing.s16, bottom = PhonoSpacing.s4).testTag(tag))
  }
  items(hits, key = { "row:" + it.key }) { hit -> GlossaryRow(hit, onOpen = { onOpen(hit.key) }) }
}

/** A 56 dp row: the term (titleMedium), the first sentence of its explanation (bodyMedium, ink2) and a chevron. */
@Composable
private fun GlossaryRow(hit: GlossaryHit, onOpen: () -> Unit) {
  val colors = PhonoTheme.colors
  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = PhonoSizes.row)
        .clickable(onClickLabel = stringResource(R.string.glossary_open), role = Role.Button, onClick = onOpen)
        .testTag(LearnTags.glossaryRow(hit.key))
        .padding(vertical = PhonoSpacing.s10),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        PhonoText(hit.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        IpaText(hit.snippet, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
    Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
  }
}

/** No match (F-14-AC4): "No term matches “xyzzy”." and the quiet "Clear the search". */
@Composable
private fun NoMatch(query: String, onClear: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s16).testTag(LearnTags.GLOSSARY_NO_MATCH),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoText(
      stringResource(R.string.glossary_no_match, query.trim()),
      style = MaterialTheme.typography.bodyLarge,
      color = PhonoTheme.colors.ink,
    )
    PhonoButton(
      text = stringResource(R.string.glossary_clear),
      onClick = onClear,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(LearnTags.GLOSSARY_CLEAR),
    )
  }
}
