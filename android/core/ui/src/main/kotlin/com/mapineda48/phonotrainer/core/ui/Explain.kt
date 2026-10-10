package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget

/** The "What's this?" control next to a term (F-14, design 9.13); its label names the term. */
@Composable
fun ExplainButton(term: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val label = stringResource(R.string.ui_explain_label, term)
  TextButton(
    onClick = onClick,
    modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(UiTags.EXPLAIN_BUTTON)
      .semantics { contentDescription = label },
  ) { Text(stringResource(R.string.ui_explain_symbol)) }
}

/** The "What's this?" sheet: the glossary entry, its example and "Learn more" (F-14). */
@Composable
fun ExplainSheet(entry: GlossaryEntry, onDismiss: () -> Unit, onLearnMore: (LinkTarget) -> Unit) {
  PhonoSheet(onDismiss = onDismiss) {
    Text(entry.title, style = MaterialTheme.typography.headlineSmall)
    IpaText(entry.body)
    entry.example?.let { IpaText(it) }
    entry.learnMore?.let { target ->
      PhonoButton(
        text = entry.learnMoreLabel ?: stringResource(R.string.ui_explain_learn_more),
        onClick = { onLearnMore(target) },
        kind = PhonoButtonKind.SECONDARY,
        modifier = Modifier.testTag(UiTags.EXPLAIN_LEARN_MORE),
      )
    }
    PhonoButton(
      text = stringResource(R.string.ui_explain_close),
      onClick = onDismiss,
      kind = PhonoButtonKind.QUIET,
      modifier = Modifier.testTag(UiTags.EXPLAIN_CLOSE),
    )
  }
}

@Preview
@Composable
private fun ExplainButtonPreview() {
  PhonoTheme { ExplainButton(term = "schwa", onClick = {}) }
}
