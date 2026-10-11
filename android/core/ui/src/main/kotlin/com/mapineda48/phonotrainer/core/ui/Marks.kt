package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoBadge
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.component.focusRing
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoColors
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.PatternChoice
import com.mapineda48.phonotrainer.core.ui.marks.MarkLines
import com.mapineda48.phonotrainer.core.ui.marks.drawFamilyLine
import com.mapineda48.phonotrainer.core.ui.marks.drawLinkArc

// Marks of a change (design 9.4, spec section 9 "mark vocabulary"). Colour is never the only cue: each family is a
// line style + an icon + a name, and the hue is the third cue (A-01). Family hues sit only on page or sheet.

/** The name of [family], as every mark and legend says it. */
@Composable
fun familyName(family: Family): String = stringResource(
  when (family) {
    Family.REDUCTION -> R.string.ui_family_reduction
    Family.TD -> R.string.ui_family_td
    Family.ASSIMILATION -> R.string.ui_family_assimilation
    Family.BOUNDARY -> R.string.ui_family_boundary
    Family.LEXICAL -> R.string.ui_family_lexical
    Family.OTHER -> R.string.ui_family_other
  },
)

/** The name of [family]'s line style ("dashed line"), said next to its name in legends and Watch by change. */
@Composable
fun familyLineName(family: Family): String = stringResource(
  when (family) {
    Family.REDUCTION -> R.string.ui_line_reduction
    Family.TD -> R.string.ui_line_td
    Family.ASSIMILATION -> R.string.ui_line_assimilation
    Family.BOUNDARY -> R.string.ui_line_boundary
    Family.LEXICAL -> R.string.ui_line_lexical
    Family.OTHER -> R.string.ui_line_other
  },
)

/** What [family]'s icon means ("the stop is clipped"). */
@Composable
fun familyMeaning(family: Family): String = stringResource(
  when (family) {
    Family.REDUCTION -> R.string.ui_meaning_reduction
    Family.TD -> R.string.ui_meaning_td
    Family.ASSIMILATION -> R.string.ui_meaning_assimilation
    Family.BOUNDARY -> R.string.ui_meaning_boundary
    Family.LEXICAL -> R.string.ui_meaning_lexical
    Family.OTHER -> R.string.ui_meaning_other
  },
)

/**
 * The family's mark: its icon (in the family hue, on page or sheet), its name and, with [showLine], a short sample of
 * its line style; one node for TalkBack ("t/d processes, dashed line").
 */
