package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconToggle
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.texture.HalftoneArea
import com.mapineda48.phonotrainer.core.designsystem.texture.halftone
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.RecordTarget
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.ExplainSheet
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PracticeBadge
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.ShadowingTarget
import com.mapineda48.phonotrainer.core.ui.extension.MouthRenderer

/** What the lesson's controls do; the route wires them to the ViewModel and the shell's navigator. */
@Immutable
internal data class LessonActions(
  val onClose: () -> Unit = {},
  val onToggleSaved: () -> Unit = {},
  val onPrevious: () -> Unit = {},
  val onNext: () -> Unit = {},
  val onNextChange: () -> Unit = {},
  val onToggleStep: (LessonStep) -> Unit = {},
  val onPlay: (ListenTarget) -> Unit = {},
  val onSpeed: (Float) -> Unit = {},
  val onLoop: (Boolean) -> Unit = {},
  val onTrack: (Track) -> Unit = {},
  val onPickSort: (Int) -> Unit = {},
  val onPlaySound: () -> Unit = {},
  val onSeeMouth: () -> Unit = {},
  val onIpaChart: (String) -> Unit = {},
  /** A change chip or "Learn about {change}": the phenomenon page. */
  val onOpenChange: (ChangeLabel) -> Unit = {},
  /** "Watch more like this": the change's mode of the feed. */
  val onWatchMore: (ChangeLabel) -> Unit = {},
  val onStream: (MouthStream) -> Unit = {},
  val onHoldSound: (Int) -> Unit = {},
  val onFollow: () -> Unit = {},
  val onPractice: (ShadowingTarget, Float) -> Unit = { _, _ -> },
  val onStartShadowing: () -> Unit = {},
  val onStopShadowing: () -> Unit = {},
  val onLearnMore: (LinkTarget) -> Unit = {},
)

/** The slot of WP-30's "Record yourself" in step 5 (work-plan 4.4); null in v1-core. */
internal typealias RecordSlot = @Composable (ClipSpan, RecordTarget, Modifier) -> Unit

