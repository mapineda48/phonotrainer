package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.GlossaryIndexKey
import com.mapineda48.phonotrainer.core.navigation.InsightsKey
import com.mapineda48.phonotrainer.core.navigation.IpaChartKey
import com.mapineda48.phonotrainer.core.navigation.PhenomenonKey
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind

// The stub destinations of :feature:learn at the contract freeze (work-plan 2.3): title, lede and the
// destinations they lead to. WP-20 replaces them with its screens.

@Composable
internal fun LearnStubScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.learn_title),
    lede = stringResource(R.string.learn_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.learn_link_ipa),
      onClick = { onNavigate(IpaChartKey()) },
      modifier = Modifier.testTag(LearnTags.LINK_IPA),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.learn_link_glossary),
      onClick = { onNavigate(GlossaryIndexKey) },
      modifier = Modifier.testTag(LearnTags.LINK_GLOSSARY),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.learn_link_insights),
      onClick = { onNavigate(InsightsKey) },
      modifier = Modifier.testTag(LearnTags.LINK_INSIGHTS),
      kind = PhonoButtonKind.SECONDARY,
    )
    PhonoButton(
      text = stringResource(R.string.learn_link_flapping),
      onClick = { onNavigate(PhenomenonKey(ChangeLabel("flapping"))) },
      modifier = Modifier.testTag(LearnTags.LINK_FLAPPING),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun LearnStubScreenPreview() {
  PhonoTheme { LearnStubScreen(onNavigate = {}) }
}

@Composable
internal fun PhenomenonStubScreen(key: PhenomenonKey, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.learn_phenomenon_title),
    lede = stringResource(R.string.learn_phenomenon_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.learn_phenomenon_link_examples),
      onClick = { onNavigate(WatchKey(FeedStart.ChangeMode(key.label, onlyClip = null))) },
      modifier = Modifier.testTag(LearnTags.PHENOMENON_EXAMPLES),
      kind = PhonoButtonKind.SECONDARY,
    )
  }
}

@Preview
@Composable
private fun PhenomenonStubScreenPreview() {
  PhonoTheme { PhenomenonStubScreen(PhenomenonKey(ChangeLabel("flapping")), onNavigate = {}) }
}

@Composable
internal fun IpaChartStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.learn_ipa_title),
    lede = stringResource(R.string.learn_ipa_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun IpaChartStubScreenPreview() {
  PhonoTheme { IpaChartStubScreen() }
}

@Composable
internal fun GlossaryIndexStubScreen(modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.learn_glossary_title),
    lede = stringResource(R.string.learn_glossary_lede),
    modifier = modifier,
  )
}

@Preview
@Composable
private fun GlossaryIndexStubScreenPreview() {
  PhonoTheme { GlossaryIndexStubScreen() }
}
