package com.mapineda48.phonotrainer.feature.learn

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.ExplainButton
import com.mapineda48.phonotrainer.core.ui.ExplainSheet
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoSheet
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer

/** What a phenomenon page's actions do (the route wires them to its ViewModel and the shell's navigator). */
internal data class PhenomenonActions(
  val onBack: () -> Unit = {},
  /** "Watch examples" and the sheet's "Watch more like this": the change mode of the feed (F-15-AC2). */
  val onWatch: (ChangeLabel) -> Unit = {},
  /** "Practice this": Practice with the focus on this change. */
  val onPractice: (ChangeLabel) -> Unit = {},
  val onPlay: (Occurrence) -> Unit = {},
  val onOpenLesson: (Occurrence) -> Unit = {},
  val onAddClip: () -> Unit = {},
  val onLearn: () -> Unit = {},
  /** "See it in the IPA chart" on a demo sound. */
  val onIpaChart: (String) -> Unit = {},
  val onLearnMore: (LinkTarget) -> Unit = {},
)

/**
 * A phenomenon page (P-08, spec F-15 item 2, design 9.25, render `13`): the top bar with the change's name in its line
 * style and its "What's this?"; the chips (family, practice badge + register); the description; "How to hear it"; the
 * Spanish-speaker tip where the content has one (F-15-AC3); the caveat; "Can you copy it?"; "The mouth" (WP-29's
 * drawing when bound, else each changing sound's name and what the mouth does); "Watch examples" and "Practice this";
 * the occurrence line "12 times in your clips" (D-48) with "Counted in the clips on this phone." when some clips are
 * not here; and "Examples from your clips" (up to 8), or the empty state with "Add a clip".
 */
@Composable
internal fun PhenomenonScreen(
  state: PhenomenonUiState,
  actions: PhenomenonActions,
  modifier: Modifier = Modifier,
  glossary: (String) -> GlossaryEntry? = { null },
  mouth: MouthRenderer? = null,
) {
  val listState = rememberLazyListState()
  val ready = state as? PhenomenonUiState.Ready
  val title = ready?.card?.name ?: stringResource(R.string.learn_title)
  var changeSheet by rememberSaveable { mutableStateOf(false) }
  var term by rememberSaveable { mutableStateOf<String?>(null) }
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
  ) {
    LearnTopBar(
      title = title,
      backLabel = stringResource(R.string.phenomenon_back),
      onBack = actions.onBack,
      family = ready?.card?.family,
      scrolled = listState.canScrollBackward,
      actions = {
        if (ready != null) {
          ExplainButton(term = ready.card.name, onClick = { changeSheet = true }, modifier = Modifier.testTag(LearnTags.PHENOMENON_EXPLAIN))
        }
      },
    )
    when (state) {
      PhenomenonUiState.Loading -> Box(Modifier.padding(horizontal = PhonoSpacing.gutter)) {
        LoadingGate(loading = true, label = stringResource(R.string.phenomenon_loading)) {}
      }
      PhenomenonUiState.Unknown -> EmptyState(
        illustration = null,
        title = stringResource(R.string.phenomenon_unknown_title),
        body = stringResource(R.string.phenomenon_unknown_body),
        modifier = Modifier.testTag(LearnTags.PHENOMENON_UNKNOWN),
        actions = listOf(EmptyStateAction(stringResource(R.string.phenomenon_unknown_action), actions.onLearn)),
      )
      is PhenomenonUiState.Ready -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(LearnTags.PHENOMENON_LIST),
        contentPadding = PaddingValues(start = PhonoSpacing.gutter, end = PhonoSpacing.gutter, bottom = PhonoSpacing.s32),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
      ) {
        item(key = "chips") { Chips(state) }
        state.card.description?.let { description ->
          item(key = "description") {
            IpaText(description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(LearnTags.PHENOMENON_DESCRIPTION))
          }
        }
        state.lesson?.let { lesson ->
          item(key = "how") {
            Column(Modifier.testTag(LearnTags.PHENOMENON_HOW), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
              SectionTitle(stringResource(R.string.phenomenon_how_to_hear))
              IpaText(lesson.listenFor, style = MaterialTheme.typography.bodyLarge)
            }
          }
          lesson.spanishTip?.let { tip ->
            item(key = "tip") {
              TextWell(
                icon = PhonoIcons.Tip,
                lead = stringResource(R.string.phenomenon_spanish_lead),
                body = tip,
                modifier = Modifier.testTag(LearnTags.PHENOMENON_TIP),
              )
            }
          }
          lesson.note?.let { note ->
            item(key = "note") { TextWell(icon = PhonoIcons.Info, lead = null, body = note, modifier = Modifier.testTag(LearnTags.PHENOMENON_NOTE)) }
          }
        }
        state.card.advice?.let { advice ->
          item(key = "copy") { CanYouCopyIt(advice, hasRegisterTerm = glossary(REGISTER) != null, onExplain = { term = REGISTER }) }
        }
        state.demo?.let { demo ->
          item(key = "mouth") { MouthDemoCard(demo, mouth, onIpaChart = actions.onIpaChart) }
        }
        item(key = "actions") { PageActions(state.card.label, actions) }
        item(key = "times") { OccurrenceLine(state) }
        item(key = "examples") { Examples(state, actions) }
      }
    }
  }
  if (changeSheet && ready != null) {
    ChangeSheet(
      card = ready.card,
      onDismiss = { changeSheet = false },
      onWatchMore = {
        changeSheet = false
        actions.onWatch(ready.card.label)
      },
    )
  }
  term?.let(glossary)?.let { entry ->
    ExplainSheet(
      entry = entry,
      onDismiss = { term = null },
      onLearnMore = {
        term = null
        actions.onLearnMore(it)
      },
    )
  }
}

