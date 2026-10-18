package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

// Previews of every Practice and Saved state (definition of done 2.5 item 7; design 10.1 T4, 10.2 P-11, renders 14
// and 24). Device: the design's 412 × 915 dp.

private const val DEVICE = "spec:width=412dp,height=915dp"

@Composable
private fun Practice(state: PracticeUiState) {
  PhonoTheme {
    Box(Modifier.background(PhonoTheme.colors.page)) { PracticeScreen(state, PracticeActions()) }
  }
}

@Composable
private fun Saved(state: SavedUiState) {
  PhonoTheme {
    Box(Modifier.background(PhonoTheme.colors.page)) { SavedScreen(state, SavedActions()) }
  }
}

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun StartPreview() = Practice(PracticePreviewData.startState)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun StartWithFocusPreview() = Practice(PracticePreviewData.focusState)

@Preview(device = DEVICE)
@Composable
private fun StartWithResumePreview() =
  Practice(PracticePreviewData.startState.copy(start = PracticePreviewData.start.copy(resume = ResumeUi(4, 10))))

@Preview(device = DEVICE)
@Composable
private fun FocusWithoutExamplesPreview() = Practice(
  PracticePreviewData.startState.copy(start = StartUi(focus = FocusUi.Known(PracticePreviewData.hDropping, playable = false))),
)

@Preview(device = DEVICE)
@Composable
private fun UnknownFocusPreview() = Practice(PracticePreviewData.startState.copy(start = StartUi(focus = FocusUi.Unknown("flap"))))

@Preview(device = DEVICE)
@Composable
private fun NoAccuracyYetPreview() =
  Practice(PracticePreviewData.startState.copy(progress = PracticePreviewData.progress.copy(rows = emptyList())))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun NothingToPracticePreview() = Practice(PracticePreviewData.firstRunState)

@Preview(device = DEVICE)
@Composable
private fun LoadingPreview() = Practice(PracticeUiState(page = PracticePage.LOADING))

@Preview(device = DEVICE)
@Composable
private fun BuildingPreview() = Practice(PracticeUiState(page = PracticePage.BUILDING))

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun QuestionPreview() = Practice(PracticePreviewData.sessionState())

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun WrongAnswerPreview() = Practice(PracticePreviewData.sessionState(PracticePreviewData.session(chosen = "dictionary")))

@Preview(device = DEVICE)
@Composable
private fun RightChangeAnswerPreview() = Practice(
  PracticePreviewData.sessionState(PracticePreviewData.session(PracticePreviewData.changeQuestion, chosen = "flapping")),
)

@Preview(device = DEVICE)
@Composable
private fun CountAnswerOnTheLastQuestionPreview() = Practice(
  PracticePreviewData.sessionState(PracticePreviewData.session(PracticePreviewData.countQuestion, chosen = "4", index = 9)),
)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun ResultsPreview() = Practice(PracticePreviewData.resultsState)

@Preview(device = DEVICE)
@Composable
private fun PartialResultsPreview() = Practice(PracticePreviewData.partialResultsState)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun SavedPreview() = Saved(PracticePreviewData.savedState)

@PreviewLightDark
@Preview(device = DEVICE)
@Composable
private fun SavedEmptyPreview() = Saved(SavedUiState(loading = false))

@Preview(device = DEVICE)
@Composable
private fun SavedLoadingPreview() = Saved(SavedUiState())
