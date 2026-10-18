package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.ChipKind
import com.mapineda48.phonotrainer.core.designsystem.component.DoubleRule
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoChip
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoDialog
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.EmptyState
import com.mapineda48.phonotrainer.core.ui.EmptyStateAction
import com.mapineda48.phonotrainer.core.ui.LoadingGate
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle
import com.mapineda48.phonotrainer.core.ui.component.AnswerFeedback
import com.mapineda48.phonotrainer.core.ui.component.IntrinsicFlowRow
import com.mapineda48.phonotrainer.core.ui.component.QuestionCard
import com.mapineda48.phonotrainer.core.ui.component.QuestionCardState
import com.mapineda48.phonotrainer.core.ui.component.QuestionKindCopy
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/** What the Practice tab's controls ask for; the route turns each into a ViewModel call or a destination. */
internal class PracticeActions(
  val onClearFocus: () -> Unit = {},
  val onWeakSpots: (Boolean) -> Unit = {},
  val onStart: () -> Unit = {},
  val onResume: () -> Unit = {},
  val onAnswer: (String) -> Unit = {},
  val onPlay: (Playback) -> Unit = {},
  val onNext: () -> Unit = {},
  val onLeave: () -> Unit = {},
  val onLesson: (WordRef) -> Unit = {},
  val onLearn: (ChangeLabel) -> Unit = {},
  val onWatchMore: (ChangeLabel) -> Unit = {},
  val onSave: () -> Unit = {},
  val onAgain: () -> Unit = {},
  val onAgainWeakSpots: () -> Unit = {},
  val onBackToStart: () -> Unit = {},
  val onReset: () -> Unit = {},
  val onSaved: () -> Unit = {},
  val onAddClip: () -> Unit = {},
)

/** The kinds in the web's order: "Which pronunciation?", "Which change?", "How many words?". */
private val KINDS = listOf(QuestionKind.FORM, QuestionKind.CHANGE, QuestionKind.COUNT)

/**
 * The Practice tab (spec F-17; design 10.1 T4, render `14`; the web's `PracticePage`): the tab root's title with the
 * ear spot, the double rule and the lede; then the page of [PracticeUiState.page]: the start card and the progress
 * block, a session (the quiz card of 9.14 under "Question 3 of 10" and its bead row), the results, or "Nothing to
 * practice yet". One vertical scroll; a new question or page starts at the top.
 */
@Composable
internal fun PracticeScreen(state: PracticeUiState, actions: PracticeActions, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.practice_title)
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  val scroll = rememberScrollState()
  val place = "${state.page}:${state.session?.id}:${state.session?.index}"
  var shownPlace by rememberSaveable { mutableStateOf(place) }
  LaunchedEffect(place) {
    if (shownPlace != place) {
      scroll.scrollTo(0)
      shownPlace = place
    }
  }
  Column(
    modifier = modifier
      .fillMaxSize()
      .paneTitle(title)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .verticalScroll(scroll)
      .padding(horizontal = PhonoSpacing.gutter)
      .testTag(PracticeTags.SCREEN),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.sectionGap),
  ) {
    Spacer(Modifier.height(PhonoSpacing.s8))
    if (state.page != PracticePage.SESSION) Header(title)
    when (state.page) {
      PracticePage.LOADING, PracticePage.BUILDING -> Box(Modifier.testTag(PracticeTags.LOADING)) {
        LoadingGate(loading = true, label = stringResource(R.string.practice_loading)) {}
      }
      PracticePage.EMPTY -> {
        Box(Modifier.testTag(PracticeTags.EMPTY)) {
          EmptyState(
            illustration = PhonoIllustrations.PracticeEmpty,
            title = stringResource(R.string.practice_empty_title),
            body = stringResource(R.string.practice_empty_body),
            actions = listOf(EmptyStateAction(stringResource(R.string.practice_empty_add), actions.onAddClip)),
          )
        }
        if (state.progress.hasAny) Progress(state.progress, actions, onReset = { confirmReset = true })
      }
      PracticePage.START -> {
        StartCard(state.start, actions)
        Progress(state.progress, actions, onReset = { confirmReset = true })
      }
      PracticePage.SESSION -> state.session?.let { Session(it, actions) }
      PracticePage.RESULTS -> state.results?.let { Results(it, actions) }
    }
    Spacer(Modifier.height(PhonoSpacing.s24))
  }
  if (confirmReset) {
    PhonoDialog(
      title = stringResource(R.string.practice_reset_title),
      body = stringResource(R.string.practice_reset_body),
      confirmLabel = stringResource(R.string.practice_reset_confirm),
      onConfirm = {
        confirmReset = false
        actions.onReset()
      },
      dismissLabel = stringResource(R.string.practice_reset_cancel),
      onDismiss = { confirmReset = false },
      modifier = Modifier.testTag(PracticeTags.RESET_DIALOG),
    )
  }
}