private const val REGISTER = "register"

/** The chips row (render `13`): the family pill and the practice badge with its register. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(state: PhenomenonUiState.Ready) {
  FlowRow(
    modifier = Modifier.fillMaxWidth().testTag(LearnTags.PHENOMENON_CHIPS),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
  ) {
    FamilyPill(state.card.family, familyTitle(state.card.family, state.familyName))
    state.card.advice?.let { PracticeBadge(it.advice, register = registerWord(it.register)) }
  }
}

/** A galley well with an icon and text that may hold IPA: the Spanish-speaker tip (with its lead) and the caveat. */
@Composable
private fun TextWell(@DrawableRes icon: Int, lead: String?, body: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(PhonoRadius.medium))
      .background(colors.galley)
      .semantics(mergeDescendants = true) {}
      .padding(PhonoSpacing.s12),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.padding(top = PhonoSpacing.s2).size(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
      lead?.let { PhonoText(it, style = MaterialTheme.typography.labelLarge, color = colors.ink) }
      IpaText(body, style = MaterialTheme.typography.bodyMedium, color = if (lead != null) colors.ink else colors.ink2)
    }
  }
}

/** "Can you copy it?" (web): the badge, why, and the register with what it means and its "What's this?". */
@Composable
private fun CanYouCopyIt(advice: PracticeAdvice, hasRegisterTerm: Boolean, onExplain: () -> Unit) {
  val colors = PhonoTheme.colors
  PaperCard(modifier = Modifier.fillMaxWidth().testTag(LearnTags.PHENOMENON_COPY)) {
    PhonoText(
      stringResource(R.string.phenomenon_copy_title),
      style = MaterialTheme.typography.headlineSmall,
      color = colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    PracticeBadge(advice.advice)
    if (advice.why.isNotBlank()) IpaText(advice.why, style = MaterialTheme.typography.bodyLarge)
    val register = stringResource(R.string.phenomenon_register)
    Row(verticalAlignment = Alignment.CenterVertically) {
      PhonoText(register, style = MaterialTheme.typography.labelLarge, color = colors.ink)
      if (hasRegisterTerm) {
        ExplainButton(term = register, onClick = onExplain, modifier = Modifier.testTag(LearnTags.PHENOMENON_REGISTER_EXPLAIN))
      }
    }
    PhonoText(
      stringResource(R.string.phenomenon_register_line, registerWord(advice.register), registerMeaning(advice.register)),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
    )
  }
}

/** "Watch examples" (primary, large) and "Practice this"; one per line at large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageActions(label: ChangeLabel, actions: PhenomenonActions) {
  val large = LocalDensity.current.fontScale >= LARGE_TEXT
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(top = PhonoSpacing.s8),
    maxItemsInEachRow = if (large) 1 else 2,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    PhonoButton(
      text = stringResource(R.string.phenomenon_watch),
      onClick = { actions.onWatch(label) },
      large = true,
      fillWidth = true,
      leadingIcon = PhonoIcons.TabWatch,
      modifier = Modifier.weight(1f).testTag(LearnTags.PHENOMENON_EXAMPLES),
    )
    PhonoButton(
      text = stringResource(R.string.phenomenon_practice),
      onClick = { actions.onPractice(label) },
      kind = PhonoButtonKind.SECONDARY,
      large = true,
      fillWidth = true,
      leadingIcon = PhonoIcons.TabPractice,
      modifier = Modifier.weight(1f).testTag(LearnTags.PHENOMENON_PRACTICE),
    )
  }
}

/** The occurrence line (design 9.25, D-48): "12 times in your clips", and the partial line under it. */
@Composable
private fun OccurrenceLine(state: PhenomenonUiState.Ready) {
  val colors = PhonoTheme.colors
  Column(Modifier.padding(top = PhonoSpacing.s8), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
    when (val counts = state.counts) {
      CountsUi.Loading -> Unit
      CountsUi.Failed -> CountsFailedLine()
      is CountsUi.Ready -> {
        val times = counts.occurrences(state.card.label)
        PhonoText(
          pluralStringResource(R.plurals.phenomenon_times, times, times),
          style = MaterialTheme.typography.titleMedium.tabular(),
          color = colors.ink,
          modifier = Modifier.testTag(LearnTags.PHENOMENON_TIMES),
        )
        if (state.partial) {
          PhonoText(
            stringResource(R.string.learn_counted_here),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink2,
            modifier = Modifier.testTag(LearnTags.PHENOMENON_COUNTED_HERE),
          )
        }
      }
    }
  }
}

/** "Examples from your clips" (spec F-15 item 2): up to 8 rows, clearest first, or the empty state. */
@Composable
private fun Examples(state: PhenomenonUiState.Ready, actions: PhenomenonActions) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    SectionTitle(stringResource(R.string.phenomenon_examples_title))
    PhonoText(stringResource(R.string.phenomenon_examples_lede), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    ExampleList(
      examples = state.examples,
      loadingLabel = stringResource(R.string.phenomenon_examples_loading),
      onPlay = actions.onPlay,
      onOpenLesson = actions.onOpenLesson,
      modifier = Modifier.testTag(LearnTags.PHENOMENON_EXAMPLE_LIST),
    ) {
      EmptyState(
        illustration = PhonoIllustrations.NoExamples,
        title = stringResource(R.string.phenomenon_no_examples_title),
        body = stringResource(R.string.phenomenon_no_examples_body),
        modifier = Modifier.testTag(LearnTags.PHENOMENON_NO_EXAMPLES),
        actions = listOf(EmptyStateAction(stringResource(R.string.phenomenon_add_clip), actions.onAddClip)),
      )
    }
  }
}

