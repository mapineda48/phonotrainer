package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.Family

// Marks of a change (design 9.4): placeholder visuals. Colour is never the only cue: each family also has a line
// style, an icon and a name (spec A-10); WP-03 draws them.

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

/** The family's mark: line style + icon + name (placeholder: the name). */
@Composable
fun FamilyMark(family: Family, modifier: Modifier = Modifier) {
  Text(text = familyName(family), style = MaterialTheme.typography.labelLarge, modifier = modifier)
}

/** A word with its change's mark beneath it; [family] null draws the word plain. */
@Composable
fun MarkedWordSpan(text: String, family: Family?, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyLarge,
    textDecoration = if (family != null && family != Family.OTHER) TextDecoration.Underline else null,
    modifier = modifier,
  )
}

/** A change chip: its family's mark and the change's name; opens the phenomenon page when [onClick] is set. */
@Composable
fun ChangeChip(label: String, family: Family, onClick: () -> Unit, modifier: Modifier = Modifier) {
  AssistChip(
    onClick = onClick,
    label = { Text("$label · ${familyName(family)}") },
    modifier = modifier.heightIn(min = 48.dp).testTag(UiTags.CHANGE_CHIP),
  )
}

/** "Safe to produce" / "Recognize only". */
@Composable
fun PracticeBadge(advice: Advice, modifier: Modifier = Modifier) {
  Text(
    text = stringResource(if (advice == Advice.PRODUCE) R.string.ui_advice_produce else R.string.ui_advice_understand),
    style = MaterialTheme.typography.labelMedium,
    modifier = modifier,
  )
}

/** The marks legend of the More sheet and Settings > Help (design 9.12). */
@Composable
fun MarksLegend(modifier: Modifier = Modifier) {
  Row(modifier = modifier) { Text(stringResource(R.string.ui_marks_legend), style = MaterialTheme.typography.bodyMedium) }
}

@Preview
@Composable
private fun MarksPreview() {
  PhonoTheme {
    Row {
      FamilyMark(Family.TD)
      MarkedWordSpan(text = "water", family = Family.TD)
      PracticeBadge(Advice.PRODUCE)
    }
  }
}