/** "Practice" (displayMedium) with the ear spot, the double rule and the web's lede (design 9.24 tab roots). */
@Composable
private fun Header(title: String) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Row(verticalAlignment = Alignment.Bottom) {
      PhonoText(
        text = title,
        style = MaterialTheme.typography.displayMedium,
        color = colors.ink,
        modifier = Modifier.weight(1f).semantics { heading() },
      )
      Illustration(PhonoIllustrations.SpotPractice, Modifier.width(SPOT_WIDTH))
    }
    DoubleRule()
    PhonoText(stringResource(R.string.practice_lede), style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
  }
}

// --- the start card ----------------------------------------------------------------------------------------------

@Composable
private fun StartCard(start: StartUi, actions: PracticeActions) {
  val colors = PhonoTheme.colors
  PhonoCard(modifier = Modifier.testTag(PracticeTags.START_CARD), variant = CardVariant.STACK) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
      PhonoText(stringResource(R.string.practice_start_kinds), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
      Column(Modifier.testTag(PracticeTags.KINDS)) {
        KINDS.forEachIndexed { i, kind ->
          if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = PhonoSpacing.s12).height(PhonoSizes.hairline).background(colors.hairline))
          KindRow(kind)
        }
      }
      start.focus?.let { Focus(it, actions) }
      start.resume?.let { resume ->
        Notice(
          body = stringResource(R.string.practice_resume, resume.number, resume.of),
          action = {
            PhonoButton(
              text = stringResource(R.string.practice_resume_action),
              onClick = actions.onResume,
              modifier = Modifier.testTag(PracticeTags.RESUME),
              kind = PhonoButtonKind.SECONDARY,
              trailingIcon = PhonoIcons.ArrowRight,
            )
          },
        )
      }
      if (start.canStart) {
        PhonoSwitch(
          label = stringResource(R.string.practice_weak_spots),
          checked = start.weakSpots,
          onCheckedChange = actions.onWeakSpots,
          description = stringResource(R.string.practice_weak_spots_text),
          modifier = Modifier.testTag(PracticeTags.WEAK_SPOTS),
        )
        PhonoButton(
          text = stringResource(R.string.practice_start),
          onClick = actions.onStart,
          modifier = Modifier.testTag(PracticeTags.START),
          leadingIcon = PhonoIcons.TabPractice,
          large = true,
          fillWidth = true,
        )
      }
    }
  }
}

@Composable
private fun KindRow(kind: QuestionKind) {
  val colors = PhonoTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    Icon(
      painterResource(QuestionKindCopy.icon(kind)),
      contentDescription = null,
      tint = colors.ink,
      modifier = Modifier.padding(top = PhonoSpacing.s2).size(PhonoSizes.iconButton),
    )
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
      PhonoText(QuestionKindCopy.title(kind), style = MaterialTheme.typography.titleMedium, color = colors.ink)
      PhonoText(QuestionKindCopy.explanation(kind), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
  }
}

/** "Only: flapping ✕" + "Practice everything instead"; an unknown key practises everything (web). */
@Composable
private fun Focus(focus: FocusUi, actions: PracticeActions) {
  val everything = stringResource(R.string.practice_focus_everything)
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    when (focus) {
      is FocusUi.Known -> {
        PhonoChip(
          text = stringResource(R.string.practice_focus_only, focus.change.name),
          kind = ChipKind.OUTLINE,
          icon = MarkLines.iconOf(focus.change.family),
          onRemove = actions.onClearFocus,
          removeLabel = everything,
          modifier = Modifier.testTag(PracticeTags.FOCUS_CHIP),
        )
        if (!focus.playable) {
          Notice(
            body = stringResource(R.string.practice_focus_none_body),
            lead = stringResource(R.string.practice_focus_none_title, focus.change.name),
            modifier = Modifier.testTag(PracticeTags.FOCUS_NONE),
          )
        }
      }
      is FocusUi.Unknown -> PhonoText(
        text = stringResource(R.string.practice_focus_unknown, focus.raw),
        style = MaterialTheme.typography.bodyMedium,
        color = PhonoTheme.colors.ink2,
        modifier = Modifier.testTag(PracticeTags.FOCUS_UNKNOWN),
      )
    }
    PhonoButton(
      text = everything,
      onClick = actions.onClearFocus,
      modifier = Modifier.testTag(PracticeTags.FOCUS_EVERYTHING),
      kind = PhonoButtonKind.QUIET,
    )
  }
}

