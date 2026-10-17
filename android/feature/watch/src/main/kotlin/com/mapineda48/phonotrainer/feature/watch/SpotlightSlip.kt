package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.mapineda48.phonotrainer.core.designsystem.component.Kicker
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PerforatedTopShape
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.ui.ChangeChip
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.MarkedWordSpan
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PracticeBadge

/**
 * The spotlight slip (spec F-07 item 3, design 9.3): the change chip and the practice badge, "LISTEN FOR" and the word
 * in quotes underlined in its family's style, the "Now" pill while the word plays, the IPA line, the one-line cue,
 * and "Play the word" / "Slowly". Tapping anywhere else on the slip opens the lesson on the word. [compact] (large
 * text, design 9.6) keeps the chips, the word and the IPA; the cue and the buttons move to the lesson.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpotlightSlipCard(
  slip: SpotlightSlip,
  playhead: State<Long>,
  savedTag: Boolean,
  compact: Boolean,
  onOpenLesson: () -> Unit,
  onPlayWord: () -> Unit,
  onSlowly: () -> Unit,
  onOpenChange: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = PhonoTheme.colors
  val now by remember(slip) { derivedStateOf { playhead.value in slip.startMs..slip.endMs } }
  val name = slip.changeName.replaceFirstChar { it.uppercase() }
  val word = if (slip.nextWordText != null) {
    stringResource(R.string.watch_word_pair, slip.wordText, slip.nextWordText)
  } else {
    stringResource(R.string.watch_quoted, slip.wordText)
  }
  val ipa = ipaLine(slip)
  val adviceWords = stringResource(if (slip.advice == Advice.PRODUCE) R.string.watch_advice_produce else R.string.watch_advice_understand)
  val description = stringResource(
    R.string.watch_slip_description,
    slip.changeName,
    word,
    adviceWords.replaceFirstChar { it.uppercase() },
    listOfNotNull(ipa?.replace("→", "to")?.plus("."), cueOf(slip)).joinToString(" "),
  )
  val openLabel = stringResource(R.string.watch_open_lesson_label)
  val shape = PerforatedTopShape()
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .background(colors.sheet)
      .border(PhonoSizes.hairline, colors.hairline, shape)
      .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpenLesson)
      .semantics(mergeDescendants = true) {
        contentDescription = description
        onClick(label = openLabel) { onOpenLesson(); true }
      }
      .padding(horizontal = PhonoSpacing.s14, vertical = PhonoSpacing.s12)
      .testTag(WatchTags.SLIP),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
    ) {
      ChangeChip(name, slip.family, onClick = onOpenChange)
      PracticeBadge(slip.advice, register = registerWords(slip.register))
      if (savedTag) PhonoBadge(stringResource(R.string.watch_saved_tag), icon = PhonoIcons.Saved, modifier = Modifier.testTag(WatchTags.SAVED_TAG))
    }
    Kicker(stringResource(R.string.watch_listen_for), modifier = Modifier.clearAndSetSemantics { })
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
      modifier = Modifier.clearAndSetSemantics { },
    ) {
      MarkedWordSpan(
        text = word,
        family = slip.family,
        style = PhonoTheme.type.spotlightWord,
        modifier = Modifier.weight(1f, fill = false).testTag(WatchTags.SLIP_WORD),
      )
      if (now) NowPill()
    }
    slip.contraction?.let {
      PhonoBadge(it, icon = PhonoIcons.FamilyLexical, modifier = Modifier.clearAndSetSemantics { }.testTag(WatchTags.CONTRACTION))
    }
    ipa?.let {
      IpaText(it, style = PhonoTheme.type.ipaSlip, modifier = Modifier.clearAndSetSemantics { }.testTag(WatchTags.SLIP_IPA))
    }
    if (!compact) {
      cueOf(slip)?.let {
        PhonoText(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = colors.ink2,
          modifier = Modifier.clearAndSetSemantics { }.testTag(WatchTags.SLIP_CUE),
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
      ) {
        PhonoButton(
          text = stringResource(R.string.watch_play_word),
          onClick = onPlayWord,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.Play,
          modifier = Modifier.testTag(WatchTags.PLAY_WORD),
        )
        PhonoButton(
          text = stringResource(R.string.watch_slowly),
          onClick = onSlowly,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.Slow,
          modifier = Modifier.testTag(WatchTags.SLOWLY),
        )
      }
    }
  }
}

/** "No labelled change here: listen to the rhythm." in place of the slip ("In order" only, F-07 States). */
@Composable
internal fun NoSpotlightLine(modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = PhonoSizes.minTarget).testTag(WatchTags.NO_SPOTLIGHT),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    Icon(painterResource(PhonoIcons.Info), null, tint = colors.ink2, modifier = Modifier.size(PhonoSizes.iconButton))
    PhonoText(stringResource(R.string.watch_no_spotlight), style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
  }
}

/** The "Now" pill: ink fill, speaker icon and "Now", while the studied word plays (design 9.3). */
@Composable
private fun NowPill() {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier
      .clip(CircleShape)
      .background(colors.ink)
      .padding(horizontal = PhonoSpacing.s10, vertical = PhonoSpacing.s4)
      .testTag(WatchTags.NOW),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4),
  ) {
    Icon(painterResource(PhonoIcons.Now), null, tint = colors.onInk, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(stringResource(R.string.watch_now), style = MaterialTheme.typography.labelMedium, color = colors.onInk)
  }
}

/** "/ˈwɔtɚ/ → [ˈwɔɾɚ]", or "[…]" alone when the word has no dictionary form. */
@Composable
internal fun ipaLine(slip: SpotlightSlip): String? {
  val said = slip.realizedIpa ?: return null
  val dict = slip.dictIpa ?: return stringResource(R.string.watch_ipa_said, said)
  return stringResource(R.string.watch_ipa_line, dict, said)
}

/** The cue, with "Just learn to hear it: no need to copy it." for a "Recognize only" change (F-07 item 3). */
@Composable
private fun cueOf(slip: SpotlightSlip): String? {
  val suffix = if (slip.advice == Advice.UNDERSTAND) stringResource(R.string.watch_recognize_suffix) else null
  return listOfNotNull(slip.cue, suffix).joinToString(" ").ifEmpty { null }
}

@Composable
private fun registerWords(register: Register?): String? = when (register) {
  Register.UNIVERSAL -> stringResource(R.string.watch_register_universal)
  Register.CASUAL -> stringResource(R.string.watch_register_casual)
  Register.MARKED -> stringResource(R.string.watch_register_marked)
  null -> null
}
