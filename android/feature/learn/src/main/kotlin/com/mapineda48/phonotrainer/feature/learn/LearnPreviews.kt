package com.mapineda48.phonotrainer.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice

// Previews of every Learn state (definition of done 2.5 item 7; design 9.25, 10.1 and renders 12 and 13), on the
// design's 412 × 915 dp phone; the IPA chart also at the largest text on a 360 dp phone (F-16-AC1).

private const val DEVICE = "spec:width=412dp,height=915dp"
private const val SMALL = "spec:width=360dp,height=780dp"

@Composable
private fun Page(cvd: Boolean = false, content: @Composable () -> Unit) {
  PhonoTheme(colors = if (cvd) ColorChoice.COLOR_VISION_FRIENDLY else ColorChoice.STANDARD) {
    Box(Modifier.background(PhonoTheme.colors.page)) { content() }
  }
}

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun LearnPreview() = Page { LearnScreen(LearnPreviewData.learn(), LearnActions()) }

@Preview(device = DEVICE)
@Composable
private fun LearnColorVisionPreview() = Page(cvd = true) { LearnScreen(LearnPreviewData.learn(), LearnActions()) }

@Preview(device = DEVICE)
@Composable
private fun LearnCountsLoadingPreview() = Page { LearnScreen(LearnPreviewData.learn(counts = CountsUi.Loading), LearnActions()) }

@Preview(device = DEVICE)
@Composable
private fun LearnPartialWithInsightsPreview() = Page {
  LearnScreen(LearnPreviewData.learn(partial = true, insights = InsightsEntryUi("Your clips: 58 % of words differ")), LearnActions())
}

@Preview(device = DEVICE)
@Composable
private fun LearnCountsFailedPreview() = Page { LearnScreen(LearnPreviewData.learn(counts = CountsUi.Failed), LearnActions()) }

@Preview(device = DEVICE, fontScale = 2f)
@Composable
private fun LearnLargeTextPreview() = Page { LearnScreen(LearnPreviewData.learn(), LearnActions()) }

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun PhenomenonPreview() = Page {
  PhenomenonScreen(LearnPreviewData.phenomenon(), PhenomenonActions(), glossary = LearnPreviewData.glossary::get)
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonPlayingPartialPreview() = Page {
  PhenomenonScreen(
    LearnPreviewData.phenomenon(partial = true, examples = LearnPreviewData.examples(playing = 0)),
    PhenomenonActions(),
    glossary = LearnPreviewData.glossary::get,
  )
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonNoExamplesPreview() = Page {
  PhenomenonScreen(
    LearnPreviewData.phenomenon(counts = CountsUi.Ready(emptyMap()), examples = ExamplesUi.Ready(emptyList())),
    PhenomenonActions(),
  )
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonLoadingExamplesPreview() = Page {
  PhenomenonScreen(LearnPreviewData.phenomenon(counts = CountsUi.Loading, examples = ExamplesUi.Loading), PhenomenonActions())
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonIndexUnreadablePreview() = Page {
  PhenomenonScreen(LearnPreviewData.phenomenon(counts = CountsUi.Failed, examples = ExamplesUi.Failed), PhenomenonActions())
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonWithoutLessonPreview() = Page {
  PhenomenonScreen(LearnPreviewData.phenomenon(lesson = null, demo = null), PhenomenonActions())
}

@Preview(device = DEVICE)
@Composable
private fun PhenomenonUnknownPreview() = Page { PhenomenonScreen(PhenomenonUiState.Unknown, PhenomenonActions()) }

@Preview(device = DEVICE)
@Composable
private fun PhenomenonLoadingPreview() = Page { PhenomenonScreen(PhenomenonUiState.Loading, PhenomenonActions()) }

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun IpaChartPreview() = Page { IpaChartScreen(LearnPreviewData.ipa(), IpaChartActions()) }

@Preview(device = SMALL, fontScale = 2f)
@Composable
private fun IpaChartLargeTextPreview() = Page { IpaChartScreen(LearnPreviewData.ipa(), IpaChartActions()) }

@Preview(device = DEVICE)
@Composable
private fun IpaChartSelectedPreview() = Page {
  IpaChartScreen(LearnPreviewData.ipa(selected = "ɾ", detail = LearnPreviewData.tapDetail), IpaChartActions())
}

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun GlossaryPreview() = Page {
  GlossaryIndexScreen(LearnPreviewData.glossaryState(), GlossaryActions(), entry = LearnPreviewData.glossary::get)
}

@Preview(device = DEVICE)
@Composable
private fun GlossarySearchPreview() = Page { GlossaryIndexScreen(LearnPreviewData.glossaryState("schwa"), GlossaryActions()) }

@Preview(device = DEVICE)
@Composable
private fun GlossaryNoMatchPreview() = Page { GlossaryIndexScreen(LearnPreviewData.glossaryState("xyzzy"), GlossaryActions()) }
