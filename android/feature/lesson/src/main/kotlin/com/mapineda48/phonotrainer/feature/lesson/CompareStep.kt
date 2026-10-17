package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.designsystem.theme.SortShape
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.ui.IpaText
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines

/**
 * Step 2, Compare (spec F-10 step 2, design 9.10, renders `10` and `11`): the two forms with their "What's this?",
 * the legend, then one type sort per sound, wrapping onto new rows so every sound shows without truncation; across a
 * boundary a header per word and a dashed divider, then the gap box. A sort plays its sound and opens its card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompareStep(compare: CompareUi, hasAudio: Boolean, actions: LessonActions, explainer: Explainer) {
  val colors = PhonoTheme.colors
  Forms(compare, explainer)
  compare.spoken?.let { spoken ->
    PhonoText(
      stringResource(R.string.lesson_spoken, compare.wordText, spoken),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.ink2,
      modifier = Modifier.testTag(LessonTags.SPOKEN),
    )
  }
  if (compare.noDictionaryForm) {
    Notice(
      stringResource(R.string.lesson_no_dictionary, compare.wordText),
      lead = stringResource(R.string.lesson_no_dictionary_title),
      modifier = Modifier.testTag(LessonTags.NO_DICTIONARY),
    )
  } else {
    Legend()
    Sorts(compare, actions.onPickSort)
    compare.gap?.let { gap -> GapBox(gap, compare.wordText, compare.nextText.orEmpty()) }
    PhonoText(
      stringResource(if (compare.narrow) R.string.lesson_compare_note_narrow else R.string.lesson_compare_note_espeak),
      style = MaterialTheme.typography.bodySmall,
      color = colors.inkMuted,
      modifier = Modifier.testTag(LessonTags.COMPARE_NOTE),
    )
  }
  compare.selected?.let { sound -> SoundCard(sound, hasAudio, actions, explainer) }
}

/** "Dictionary /…/" (sheet, lineStrong ring) beside "What was said […]" (ink fill), and espeak's aligner row. */
@Composable
private fun Forms(compare: CompareUi, explainer: Explainer) {
  val colors = PhonoTheme.colors
  val nothing = stringResource(R.string.lesson_nothing)
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    FormCard(
      label = stringResource(R.string.lesson_dictionary),
      form = stringResource(R.string.lesson_dictionary_form, compare.dictionary ?: nothing),
      term = DICTIONARY_KEY,
      inked = false,
      explainer = explainer,
      modifier = Modifier.weight(1f).fillMaxHeight().testTag(LessonTags.FORM_DICTIONARY),
    )
    FormCard(
      label = stringResource(R.string.lesson_said),
      form = stringResource(R.string.lesson_said_form, compare.said ?: nothing),
      term = SAID_KEY,
      inked = true,
      explainer = explainer,
      modifier = Modifier.weight(1f).fillMaxHeight().testTag(LessonTags.FORM_SAID),
    )
  }
  compare.aligner?.let { aligner ->
    FormCard(
      label = stringResource(R.string.lesson_aligner),
      form = stringResource(R.string.lesson_said_form, aligner),
      term = ALIGNER_KEY,
      inked = false,
      explainer = explainer,
      modifier = Modifier.fillMaxWidth().testTag(LessonTags.FORM_ALIGNER),
    )
  }
}

private const val DICTIONARY_KEY = "dictionary-form"
private const val SAID_KEY = "what-was-said"
private const val ALIGNER_KEY = "canonical"

@Composable
private fun FormCard(label: String, form: String, term: String, inked: Boolean, explainer: Explainer, modifier: Modifier) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.large)
  val content = if (inked) colors.onInk else colors.ink
  Column(
    modifier = modifier
      .clip(shape)
      .background(if (inked) colors.ink else colors.sheet)
      .then(if (inked) Modifier else Modifier.border(PhonoSizes.ring, colors.lineStrong, shape))
      .padding(start = PhonoSpacing.s14, end = PhonoSpacing.s2, bottom = PhonoSpacing.s12),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      PhonoText(label, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.weight(1f).padding(top = PhonoSpacing.s8))
      if (explainer.hasTerm(term)) {
        val id = explainer.termKey(term)
        // The "?" of the inked card is drawn in onInk: its icon follows the card, not the page.
        PhonoIconButton(
          icon = PhonoIcons.Help,
          contentDescription = stringResource(R.string.lesson_explain, label),
          onClick = { explainer.open(id) },
          iconSize = PhonoSizes.iconButton,
          tint = content,
          modifier = Modifier.testTag(UiTags.EXPLAIN_BUTTON).focusRequester(explainer.requester(id)),
        )
      } else {
        Box(Modifier.size(PhonoSizes.minTarget))
      }
    }
    IpaText(form, style = PhonoTheme.type.ipaForm, color = content, modifier = Modifier.padding(end = PhonoSpacing.s12))
  }
}