// --- progress ----------------------------------------------------------------------------------------------------

/**
 * "Your accuracy so far" with "Kept only on this phone.", the rows weakest first and "Reset"; the Saved tile and
 * "Changes you have met" with the changes not met yet (spec F-17 item 5). Nothing here counts days, streaks or time
 * (F-17-AC5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Progress(progress: ProgressUi, actions: PracticeActions, onReset: () -> Unit) {
  val colors = PhonoTheme.colors
  Column(Modifier.testTag(PracticeTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12, Alignment.Start),
      itemVerticalAlignment = Alignment.Bottom,
    ) {
      PhonoText(
        stringResource(R.string.practice_progress_title),
        style = MaterialTheme.typography.headlineMedium,
        color = colors.ink,
        modifier = Modifier.semantics { heading() },
      )
      PhonoText(
        stringResource(R.string.practice_progress_kept),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
        modifier = Modifier.padding(bottom = PhonoSpacing.s4).testTag(PracticeTags.PROGRESS_KEPT),
      )
    }
    PhonoCard {
      if (progress.rows.isEmpty()) {
        PhonoText(
          stringResource(R.string.practice_progress_none),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.testTag(PracticeTags.PROGRESS_NONE),
        )
      } else {
        progress.rows.forEachIndexed { i, row ->
          if (i > 0) Box(Modifier.fillMaxWidth().height(PhonoSizes.hairline).background(colors.hairline))
          AccuracyRowView(row)
        }
      }
    }
    if (progress.rows.isNotEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PhonoText(
          stringResource(R.string.practice_progress_weakest),
          style = MaterialTheme.typography.bodySmall,
          color = colors.ink2,
          modifier = Modifier.weight(1f),
        )
        PhonoButton(
          text = stringResource(R.string.practice_reset),
          onClick = onReset,
          modifier = Modifier.testTag(PracticeTags.RESET),
          kind = PhonoButtonKind.QUIET,
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.gridGap)) {
      PaperCard(modifier = Modifier.weight(1f).testTag(PracticeTags.LINK_SAVED), onClick = actions.onSaved) {
        Tile(PhonoIcons.Saved, stringResource(R.string.practice_saved_tile), progress.saved.toString())
      }
      PaperCard(modifier = Modifier.weight(1f).testTag(PracticeTags.MET)) {
        Tile(PhonoIcons.QuizWhichChange, stringResource(R.string.practice_met_tile), stringResource(R.string.practice_met_count, progress.met, progress.total))
      }
    }
    if (progress.notMet.isNotEmpty()) {
      Kicker(stringResource(R.string.practice_not_met), count = progress.notMet.size.toString())
      FlowRow(
        modifier = Modifier.testTag(PracticeTags.NOT_MET),
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
      ) {
        progress.notMet.forEach { change ->
          Box(Modifier.testTag(PracticeTags.notMet(change.label))) {
            ChangeChip(capitalized(change.name), change.family, onClick = { actions.onLearn(change.label) })
          }
        }
      }
    }
  }
}

@Composable
private fun Tile(icon: Int, label: String, value: String) {
  val colors = PhonoTheme.colors
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
    Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    PhonoText(label, style = MaterialTheme.typography.labelLarge, color = colors.ink2, modifier = Modifier.weight(1f, fill = false))
  }
  PhonoText(value, style = MaterialTheme.typography.headlineMedium.tabular(), color = colors.ink)
}

/** "t/d deletion · 4 of 9 · 44 %" with its family icon and a 10 dp ink2 meter (design T4); one TalkBack node. */
@Composable
private fun AccuracyRowView(row: AccuracyRow) {
  val colors = PhonoTheme.colors
  val name = capitalized(row.change.name)
  val description = pluralStringResource(R.plurals.practice_progress_row_description, row.seen, name, row.correct, row.seen, row.percent)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = PhonoSpacing.s8)
      .testTag(PracticeTags.accuracyRow(row.change.label))
      .semantics(mergeDescendants = true) { contentDescription = description },
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      Icon(
        painterResource(MarkLines.iconOf(row.change.family)),
        contentDescription = null,
        tint = colors.family.of(row.change.family),
        modifier = Modifier.size(PhonoSizes.iconButton),
      )
      PhonoText(name, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
      PhonoText(
        stringResource(R.string.practice_progress_row, row.correct, row.seen, row.percent),
        style = MaterialTheme.typography.titleSmall.tabular(),
        color = colors.ink,
      )
    }
    Meter(row.correct.toFloat() / row.seen)
  }
}

