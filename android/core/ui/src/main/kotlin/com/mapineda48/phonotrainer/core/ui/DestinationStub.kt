package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
  Column(
    modifier = modifier.fillMaxSize().paneTitle(title).verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineLarge,
      modifier = Modifier.testTag(UiTags.STUB_TITLE).semantics { heading() },
    )
    Text(text = lede, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.STUB_LEDE))
    Text(
      text = stringResource(R.string.ui_stub_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    content()
  }
}

@Preview
@Composable
private fun DestinationStubPreview() {
  PhonoTheme { DestinationStub(title = "Library", lede = "Every clip on this phone and on your computer.") }
}