/** "= same · ≠ changed · – dropped · + added", directly above the sorts; read as one line. */
@Composable
private fun Legend() {
  val colors = PhonoTheme.colors
  val items = listOf(
    MARK_SAME to stringResource(R.string.lesson_mark_same),
    MARK_CHANGED to stringResource(R.string.lesson_mark_changed),
    MARK_DROPPED to stringResource(R.string.lesson_mark_dropped),
    MARK_ADDED to stringResource(R.string.lesson_mark_added),
  )
  val description = stringResource(R.string.lesson_legend) + ": " + items.joinToString(", ") { (mark, word) -> "$mark $word" }
  Row(
    modifier = Modifier.fillMaxWidth().testTag(LessonTags.LEGEND).clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    items.forEach { (mark, word) ->
      Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s4), verticalAlignment = Alignment.CenterVertically) {
        PhonoText(mark, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.ink)
        PhonoText(word, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
      }
    }
  }
}

private const val MARK_SAME = "="
private const val MARK_CHANGED = "≠"
private const val MARK_DROPPED = "–"
private const val MARK_ADDED = "+"

/** More sounds than this: the narrower 64 dp sorts (design 9.10). */
private const val MANY_SOUNDS = 5
private val SortWidth = 80.dp
private val SortWidthNarrow = 64.dp

/** The sorts: one group, or the word's and the next word's with a header each and a dashed divider between them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sorts(compare: CompareUi, onPick: (Int) -> Unit) {
  val width = if (compare.sorts.size > MANY_SOUNDS) SortWidthNarrow else SortWidth
  val selected = compare.selected?.sort?.index
  val own = compare.sorts.filterNot { it.nextWord }
  val next = compare.sorts.filter { it.nextWord }
  FlowRow(
    modifier = Modifier.fillMaxWidth().testTag(LessonTags.SORTS),
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    if (next.isEmpty()) {
      own.forEach { CompareSort(it, it.index == selected, width) { onPick(it.index) } }
    } else {
      SortGroup(compare.wordText, own, selected, width, onPick, next = false)
      DashedDivider()
      SortGroup(compare.nextText.orEmpty(), next, selected, width, onPick, next = true)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortGroup(word: String, sorts: List<SortUi>, selected: Int?, width: Dp, onPick: (Int) -> Unit, next: Boolean) {
  val colors = PhonoTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6)) {
    Column(Modifier.testTag(LessonTags.sortGroup(next)).semantics(mergeDescendants = true) {}) {
      PhonoText(stringResource(R.string.lesson_word, word), style = PhonoTheme.type.accentItalic, color = colors.ink2)
      Box(Modifier.width(width).height(PhonoSizes.hairline).background(colors.lineStrong))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      sorts.forEach { CompareSort(it, it.index == selected, width) { onPick(it.index) } }
    }
  }
}

@Composable
private fun DashedDivider() {
  val color = PhonoTheme.colors.lineStrong
  Box(
    Modifier
      .width(PhonoSpacing.s8)
      .heightIn(min = DividerHeight)
      .drawBehind {
        val x = size.width / 2
        drawLine(
          color, Offset(x, 0f), Offset(x, size.height), PhonoSizes.ring.toPx(),
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
      },
  )
}

private val DividerHeight = 180.dp

/**
 * One type sort (design 9.10 item 3): the expected sound on top (struck through when dropped), the mark in a 30 dp
 * circle, what was said below (a dashed empty slot when nothing was), and the mark's word under it. Same: quiet;
 * changed: 2 dp ink border and an ink-filled mark; dropped: a galley sort with a dashed outline; added: a dashed ink
 * outline and an empty dictionary slot; selected: ink fill and a double ring. TalkBack: "t changed to ɾ", "j, not
 * heard" (F-10-AC1, AC2).
 */
