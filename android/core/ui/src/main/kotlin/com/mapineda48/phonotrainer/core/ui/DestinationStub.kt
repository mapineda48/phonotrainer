package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.DoubleRule
import com.mapineda48.phonotrainer.core.designsystem.component.Notice
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.ui.a11y.paneTitle

/**
 * The stub of a destination at the contract freeze (work-plan 2.3): its title and its one-sentence lede (spec A-16),
 * so every destination is navigable before its feature lands. Each feature replaces its own stubs. The column
 * scrolls, so [content] must not hold a lazy list.
 */
@Composable
fun DestinationStub(
  title: String,
  lede: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  val colors = PhonoTheme.colors
  Column(
    modifier = modifier.fillMaxSize().paneTitle(title).verticalScroll(rememberScrollState())
      .padding(horizontal = PhonoSpacing.gutter, vertical = PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s12),
  ) {
    PhonoText(
      text = title,
      style = MaterialTheme.typography.headlineLarge,
      color = colors.ink,
      modifier = Modifier.testTag(UiTags.STUB_TITLE).semantics { heading() },
    )
    DoubleRule()
    PhonoText(
      text = lede,
      style = MaterialTheme.typography.bodyLarge,
      color = colors.ink2,
      modifier = Modifier.testTag(UiTags.STUB_LEDE),
    )
    Notice(stringResource(R.string.ui_stub_note))
    content()
  }
}

@PreviewLightDark
@Composable
internal fun DestinationStubPreview() {
  // A destination draws on the page the shell paints behind every destination.
  PhonoTheme {
    Box(Modifier.background(PhonoTheme.colors.page)) {
      DestinationStub(title = "Library", lede = "Every clip on this phone and on your computer.")
    }
  }
}