/**
 * A change's "What's this?" (spec F-14, design 9.13): its description, the practice badge with its register and why,
 * and "Watch more like this" (the page itself is its "Learn more").
 */
@Composable
private fun ChangeSheet(card: ChangeCardUi, onDismiss: () -> Unit, onWatchMore: () -> Unit) {
  val colors = PhonoTheme.colors
  PhonoSheet(onDismiss = onDismiss, title = card.name, modifier = Modifier.testTag(LearnTags.CHANGE_SHEET)) {
    Column(Modifier.padding(bottom = PhonoSpacing.s24), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      PhonoText(card.name, style = MaterialTheme.typography.headlineLarge, color = colors.ink, modifier = Modifier.semantics { heading() })
      card.description?.let { IpaText(it, style = MaterialTheme.typography.bodyLarge) }
      card.advice?.let { advice ->
        PracticeBadge(advice.advice, register = registerWord(advice.register))
        if (advice.why.isNotBlank()) IpaText(advice.why, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      }
      PhonoButton(
        text = stringResource(R.string.phenomenon_watch_more),
        onClick = onWatchMore,
        kind = PhonoButtonKind.SECONDARY,
        leadingIcon = PhonoIcons.TabWatch,
        modifier = Modifier.testTag(LearnTags.CHANGE_SHEET_WATCH),
      )
      PhonoButton(text = stringResource(R.string.phenomenon_close), onClick = onDismiss, kind = PhonoButtonKind.QUIET)
    }
  }
}

/** The link under a demo sound's words: "See it in the IPA chart". */
@Composable
internal fun ChartLink(symbol: String, onIpaChart: (String) -> Unit, modifier: Modifier = Modifier) {
  LinkButton(
    text = stringResource(R.string.phenomenon_mouth_chart),
    onClick = { onIpaChart(symbol) },
    trailingArrow = true,
    modifier = modifier,
  )
}
