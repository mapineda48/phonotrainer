package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.CardVariant
import com.mapineda48.phonotrainer.core.designsystem.component.DoubleRule
import com.mapineda48.phonotrainer.core.designsystem.component.Illustration
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.NoticeKind
import com.mapineda48.phonotrainer.core.designsystem.component.PaperCard
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

/** Where an end card can send the learner. */
@Immutable
internal data class EndCallbacks(
  val onHome: () -> Unit = {},
  val onAnotherSet: () -> Unit = {},
  val onPractice: (ChangeLabel?) -> Unit = {},
  val onWatchAgain: () -> Unit = {},
  val onNextClip: (JobId) -> Unit = {},
  val onLearn: (ChangeLabel) -> Unit = {},
  val onReplayMix: () -> Unit = {},
  val onAddClip: () -> Unit = {},
)

/**
 * An end card (spec F-06 item 9, F-08 items 6 and 7, design 9.15, render `08`): the set summary ("Done for today" or
 * "Set done", the tally between double rules, "What you met", "Nothing plays after this card…"), the end of a clip,
 * of a change, of the saved shorts, or the Mix with nothing left. Nothing loads past it: it is the feed's last page,
 * and every way on is a button. No streak and no time-spent badge, ever.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndCard(
  kind: EndKind,
  state: WatchUiState,
  callbacks: EndCallbacks,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
    PaperCard(
      variant = if (kind == EndKind.SET_SUMMARY) CardVariant.STACK else CardVariant.CROP_MARKS,
      modifier = Modifier.padding(PhonoSpacing.feedGutter).fillMaxWidth().testTag(WatchTags.END),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
        when (kind) {
          EndKind.SET_SUMMARY -> SetSummaryBody(state.summary, callbacks)
          EndKind.CLIP_END -> ClipEndBody(state.clipEnd, callbacks)
          EndKind.CHANGE_END -> {
            val change = state.mode as? ModeName.Change
            val name = change?.name.orEmpty()
            Title(stringResource(R.string.watch_end_every_change, name))
            if (change != null) {
              PhonoButton(
                stringResource(R.string.watch_end_learn_about, name),
                { callbacks.onLearn(change.label) },
                kind = PhonoButtonKind.PRIMARY,
                fillWidth = true,
                modifier = Modifier.testTag(WatchTags.END_LEARN),
              )
              PhonoButton(
                stringResource(R.string.watch_end_practice_change, name),
                { callbacks.onPractice(change.label) },
                kind = PhonoButtonKind.SECONDARY,
                modifier = Modifier.testTag(WatchTags.END_PRACTICE),
              )
            }
          }
          EndKind.SAVED_END -> {
            Title(stringResource(R.string.watch_end_all_saved))
            PhonoButton(
              stringResource(R.string.watch_end_practice),
              { callbacks.onPractice(null) },
              fillWidth = true,
              modifier = Modifier.testTag(WatchTags.END_PRACTICE),
            )
          }
          EndKind.NOTHING_LEFT -> {
            Title(stringResource(R.string.watch_end_seen_every))
            PhonoButton(
              stringResource(R.string.watch_end_replay_mix),
              callbacks.onReplayMix,
              fillWidth = true,
              modifier = Modifier.testTag(WatchTags.END_REPLAY_MIX),
            )
            PhonoButton(
              stringResource(R.string.watch_end_practice),
              { callbacks.onPractice(null) },
              kind = PhonoButtonKind.SECONDARY,
              modifier = Modifier.testTag(WatchTags.END_PRACTICE),
            )
            PhonoButton(
              stringResource(R.string.watch_end_add_clip),
              callbacks.onAddClip,
              kind = PhonoButtonKind.SECONDARY,
              modifier = Modifier.testTag(WatchTags.END_ADD_CLIP),
            )
          }
        }
        if (state.clipsOnTheWay > 0) {
          Notice(
            body = pluralStringResource(R.plurals.watch_end_on_the_way, state.clipsOnTheWay, state.clipsOnTheWay),
            kind = NoticeKind.INFO,
            modifier = Modifier.testTag(WatchTags.END_ON_THE_WAY),
          )
        }
      }
    }
  }
}

@Composable
private fun Title(text: String) {
  PhonoText(
    text,
    style = MaterialTheme.typography.headlineMedium,
    color = PhonoTheme.colors.ink,
    modifier = Modifier.semantics { heading() }.testTag(WatchTags.END_TITLE),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetSummaryBody(summary: SetSummary?, callbacks: EndCallbacks) {
  val colors = PhonoTheme.colors
  val today = summary?.today ?: true
  Kicker(stringResource(if (today) R.string.watch_end_kicker_today else R.string.watch_end_kicker_set))
  PhonoText(
    stringResource(if (today) R.string.watch_end_done_today else R.string.watch_end_set_done),
    style = MaterialTheme.typography.displaySmall,
    color = colors.ink,
    modifier = Modifier.semantics { heading() }.testTag(WatchTags.END_TITLE),
  )
  Illustration(PhonoIllustrations.SetDone)
  if (summary != null) {
    DoubleRule()
    val shorts = pluralStringResource(R.plurals.watch_end_shorts, summary.shorts, summary.shorts)
    val tally = if (summary.questions > 0) {
      stringResource(
        R.string.watch_end_tally,
        shorts,
        pluralStringResource(R.plurals.watch_end_questions, summary.questions, summary.questions),
        stringResource(R.string.watch_end_right, summary.right, summary.questions),
      )
    } else {
      shorts
    }
    PhonoText(tally, style = PhonoTheme.type.accentItalic, color = colors.ink, modifier = Modifier.testTag(WatchTags.END_TALLY))
    DoubleRule()
    if (summary.noQuestions) {
      PhonoText(
        stringResource(R.string.watch_end_no_questions),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
        modifier = Modifier.testTag(WatchTags.END_NO_QUESTIONS),
      )
    }
    if (summary.met.isNotEmpty()) {
      PhonoText(stringResource(R.string.watch_end_met), style = MaterialTheme.typography.titleSmall, color = colors.ink)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4)) {
        summary.met.forEach { met ->
          ChangeChip(
            stringResource(R.string.watch_end_met_count, met.name.replaceFirstChar { it.uppercase() }, met.count),
            met.family,
            onClick = { callbacks.onLearn(met.label) },
            modifier = Modifier.testTag(WatchTags.met(met.label.value)),
          )
        }
      }
    }
  }
  PhonoText(stringResource(R.string.watch_end_nothing_plays), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
  PhonoButton(stringResource(R.string.watch_end_back_home), callbacks.onHome, fillWidth = true, modifier = Modifier.testTag(WatchTags.END_HOME))
  PhonoButton(
    stringResource(R.string.watch_end_another_set),
    callbacks.onAnotherSet,
    kind = PhonoButtonKind.SECONDARY,
    modifier = Modifier.testTag(WatchTags.END_ANOTHER),
  )
  PhonoButton(
    stringResource(R.string.watch_end_practice),
    { callbacks.onPractice(null) },
    kind = PhonoButtonKind.SECONDARY,
    modifier = Modifier.testTag(WatchTags.END_PRACTICE),
  )
}

@Composable
private fun ClipEndBody(end: ClipEnd?, callbacks: EndCallbacks) {
  val colors = PhonoTheme.colors
  Title(end?.title?.let { stringResource(R.string.watch_end_of_clip, it) } ?: stringResource(R.string.watch_end_of_clip_untitled))
  if (end != null) {
    PhonoText(
      stringResource(
        R.string.watch_end_clip_tally,
        pluralStringResource(R.plurals.watch_end_shorts, end.shorts, end.shorts),
        pluralStringResource(R.plurals.watch_end_kinds, end.kinds, end.kinds),
      ),
      style = MaterialTheme.typography.bodyLarge,
      color = colors.ink2,
      modifier = Modifier.testTag(WatchTags.END_TALLY),
    )
  }
  PhonoButton(stringResource(R.string.watch_end_watch_again), callbacks.onWatchAgain, fillWidth = true, modifier = Modifier.testTag(WatchTags.END_AGAIN))
  end?.next?.let { next ->
    PhonoButton(
      stringResource(R.string.watch_end_next_clip, next.title),
      { callbacks.onNextClip(next.jobId) },
      kind = PhonoButtonKind.SECONDARY,
      modifier = Modifier.testTag(WatchTags.END_NEXT_CLIP),
    )
  }
  PhonoButton(
    stringResource(R.string.watch_end_practice),
    { callbacks.onPractice(null) },
    kind = PhonoButtonKind.SECONDARY,
    modifier = Modifier.testTag(WatchTags.END_PRACTICE),
  )
  PhonoButton(
    stringResource(R.string.watch_end_back_home),
    callbacks.onHome,
    kind = PhonoButtonKind.QUIET,
    modifier = Modifier.testTag(WatchTags.END_HOME),
  )
}
