package com.mapineda48.phonotrainer.feature.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation

// Previews of every lesson state (definition of done 2.5 item 7; design 9.9 to 9.11 and renders 10 and 11). Device:
// the design's 412 × 915 dp; a sheet preview shows the sheet's content on its paper.

private const val DEVICE = "spec:width=412dp,height=915dp"

@Composable
private fun Lesson(state: LessonUiState, presentation: LessonPresentation = LessonPresentation.SHEET, cvd: Boolean = false) {
  PhonoTheme(colors = if (cvd) ColorChoice.COLOR_VISION_FRIENDLY else ColorChoice.STANDARD) {
    Box(Modifier.background(PhonoTheme.colors.sheet)) {
      LessonScreen(
        state = state,
        presentation = presentation,
        actions = LessonActions(),
        glossary = LessonPreviewData.glossary::get,
      )
    }
  }
}

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun CompareSheetPreview() = Lesson(LessonPreviewData.water())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun BoundaryFullPreview() = Lesson(LessonPreviewData.didYou(), LessonPresentation.FULL)

@Preview(device = DEVICE)
@Composable
private fun BoundaryColorVisionPreview() = Lesson(LessonPreviewData.didYou(), LessonPresentation.FULL, cvd = true)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun CompactGuidancePreview() = Lesson(LessonPreviewData.water(open = emptySet(), selected = false))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun LowConfidencePreview() = Lesson(LessonPreviewData.lowConfidence())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun NothingHeardPreview() = Lesson(LessonPreviewData.nothingHeard())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun NoDictionaryFormPreview() = Lesson(LessonPreviewData.noDictionaryForm())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun NoAudioPreview() = Lesson(LessonPreviewData.noAudio())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun EspeakPreview() = Lesson(LessonPreviewData.espeak())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun ContractionPreview() = Lesson(LessonPreviewData.contraction())

@Preview(device = DEVICE, fontScale = 2f)
@Composable
private fun LargeTextPreview() = Lesson(LessonPreviewData.water())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun LoadingPreview() = Lesson(LessonUiState.Loading)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun ClipGonePreview() = Lesson(LessonUiState.Unavailable(Unavailable.CLIP_GONE))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun ClipDamagedPreview() = Lesson(LessonUiState.Unavailable(Unavailable.CLIP_DAMAGED), LessonPresentation.FULL)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun WordGonePreview() = Lesson(LessonUiState.Unavailable(Unavailable.WORD_GONE))