@Composable
private fun CompareSort(sort: SortUi, selected: Boolean, width: Dp, onClick: () -> Unit) {
  val colors = PhonoTheme.colors
  val label = when (sort.status) {
    CompareStatus.SAME -> stringResource(R.string.lesson_sort_same, sort.said ?: sort.dictionary.orEmpty())
    CompareStatus.CHANGED -> stringResource(R.string.lesson_sort_changed, sort.dictionary.orEmpty(), sort.said.orEmpty())
    CompareStatus.NOT_HEARD -> stringResource(R.string.lesson_sort_dropped, sort.dictionary.orEmpty())
    CompareStatus.ADDED -> stringResource(R.string.lesson_sort_added, sort.said.orEmpty())
  }
  val (mark, word) = markOf(sort.status)
  val fill = when {
    selected -> colors.ink
    sort.status == CompareStatus.NOT_HEARD -> colors.galley
    else -> colors.sheet
  }
  val content = if (selected) colors.onInk else colors.ink
  val faint = if (selected) colors.onInk else colors.ink2
  Box(
    modifier = Modifier
      .width(width + RingGap * 2)
      .then(if (selected) Modifier.border(2.dp, colors.ink, OuterSortShape) else Modifier)
      .padding(RingGap),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = SortMinHeight)
        .clip(SortShape)
        .background(fill)
        .sortOutline(sort.status, selected, colors.ink, colors.inkMuted, colors.lineStrong)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {
          contentDescription = label
          this.selected = selected
        }
        .testTag(LessonTags.sort(sort.index))
        .padding(vertical = PhonoSpacing.s10, horizontal = PhonoSpacing.s4),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
    ) {
      SoundSlot(sort.dictionary, faint, struck = sort.status == CompareStatus.NOT_HEARD, dashed = selected.not() && sort.status == CompareStatus.ADDED)
      Box(
        modifier = Modifier
          .size(MarkCircle)
          .clip(CircleShape)
          .background(
            when {
              selected -> colors.onInk
              sort.status == CompareStatus.CHANGED -> colors.ink
              else -> Color.Transparent
            },
          )
          .then(if (!selected && sort.status != CompareStatus.CHANGED) Modifier.border(PhonoSizes.ring, colors.outline, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
      ) {
        PhonoText(
          mark,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = when {
            selected -> colors.ink
            sort.status == CompareStatus.CHANGED -> colors.onInk
            sort.status == CompareStatus.SAME -> colors.inkMuted
            else -> colors.ink
          },
        )
      }
      SoundSlot(sort.said, content, struck = false, dashed = !selected && sort.status == CompareStatus.NOT_HEARD)
      PhonoText(stringResource(word), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = content, textAlign = TextAlign.Center)
    }
  }
}

private val RingGap = 3.dp
private val OuterSortShape = RoundedCornerShape(PhonoRadius.sort + RingGap)
private val SortMinHeight = 140.dp
private val MarkCircle = 30.dp
private val SlotSize = 34.dp

private fun markOf(status: CompareStatus): Pair<String, Int> = when (status) {
  CompareStatus.SAME -> MARK_SAME to R.string.lesson_mark_same
  CompareStatus.CHANGED -> MARK_CHANGED to R.string.lesson_mark_changed
  CompareStatus.NOT_HEARD -> MARK_DROPPED to R.string.lesson_mark_dropped
  CompareStatus.ADDED -> MARK_ADDED to R.string.lesson_mark_added
}

/** A sound in its sort, or the empty slot of a sound that is not there (dashed when it marks the change). */
@Composable
private fun SoundSlot(symbol: String?, color: Color, struck: Boolean, dashed: Boolean) {
  if (symbol != null) {
    PhonoText(
      symbol,
      style = PhonoTheme.type.ipaSort.copy(textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None),
      color = color,
      textAlign = TextAlign.Center,
    )
  } else {
    val outline = PhonoTheme.colors.inkMuted
    Box(
      Modifier
        .size(SlotSize)
        .drawBehind {
          if (dashed) {
            drawRoundRect(
              outline,
              cornerRadius = CornerRadius(6.dp.toPx()),
              style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
          }
        },
    )
  }
}

/** The sort's outline by its state: a quiet ring, the changed 2 dp ink ring, or the dashed outline of a gap. */
private fun Modifier.sortOutline(status: CompareStatus, selected: Boolean, ink: Color, muted: Color, quiet: Color): Modifier =
  when {
    selected -> this
    status == CompareStatus.CHANGED -> border(2.dp, ink, SortShape)
    status == CompareStatus.SAME -> border(PhonoSizes.ring, quiet, SortShape)
    else -> drawBehind {
      drawRoundRect(
        if (status == CompareStatus.ADDED) ink else muted,
        cornerRadius = CornerRadius(PhonoRadius.sort.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
      )
    }
  }

/** The gap box (design 9.10 item 4): the family's tint and ring, its icon, and the gap in milliseconds, in bold. */
@Composable
private fun GapBox(gap: GapUi, word: String, next: String) {
  val colors = PhonoTheme.colors
  val shape = RoundedCornerShape(PhonoRadius.medium)
  val amount = if (gap.ms <= 0) stringResource(R.string.lesson_gap_overlap) else stringResource(R.string.lesson_gap_ms, gap.ms)
  val sentence = stringResource(
    if (gap.ms <= LessonLogic.LINKED_GAP_MS) R.string.lesson_gap_linked else R.string.lesson_gap_unlinked,
    word,
    next,
    amount,
  )
  val text = buildAnnotatedString {
    append(sentence)
    val start = sentence.indexOf(amount)
    if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + amount.length)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(colors.family.tintOf(gap.family))
      .border(PhonoSizes.ring, colors.family.of(gap.family), shape)
      .padding(PhonoSpacing.s12)
      .testTag(LessonTags.GAP)
      .semantics(mergeDescendants = true) {},
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Icon(painterResource(MarkLines.iconOf(gap.family)), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconBar))
    androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
  }
}