/**
 * The word lesson (spec F-10, design 9.9 to 9.11), stateless (architecture 3.1): over the paused feed it is the content
 * of the shell's bottom sheet ([LessonPresentation.SHEET], half height, expandable), elsewhere a full-height page
 * ([LessonPresentation.FULL]). One scroll holds the paused-context pill, the header and the five numbered steps.
 *
 * "What's this?" sheets are local UI state (architecture 16.1); closing one returns the focus to its "?" (F-14-AC1).
 * [glossary] answers the "?" of a term (a term the content lacks shows no "?"), [mouth] draws step 4 when WP-29's
 * module is present, [playheadMs] is where the span player is while it plays this clip (for that drawing; the player
 * updates it every 50 ms), and [record] is WP-30's section.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LessonScreen(
  state: LessonUiState,
  presentation: LessonPresentation,
  actions: LessonActions,
  modifier: Modifier = Modifier,
  glossary: (String) -> GlossaryEntry? = { null },
  mouth: MouthRenderer? = null,
  playheadMs: Long? = null,
  record: RecordSlot? = null,
) {
  val colors = PhonoTheme.colors
  val title = stringResource(R.string.lesson_title)
  // In the sheet every state fills the sheet's height, so the sheet's size never changes while the lesson loads:
  // Material's sheet settles where its content's first size puts it, and a lesson that grew after the sheet had opened
  // settled fully expanded, where Back only brings it down to half height. Full height, it opens at half height
  // (design 9.9) and the first Back closes it (F-07-AC6).
  val frame = if (presentation == LessonPresentation.FULL) {
    Modifier.fillMaxSize().background(colors.sheet).statusBarsPadding()
  } else {
    Modifier.fillMaxSize()
  }
  Box(
    modifier = modifier
      .then(frame)
      .paneTitle(title)
      // The sheet composes in its own window: tags must be resource ids there too (wave-1 review, item 15).
      .semantics { testTagsAsResourceId = true }
      .testTag(LessonTags.ROOT),
  ) {
    when (state) {
      LessonUiState.Loading -> LoadingGate(
        loading = true,
        label = stringResource(R.string.lesson_loading),
        modifier = Modifier.padding(PhonoSpacing.gutter).testTag(LessonTags.LOADING),
      ) {}
      is LessonUiState.Unavailable -> UnavailableContent(state.reason, actions.onClose)
      is LessonUiState.Ready -> ReadyContent(state, actions, glossary, mouth, playheadMs, record)
    }
  }
}

@Composable
private fun UnavailableContent(reason: Unavailable, onClose: () -> Unit) {
  val (title, body) = when (reason) {
    Unavailable.CLIP_GONE -> R.string.lesson_gone_title to R.string.lesson_gone_body
    Unavailable.CLIP_DAMAGED -> R.string.lesson_damaged_title to R.string.lesson_damaged_body
    Unavailable.WORD_GONE -> R.string.lesson_word_gone_title to R.string.lesson_word_gone_body
  }
  Column(Modifier.fillMaxWidth().testTag(LessonTags.UNAVAILABLE)) {
    EmptyState(
      illustration = null,
      title = stringResource(title),
      body = stringResource(body),
      actions = listOf(EmptyStateAction(stringResource(R.string.lesson_close), onClose, PhonoButtonKind.SECONDARY)),
    )
  }
}

/** Which "What's this?" sheet is open: a glossary term, or a change of step 3. */
private const val TERM = "term:"
private const val CHANGE = "change:"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadyContent(
  state: LessonUiState.Ready,
  actions: LessonActions,
  glossary: (String) -> GlossaryEntry?,
  mouth: MouthRenderer?,
  playheadMs: Long?,
  record: RecordSlot?,
) {
  val scroll = rememberScrollState()
  val requesters = remember { LessonStep.entries.associateWith { BringIntoViewRequester() } }
  var explain by rememberSaveable { mutableStateOf<String?>(null) }
  var returnFocus by remember { mutableStateOf<String?>(null) }
  val focus = remember { HashMap<String, FocusRequester>() }
  val explainer = Explainer(
    glossary = glossary,
    requester = { key -> focus.getOrPut(key) { FocusRequester() } },
    open = { key -> explain = key },
  )

  // The step last opened comes back into view after a restore, and step 4 when "See it in the mouth" opens it.
  LaunchedEffect(state.reveal) {
    val at = state.at ?: return@LaunchedEffect
    withFrameNanos { }
    requesters.getValue(at).bringIntoView()
  }
  LaunchedEffect(returnFocus) {
    val key = returnFocus ?: return@LaunchedEffect
    withFrameNanos { }
    runCatching { focus[key]?.requestFocus() }
    returnFocus = null
  }

  Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = PhonoSpacing.s32)) {
    state.paused?.let { PausedPill(it) }
    LessonHeader(state.header, actions)
    LessonSteps(state, actions, requesters, explainer, mouth, playheadMs, record)
  }

  val shown = explain
  when {
    shown == null -> Unit
    shown.startsWith(TERM) -> {
      val entry = glossary(shown.removePrefix(TERM))
      if (entry == null) {
        explain = null
      } else {
        ExplainSheet(
          entry,
          onDismiss = {
            explain = null
            returnFocus = shown
          },
          onLearnMore = { target ->
            explain = null
            actions.onLearnMore(target)
          },
        )
      }
    }
    shown.startsWith(CHANGE) -> {
      val card = state.why.cards.firstOrNull { it.change.label.value == shown.removePrefix(CHANGE) }
      if (card == null) {
        explain = null
      } else {
        ChangeExplainSheet(
          card = card,
          onDismiss = {
            explain = null
            returnFocus = shown
          },
          onWatchMore = {
            explain = null
            actions.onWatchMore(card.change.label)
          },
          onLearnMore = {
            explain = null
            actions.onOpenChange(card.change.label)
          },
        )
      }
    }
  }
}

/** How a step offers "What's this?": the entry of a term (none: no "?"), and the focus it returns to. */
internal class Explainer(
  val glossary: (String) -> GlossaryEntry?,
  val requester: (String) -> FocusRequester,
  val open: (String) -> Unit,
) {
  fun hasTerm(key: String): Boolean = glossary(key) != null

  fun termKey(key: String): String = TERM + key

  fun changeKey(label: ChangeLabel): String = CHANGE + label.value
}

/** "Paused · Two friends at the lake": the feed waits beneath (design 9.9). */
@Composable
private fun PausedPill(paused: PausedUi) {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier
      .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s4)
      .clip(CircleShape)
      .background(colors.galley)
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6)
      .testTag(LessonTags.PAUSED)
      .semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(painterResource(PhonoIcons.Pause), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(stringResource(R.string.lesson_paused, paused.title), style = MaterialTheme.typography.labelLarge, color = colors.ink)
  }
}

