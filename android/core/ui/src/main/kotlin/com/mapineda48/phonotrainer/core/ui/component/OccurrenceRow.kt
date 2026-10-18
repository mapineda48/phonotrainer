package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.tabular
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.R
import com.mapineda48.phonotrainer.core.ui.formatClock

/** An example row (design 9.25): the occurrence and whether it is the one playing. */
@Immutable
data class OccurrenceRowState(val occurrence: Occurrence, val playing: Boolean = false)

/** Test tags of the example row. */
object OccurrenceTags {
  const val PLAY = "ui.occurrence.play"
  const val LESSON = "ui.occurrence.lesson"
  const val WORDS = "ui.occurrence.words"
  const val FORMS = "ui.occurrence.forms"
  const val META = "ui.occurrence.meta"
}

/**
 * An example from the learner's clips (WP-20; API frozen at M2, work-plan 4.5; design 9.25, render `13`): a 44 dp play
 * circle in a 48 dp target (ink-filled with "Stop" while [OccurrenceRowState.playing]), the word marked with its
 * change's line style (with the next word, linked, when the change runs into it), the dictionary form and what was
 * said ("/ˈwɔtɚ/ → [ˈwɔɾɚ]", web `ExampleList`), "clip · time", and a chevron. The words, forms and meta are one
 * target that opens the word's lesson ([onOpenLesson]); TalkBack reads them as one, with the symbols' names, and says
 * "Open in its lesson" as the action.
 *
 * The span to play is [OccurrenceRowPlayback.span], the same for every caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OccurrenceRow(
  state: OccurrenceRowState,
  onPlay: () -> Unit,
  onOpenLesson: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val occurrence = state.occurrence
  val word = OccurrenceRowPlayback.displayWord(occurrence.wordText)
  val next = occurrence.nextWordText?.takeIf { OccurrenceRowPlayback.runsIntoNext(occurrence) }?.let(OccurrenceRowPlayback::displayWord)
  val words = if (next != null) stringResource(R.string.occurrence_words, word, next) else word
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = ROW_MIN),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PlayCircle(playing = state.playing, words = words, onClick = onPlay)
    Row(
      modifier = Modifier
        .weight(1f)
        .heightIn(min = PhonoSizes.minTarget)
        .clickable(onClickLabel = stringResource(R.string.occurrence_lesson), role = Role.Button, onClick = onOpenLesson)
        .testTag(OccurrenceTags.LESSON)
        .padding(vertical = PhonoSpacing.s8),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s2)) {
        FlowRow(
          modifier = Modifier.testTag(OccurrenceTags.WORDS),
          verticalArrangement = Arrangement.Center,
          horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
        ) {
          MarkedWordSpan(
            text = word,
            family = occurrence.family,
            style = MaterialTheme.typography.titleMedium,
            linksToNext = next != null && occurrence.family == Family.BOUNDARY,
          )
          if (next != null) {
            MarkedWordSpan(text = next, family = null, style = MaterialTheme.typography.titleMedium)
          }
        }
        IpaText(
          text = forms(occurrence),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.ink2,
          modifier = Modifier.testTag(OccurrenceTags.FORMS),
        )
        PhonoText(
          stringResource(R.string.occurrence_meta, occurrence.clipTitle, formatClock(occurrence.startMs)),
          style = MaterialTheme.typography.bodySmall.tabular(),
          color = colors.ink2,
          modifier = Modifier.testTag(OccurrenceTags.META),
        )
      }
      Icon(painterResource(PhonoIcons.ChevronRight), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconButton))
    }
  }
}

/** "/wɔtɚ/ → [wɔɾɚ]", or only "[…]" when the dictionary has no form for the word. */
@Composable
private fun forms(occurrence: Occurrence): String {
  val said = "[${OccurrenceRowPlayback.saidForm(occurrence)}]"
  val dictionary = OccurrenceRowPlayback.dictionaryForm(occurrence) ?: return said
  return stringResource(R.string.occurrence_forms, "/$dictionary/", said)
}

/** The 44 dp play circle (an ink ring; ink-filled with a stop square while playing) in a 48 dp target. */
@Composable
private fun PlayCircle(playing: Boolean, words: String, onClick: () -> Unit) {
  val colors = PhonoTheme.colors
  val label = stringResource(if (playing) R.string.occurrence_stop else R.string.occurrence_play, words)
  val state = stringResource(if (playing) R.string.occurrence_playing else R.string.occurrence_stopped)
  Box(
    modifier = Modifier
      .focusRing(CircleShape)
      .size(PhonoSizes.minTarget)
      .clip(CircleShape)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics {
        contentDescription = label
        stateDescription = state
      }
      .testTag(OccurrenceTags.PLAY),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(CIRCLE)
        .clip(CircleShape)
        .background(if (playing) colors.ink else colors.sheet)
        .border(PhonoSizes.ring, colors.ink, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(if (playing) PhonoIcons.Stop else PhonoIcons.Play),
        contentDescription = null,
        tint = if (playing) colors.onInk else colors.ink,
        modifier = Modifier.size(PhonoSizes.iconButton),
      )
    }
  }
}

private val CIRCLE = 44.dp
private val ROW_MIN = 64.dp

@PreviewLightDark
@Composable
private fun OccurrenceRowPreview() {
  PhonoPreviewSurface {
    OccurrenceRow(OccurrenceRowState(PreviewData.occurrence), onPlay = {}, onOpenLesson = {})
    OccurrenceRow(OccurrenceRowState(PreviewData.occurrence, playing = true), onPlay = {}, onOpenLesson = {})
    OccurrenceRow(
      OccurrenceRowState(
        PreviewData.occurrence.copy(
          wordText = "Did",
          dictIpa = "dɪd",
          realizedIpa = "dɪdʒ",
          family = Family.BOUNDARY,
          crossesBoundary = true,
          nextWordText = "you",
        ),
      ),
      onPlay = {},
      onOpenLesson = {},
    )
  }
}