@Composable
fun FamilyMark(family: Family, modifier: Modifier = Modifier, showLine: Boolean = true) {
  val colors = PhonoTheme.colors
  val hue = colors.family.of(family)
  val name = familyName(family)
  val line = familyLineName(family)
  val description = stringResource(R.string.ui_family_mark_description, name, line)
  Row(
    modifier = modifier.clearAndSetSemantics { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(MarkLines.iconOf(family)), null, tint = hue, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(name, style = MaterialTheme.typography.labelLarge, color = colors.ink)
    if (showLine) {
      val patterns = PhonoTheme.patterns
      Canvas(Modifier.width(28.dp).heightIn(min = 10.dp).padding(top = 2.dp)) {
        drawFamilyLine(family, hue, 0f, size.width, size.height / 2f - 2.dp.toPx(), patterns)
      }
    }
  }
}

/** How a marked word is drawn (design 9.4): shape, never hue. */
enum class WordMarkState {
  NORMAL,

  /** The word being said: an ink fill (6 dp corners) with onInk words; the underline stays below the fill. */
  PLAYING,

  /** The lesson is open on it: a 2 dp ink ring. */
  SELECTED,

  /** Low confidence or nothing heard: a 1.5 dp dashed inkMuted outline; never spotlighted. */
  LOW_CONFIDENCE,

  /** Outside an active filter (transcript): 45 % and no underline; the words stay. */
  DIMMED,
}

/**
 * A word with its change's mark beneath it (design 9.4): the family's line drawn under the text from its baseline
 * (0.26 em below, the web's offset), and with patterns on, the family icon after the word. [family] null draws the
 * word plain. [linksToNext] draws the ‿ arc after a word-boundary word, under the gap to the next word. The caption
 * flow and the transcript (feature modules) compose these words; the accessible text of a word is theirs.
 */
@Composable
fun MarkedWordSpan(
  text: String,
  family: Family?,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  state: WordMarkState = WordMarkState.NORMAL,
  stressed: Boolean = false,
  linksToNext: Boolean = false,
  patterns: Boolean = PhonoTheme.patterns,
) {
  val colors = PhonoTheme.colors
  val hue = family?.let { colors.family.of(it) }
  val marked = family != null && state != WordMarkState.DIMMED
  var baseline by remember { mutableFloatStateOf(0f) }
  val fontSize = style.fontSize
  val topPad = with(LocalDensity.current) { PhonoSpacing.s2.toPx() }
  val shape = RoundedCornerShape(PhonoRadius.word)
  Row(
    modifier = modifier.alpha(if (state == WordMarkState.DIMMED) PhonoColors.DISABLED_ALPHA else 1f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s2),
  ) {
    Box(
      modifier = Modifier
        .drawBehind {
          val em = if (fontSize.isSp) fontSize.toPx() else 16.dp.toPx()
          when (state) {
            // The fill stops above the underline, so the mark stays on paper (design 2.3 rule 1).
            WordMarkState.PLAYING -> drawRoundRect(
              colors.ink,
              size = Size(size.width, (baseline + em * FILL_DESCENT_EM).coerceAtMost(size.height)),
              cornerRadius = CornerRadius(PhonoRadius.word.toPx()),
            )
            WordMarkState.SELECTED -> drawRoundRect(
              colors.ink, cornerRadius = CornerRadius(PhonoRadius.word.toPx()), style = Stroke(2.dp.toPx()),
            )
            WordMarkState.LOW_CONFIDENCE -> drawRoundRect(
              colors.inkMuted,
              cornerRadius = CornerRadius(PhonoRadius.word.toPx()),
              style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            else -> Unit
          }
          if (marked && hue != null) {
            val y = (baseline + em * UNDERLINE_OFFSET_EM)
              .coerceAtMost(size.height - MarkLines.heightDp(family, patterns).dp.toPx())
            drawFamilyLine(family, hue, 0f, size.width, y, patterns)
            if (linksToNext && family == Family.BOUNDARY) drawLinkArc(hue, size.width + 4.dp.toPx(), y)
          }
        }
        .padding(horizontal = PhonoSpacing.s4, vertical = PhonoSpacing.s2)
        .padding(bottom = 6.dp),
    ) {
      Text(
        text = text,
        style = style.copy(fontWeight = if (stressed) FontWeight.Bold else style.fontWeight),
        color = if (state == WordMarkState.PLAYING) colors.onInk else colors.ink,
        onTextLayout = { layout -> baseline = topPad + layout.firstBaseline },
      )
    }
    if (marked && patterns && hue != null) {
      Icon(painterResource(MarkLines.iconOf(family)), null, tint = hue, modifier = Modifier.size(14.dp))
    }
  }
}

private const val UNDERLINE_OFFSET_EM = 0.26f
private const val FILL_DESCENT_EM = 0.2f

/**
 * A change chip (design 9.1): the family's tint, a 1.5 dp ring in the family hue, the family icon and the change's
 * name in ink, at least 32 dp tall in a 48 dp target. Opens the phenomenon page. TalkBack reads the name and the
 * family.
 */
@Composable
fun ChangeChip(label: String, family: Family, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val colors = PhonoTheme.colors
  val description = stringResource(R.string.ui_change_chip_description, label, familyName(family))
  Row(
    modifier = modifier
      .focusRing(CircleShape)
      .minimumInteractiveComponentSize()
      .testTag(UiTags.CHANGE_CHIP)
      .heightIn(min = 32.dp)
      .alpha(if (enabled) 1f else PhonoColors.DISABLED_ALPHA)
      .clip(CircleShape)
      .background(colors.family.tintOf(family))
      .border(PhonoSizes.ring, colors.family.of(family), CircleShape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics(mergeDescendants = true) { contentDescription = description }
      .padding(horizontal = PhonoSpacing.s12, vertical = PhonoSpacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s6),
  ) {
    Icon(painterResource(MarkLines.iconOf(family)), null, tint = colors.ink, modifier = Modifier.size(PhonoSizes.iconInline))
    PhonoText(label, style = MaterialTheme.typography.labelMedium, color = colors.ink)
  }
}

/** "Safe to produce" (mic) / "Recognize only" (ear), a galley pill, never coloured; [register] adds " · casual". */
@Composable
fun PracticeBadge(advice: Advice, modifier: Modifier = Modifier, register: String? = null) {
  val words = stringResource(if (advice == Advice.PRODUCE) R.string.ui_advice_produce else R.string.ui_advice_understand)
  PhonoBadge(
    text = register?.let { stringResource(R.string.ui_advice_with_register, words, it) } ?: words,
    icon = if (advice == Advice.PRODUCE) PhonoIcons.PracticeProduce else PhonoIcons.PracticeRecognize,
    modifier = modifier,
  )
}

/**
 * The marks legend of the More sheet and Settings > Help (design 9.12): the four families and the whole-word changes,
 * each with its icon, name and line style and the web's sample word drawn with its mark; then low confidence and the
 * stressed word.
 */
@Composable
fun MarksLegend(modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12)) {
    PhonoText(
      stringResource(R.string.ui_marks_legend),
      style = MaterialTheme.typography.headlineSmall,
      color = colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    PhonoText(stringResource(R.string.ui_marks_legend_lede), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    val samples = listOf(
      Family.REDUCTION to R.string.ui_sample_reduction,
      Family.TD to R.string.ui_sample_td,
      Family.ASSIMILATION to R.string.ui_sample_assimilation,
      Family.BOUNDARY to R.string.ui_sample_boundary,
      Family.LEXICAL to R.string.ui_sample_lexical,
      Family.OTHER to R.string.ui_sample_other,
    )
    for ((family, sample) in samples) {
      LegendRow(
        word = { MarkedWordSpan(stringResource(sample), family, style = MaterialTheme.typography.bodyLarge) },
        title = familyName(family),
        detail = "${familyLineName(family)} · ${familyMeaning(family)}",
      )
    }
    LegendRow(
      word = {
        MarkedWordSpan(
          stringResource(R.string.ui_sample_low_confidence), null,
          style = MaterialTheme.typography.bodyLarge, state = WordMarkState.LOW_CONFIDENCE,
        )
      },
      title = stringResource(R.string.ui_mark_low_confidence),
      detail = stringResource(R.string.ui_mark_low_confidence_line),
    )
    LegendRow(
      word = {
        MarkedWordSpan(stringResource(R.string.ui_sample_stressed), null, style = MaterialTheme.typography.bodyLarge, stressed = true)
      },
      title = stringResource(R.string.ui_mark_stressed),
      detail = stringResource(R.string.ui_mark_stressed_line),
    )
  }
}

@Composable
private fun LegendRow(word: @Composable () -> Unit, title: String, detail: String) {
  val colors = PhonoTheme.colors
  Row(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    Box(Modifier.width(112.dp)) { word() }
    Column(Modifier.weight(1f)) {
      PhonoText(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
      PhonoText(detail, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
    }
  }
}

@PreviewLightDark
@Composable
private fun MarksPreview() {
  PhonoPreviewSurface {
    FamilyMark(Family.TD)
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      MarkedWordSpan(text = "pick", family = Family.BOUNDARY, linksToNext = true)
      MarkedWordSpan(text = "water", family = Family.TD, state = WordMarkState.PLAYING)
      MarkedWordSpan(text = "did", family = Family.ASSIMILATION, state = WordMarkState.SELECTED)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.chipGap)) {
      ChangeChip("Flapping", Family.TD, onClick = {})
      PracticeBadge(Advice.PRODUCE)
    }
    MarksLegend()
  }
}

@PreviewLightDark
@Composable
private fun MarksCvdPatternsPreview() {
  PhonoPreviewSurface(colors = ColorChoice.COLOR_VISION_FRIENDLY, patterns = PatternChoice.FOLLOW_COLORS) {
    Row(horizontalArrangement = Arrangement.spacedBy(PhonoSpacing.s8)) {
      MarkedWordSpan(text = "does", family = Family.REDUCTION)
      MarkedWordSpan(text = "water", family = Family.TD)
      MarkedWordSpan(text = "pick", family = Family.BOUNDARY, linksToNext = true)
    }
    ChangeChip("Linking", Family.BOUNDARY, onClick = {})
    PracticeBadge(Advice.UNDERSTAND, register = "casual")
  }
}