/** The header of design 9.9: kicker, save and close, the word, where it is, its changes and the ways to move on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonHeader(header: HeaderUi, actions: LessonActions) {
  val colors = PhonoTheme.colors
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .halftone(seed = header.position, color = colors.halftone, area = HalftoneArea.CORNER_TOP_END)
      .padding(start = PhonoSpacing.gutter, end = PhonoSpacing.s8, top = PhonoSpacing.s4, bottom = PhonoSpacing.s12),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      PhonoText(
        stringResource(R.string.lesson_kicker),
        style = MaterialTheme.typography.labelLarge,
        color = colors.ink2,
        modifier = Modifier.weight(1f),
      )
      PhonoIconToggle(
        icon = if (header.saved) PhonoIcons.Saved else PhonoIcons.Save,
        contentDescription = stringResource(R.string.lesson_save),
        checked = header.saved,
        onCheckedChange = { actions.onToggleSaved() },
        modifier = Modifier.testTag(LessonTags.SAVE),
      )
      PhonoIconButton(
        icon = PhonoIcons.Close,
        contentDescription = stringResource(R.string.lesson_close),
        onClick = actions.onClose,
        modifier = Modifier.testTag(LessonTags.CLOSE),
      )
    }
    WordTitle(header)
    PhonoText(
      stringResource(
        R.string.lesson_meta, header.position, header.total, header.phrase,
        LessonLogic.clock(header.startS), LessonLogic.clock(header.endS),
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
      modifier = Modifier.testTag(LessonTags.META),
    )
    if (header.changes.isNotEmpty() || header.advice != null || header.reliability != Reliability.OK) {
      FlowRow(
        modifier = Modifier.padding(end = PhonoSpacing.s8).testTag(LessonTags.CHANGES),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        header.changes.forEach { change ->
          ChangeChip(change.name, change.family, onClick = { actions.onOpenChange(change.label) })
        }
        header.advice?.let { PracticeBadge(it.advice, register = registerWord(it.register)) }
        if (header.reliability != Reliability.OK) {
          PhonoBadge(
            text = stringResource(
              if (header.reliability == Reliability.NOTHING_HEARD) R.string.lesson_nothing_heard else R.string.lesson_low_confidence,
            ),
            dashed = true,
            modifier = Modifier.testTag(LessonTags.RELIABILITY).semantics(mergeDescendants = true) {},
          )
        }
      }
    }
    WordButtons(header, actions)
  }
}

@Composable
private fun WordTitle(header: HeaderUi) {
  val colors = PhonoTheme.colors
  val word = stringResource(R.string.lesson_word, header.text)
  val next = header.nextText?.let { stringResource(R.string.lesson_word_next, it) }
  Row(
    modifier = Modifier
      .padding(end = PhonoSpacing.s8)
      .testTag(LessonTags.WORD)
      .semantics(mergeDescendants = true) { heading() },
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoText(word, style = PhonoTheme.type.lessonWord, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
    next?.let {
      PhonoText(
        it,
        style = PhonoTheme.type.lessonWord.copy(
          fontSize = MaterialTheme.typography.headlineSmall.fontSize,
          lineHeight = MaterialTheme.typography.headlineSmall.lineHeight,
          fontStyle = FontStyle.Italic,
        ),
        color = colors.ink2,
        modifier = Modifier.padding(bottom = PhonoSpacing.s4),
      )
    }
  }
}

/** "‹ Word", "Word ›" (full names "Previous word", "Next word") and "Next change →" (design 9.9). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordButtons(header: HeaderUi, actions: LessonActions) {
  val previous = stringResource(R.string.lesson_previous)
  val next = stringResource(R.string.lesson_next)
  FlowRow(
    modifier = Modifier.fillMaxWidth().heightIn(min = PhonoSizes.minTarget).padding(end = PhonoSpacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    PhonoButton(
      text = stringResource(R.string.lesson_word_button),
      onClick = actions.onPrevious,
      kind = PhonoButtonKind.SECONDARY,
      enabled = header.canPrevious,
      leadingIcon = PhonoIcons.ChevronLeft,
      modifier = Modifier.testTag(LessonTags.PREVIOUS).semantics { contentDescription = previous },
    )
    PhonoButton(
      text = stringResource(R.string.lesson_word_button),
      onClick = actions.onNext,
      kind = PhonoButtonKind.SECONDARY,
      enabled = header.canNext,
      trailingIcon = PhonoIcons.ChevronRight,
      modifier = Modifier.testTag(LessonTags.NEXT).semantics { contentDescription = next },
    )
    Spacer(Modifier.weight(1f))
    PhonoButton(
      text = stringResource(R.string.lesson_next_change),
      onClick = actions.onNextChange,
      kind = PhonoButtonKind.TONAL,
      enabled = header.canNextChange,
      trailingIcon = PhonoIcons.ArrowRight,
      modifier = Modifier.testTag(LessonTags.NEXT_CHANGE),
    )
  }
}

/** The register's word after the practice badge ("Safe to produce · universal"). */
@Composable
internal fun registerWord(register: Register): String = stringResource(
  when (register) {
    Register.UNIVERSAL -> R.string.lesson_register_universal
    Register.CASUAL -> R.string.lesson_register_casual
    Register.MARKED -> R.string.lesson_register_marked
  },
)