/**
 * The sound card of the selected sort (design 9.10 item 5): the "≠ changed · /t/ → [ɾ]" pill, the symbol, its name,
 * "As in “water”." and what the mouth does, "Play this sound", "See it in the mouth" and "Open in the IPA chart". It is
 * a polite live region, so picking a sort is heard (web `aria-live`). The mouth still of WP-29 is not drawn here in
 * v1-core (step 4's fallback names the sound).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundCard(sound: SoundUi, hasAudio: Boolean, actions: LessonActions, explainer: Explainer) {
  val colors = PhonoTheme.colors
  val sort = sound.sort
  val pill = when (sort.status) {
    CompareStatus.SAME -> stringResource(R.string.lesson_pill_same, sound.symbol)
    CompareStatus.CHANGED -> stringResource(R.string.lesson_pill_changed, sort.dictionary.orEmpty(), sort.said.orEmpty())
    CompareStatus.NOT_HEARD -> stringResource(R.string.lesson_pill_dropped, sound.symbol)
    CompareStatus.ADDED -> stringResource(R.string.lesson_pill_added, sound.symbol)
  }
  val bracketed = if (sort.said != null) {
    stringResource(R.string.lesson_said_form, sound.symbol)
  } else {
    stringResource(R.string.lesson_dictionary_form, sound.symbol)
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(PhonoRadius.large))
      .background(colors.galley)
      .semantics { liveRegion = LiveRegionMode.Polite }
      .testTag(LessonTags.SOUND_CARD)
      .padding(PhonoSpacing.s16),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
  ) {
    Box(
      Modifier
        .clip(CircleShape)
        .background(colors.ink)
        .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6),
    ) {
      IpaText(pill, style = MaterialTheme.typography.labelLarge, color = colors.onInk)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
      IpaText(bracketed, style = PhonoTheme.type.ipaSymbol, transcription = true)
      PhonoText(
        sound.name ?: stringResource(R.string.lesson_sound_unknown),
        style = MaterialTheme.typography.titleMedium,
        color = colors.ink,
        modifier = Modifier.weight(1f),
      )
      sound.explainKey?.let { key -> TermButton(key, bracketed, explainer) }
    }
    sound.example?.let { example ->
      PhonoText(stringResource(R.string.lesson_sound_as_in, example), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
    }
    sound.cue?.let { PhonoText(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2) }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      PhonoButton(
        text = stringResource(R.string.lesson_play_sound),
        onClick = actions.onPlaySound,
        kind = PhonoButtonKind.PRIMARY,
        enabled = hasAudio,
        leadingIcon = PhonoIcons.Play,
        modifier = Modifier.testTag(LessonTags.SOUND_PLAY),
      )
      if (sound.name != null) {
        PhonoButton(
          text = stringResource(R.string.lesson_see_mouth),
          onClick = actions.onSeeMouth,
          kind = PhonoButtonKind.SECONDARY,
          leadingIcon = PhonoIcons.SeeMouth,
          modifier = Modifier.testTag(LessonTags.SOUND_MOUTH),
        )
      }
      LinkButton(
        text = stringResource(R.string.lesson_ipa_chart),
        onClick = { actions.onIpaChart(IpaChartSymbol.of(sound.symbol)) },
        trailingArrow = true,
        modifier = Modifier.testTag(LessonTags.SOUND_CHART),
      )
    }
  }
}

/** The symbol the IPA chart opens on: without stress marks. */
internal object IpaChartSymbol {
  fun of(symbol: String): String = IpaReading.bare(symbol)
}