/** A 10 dp meter: ink2 on galley (design T4); decorative, the words carry the numbers. */
@Composable
private fun Meter(fraction: Float) {
  val colors = PhonoTheme.colors
  Canvas(Modifier.fillMaxWidth().height(METER).clearAndSetSemantics {}) {
    val radius = CornerRadius(size.height / 2f)
    drawRoundRect(colors.galley, cornerRadius = radius)
    val filled = size.width * fraction.coerceIn(0f, 1f)
    if (filled > 0f) drawRoundRect(colors.ink2, size = Size(filled, size.height), cornerRadius = radius)
  }
}

// --- a session ---------------------------------------------------------------------------------------------------

/**
 * One question (design 10.1 "Practice session"): "Question 3 of 10" with the bead row and the tally, "Leave the
 * session"; the quiz card; once answered its feedback and "Next question" (or "See your results" after the last).
 */
@Composable
private fun Session(session: SessionUi, actions: PracticeActions) {
  Column(Modifier.testTag(PracticeTags.SESSION), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
    SessionHeader(session, actions.onLeave)
    QuestionCard(
      state = QuestionCardState(session.question, chosen = session.chosen, number = session.number, of = session.of, families = session.families),
      onAnswer = actions.onAnswer,
      onPlay = { actions.onPlay(Playback.NORMAL) },
      onSlow = { actions.onPlay(Playback.SLOW) },
      onContext = { actions.onPlay(Playback.CONTEXT) },
    )
    session.feedback?.let { feedback ->
      val occurrence = feedback.result.explanation
      AnswerFeedback(
        state = feedback,
        onLesson = { actions.onLesson(occurrence.word) },
        onLearn = { actions.onLearn(feedback.result.change) },
        onWatchMore = { actions.onWatchMore(feedback.result.change) },
        onSave = actions.onSave,
        onAgainSlow = { actions.onPlay(Playback.SLOW) },
      )
      PhonoButton(
        text = stringResource(if (session.last) R.string.practice_see_results else R.string.practice_next),
        onClick = actions.onNext,
        modifier = Modifier.testTag(PracticeTags.NEXT),
        trailingIcon = PhonoIcons.ArrowRight,
        large = true,
        fillWidth = true,
      )
    }
  }
}

@Composable
private fun SessionHeader(session: SessionUi, onLeave: () -> Unit) {
  val colors = PhonoTheme.colors
  Row(verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      PhonoText(
        text = stringResource(R.string.practice_session_position, session.number, session.of),
        style = MaterialTheme.typography.titleLarge,
        color = colors.ink,
        modifier = Modifier
          .testTag(PracticeTags.POSITION)
          .semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
          },
      )
      Beads(session.beads)
      PhonoText(
        text = stringResource(R.string.practice_session_tally, session.right, session.wrong),
        style = MaterialTheme.typography.bodyMedium.tabular(),
        color = colors.ink2,
        modifier = Modifier.testTag(PracticeTags.TALLY),
      )
    }
    PhonoIconButton(
      icon = PhonoIcons.Close,
      contentDescription = stringResource(R.string.practice_session_leave),
      onClick = onLeave,
      modifier = Modifier.testTag(PracticeTags.LEAVE),
    )
  }
}

/** The bead row (design 9.2): answered = an ink dot, current = a wider ink capsule, ahead = an outline. Decorative. */
@Composable
private fun Beads(beads: List<Bead>) {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier.testTag(PracticeTags.BEADS).clearAndSetSemantics {},
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    beads.forEachIndexed { i, bead ->
      val width = if (bead == Bead.CURRENT) BEAD_CURRENT else BEAD
      Box(
        Modifier
          .testTag(PracticeTags.bead(i))
          .size(width, BEAD)
          .clip(CircleShape)
          .background(if (bead == Bead.NEXT) colors.sheet else colors.ink)
          .then(if (bead == Bead.NEXT) Modifier.border(PhonoSizes.ring, colors.ink, CircleShape) else Modifier),
      )
    }
  }
}

