package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.LinkButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoIconButton
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSizes
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget

/**
 * The "What's this?" control after a term (F-14, design 9.13): a 20 dp circle-help icon in a 48 dp target; TalkBack
 * reads "What's this: {term}".
 */
@Composable
fun ExplainButton(term: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  PhonoIconButton(
    icon = PhonoIcons.Help,
    contentDescription = stringResource(R.string.ui_explain_label, term),
    onClick = onClick,
    iconSize = PhonoSizes.iconButton,
    modifier = modifier.testTag(UiTags.EXPLAIN_BUTTON),
  )
}

/**
 * The "What's this?" sheet (F-14, design 9.13): the title, one to three sentences (IPA set in Charis), the example in
 * Charis, and "Learn more" with its arrow icon. Closing returns the focus to the control (the sheet's own behaviour).
 */
@Composable
fun ExplainSheet(entry: GlossaryEntry, onDismiss: () -> Unit, onLearnMore: (LinkTarget) -> Unit) {
  PhonoSheet(onDismiss = onDismiss, title = entry.title) {
    ExplainContent(entry, onDismiss, onLearnMore)
  }
}

/** The sheet's content, also drawn on its own by previews and screen tests. */
@Composable
fun ExplainContent(entry: GlossaryEntry, onDismiss: () -> Unit, onLearnMore: (LinkTarget) -> Unit) {
  val colors = PhonoTheme.colors
  Column(
    modifier = Modifier.padding(bottom = PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    PhonoText(
      entry.title,
      style = MaterialTheme.typography.headlineLarge,
      color = colors.ink,
      modifier = Modifier.semantics { heading() },
    )
    IpaText(entry.body, style = MaterialTheme.typography.bodyLarge)
    entry.example?.let { IpaText(it, style = MaterialTheme.typography.bodyLarge, color = colors.ink2) }
    entry.learnMore?.let { target ->
      LinkButton(
        text = entry.learnMoreLabel ?: stringResource(R.string.ui_explain_learn_more),
        onClick = { onLearnMore(target) },
        trailingArrow = true,
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

@PreviewLightDark
@Composable
private fun ExplainPreview() {
  PhonoPreviewSurface {
    ExplainButton(term = "schwa", onClick = {})
    ExplainContent(
      GlossaryEntry(
        key = "schwa",
        title = "Schwa",
        body = "The weak vowel /ə/: short, relaxed, in the middle of the mouth.",
        example = "about → /əˈbaʊt/",
        learnMore = LinkTarget.Learn,
        learnMoreLabel = null,
      ),
      onDismiss = {},
      onLearnMore = {},
    )
  }
}