// --- the results -------------------------------------------------------------------------------------------------

/**
 * "7 of 10 right" (displayLarge), the partial line when the pool ran out early, "Worth reviewing" chips, the list of
 * the questions, "Practice again", "Practice my weak spots" and "Back to the start" (web `SessionSummary`).
 */
@Composable
private fun Results(results: ResultsUi, actions: PracticeActions) {
  val colors = PhonoTheme.colors
  PhonoCard(modifier = Modifier.testTag(PracticeTags.RESULTS), variant = CardVariant.CROP_MARKS) {
    Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s16)) {
      Kicker(stringResource(R.string.practice_results_kicker), icon = PhonoIcons.TabPractice)
      PhonoText(
        text = pluralStringResource(R.plurals.practice_results_score, results.total, results.right, results.total),
        style = MaterialTheme.typography.displayLarge,
        color = colors.ink,
        modifier = Modifier
          .testTag(PracticeTags.SCORE)
          .semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
          },
      )
      if (results.partial) {
        PhonoText(
          stringResource(R.string.practice_results_partial),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.ink2,
          modifier = Modifier.testTag(PracticeTags.PARTIAL),
        )
      }
      if (results.missed.isNotEmpty()) {
        Column(Modifier.testTag(PracticeTags.REVIEW), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
          PhonoText(
            stringResource(R.string.practice_results_review),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
          )
          // Not a FlowRow: the card takes its content's intrinsic height, which a FlowRow of chips understates.
          IntrinsicFlowRow(horizontalSpacing = PhonoSpacing.chipGap, verticalSpacing = PhonoSpacing.s4) {
            results.missed.forEach { change ->
              Box(Modifier.testTag(PracticeTags.review(change.label))) {
                ChangeChip(capitalized(change.name), change.family, onClick = { actions.onLearn(change.label) })
              }
            }
          }
        }
      }
      if (results.lines.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
          results.lines.forEach { ResultLineView(it) }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
        PhonoButton(
          text = stringResource(R.string.practice_again),
          onClick = actions.onAgain,
          modifier = Modifier.testTag(PracticeTags.AGAIN),
          leadingIcon = PhonoIcons.Replay,
          fillWidth = true,
        )
        PhonoButton(
          text = stringResource(R.string.practice_weak_spots_again),
          onClick = actions.onAgainWeakSpots,
          modifier = Modifier.testTag(PracticeTags.AGAIN_WEAK_SPOTS),
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.TabPractice,
          fillWidth = true,
        )
        PhonoButton(
          text = stringResource(R.string.practice_back_to_start),
          onClick = actions.onBackToStart,
          modifier = Modifier.testTag(PracticeTags.BACK_TO_START),
          kind = PhonoButtonKind.QUIET,
          fillWidth = true,
        )
      }
    }
  }
}

/** "✓ 3. Which change? “water” — flapping"; TalkBack hears "Right:" or "Wrong:" first (web). */
@Composable
private fun ResultLineView(line: ResultLine) {
  val colors = PhonoTheme.colors
  val text = stringResource(R.string.practice_results_line, line.number, QuestionKindCopy.title(line.kind), line.word, line.change)
  val spoken = stringResource(if (line.correct) R.string.practice_results_right else R.string.practice_results_wrong, text)
  Row(
    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(
      painterResource(if (line.correct) PhonoIcons.StepDone else PhonoIcons.Close),
      contentDescription = null,
      tint = if (line.correct) colors.good else colors.critical,
      modifier = Modifier.padding(top = PhonoSpacing.s2).size(PhonoSizes.iconInline),
    )
    PhonoText(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal), color = colors.ink, modifier = Modifier.weight(1f))
  }
}

/** "flapping" → "Flapping" (the web's `capitalize`). */
internal fun capitalized(text: String): String = text.replaceFirstChar { it.uppercaseChar() }

private val SPOT_WIDTH = 96.dp
private val METER = 10.dp
private val BEAD = 10.dp
private val BEAD_CURRENT = 22.dp
