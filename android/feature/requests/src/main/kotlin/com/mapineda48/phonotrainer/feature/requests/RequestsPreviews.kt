package com.mapineda48.phonotrainer.feature.requests

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.feature.requests.add.AddClipActions
import com.mapineda48.phonotrainer.feature.requests.add.AddClipScreen
import com.mapineda48.phonotrainer.feature.requests.add.AddClipUiState

// Previews of every state of Requests (P-03), the request detail (P-04) and "Add a clip" (P-05), light and dark
// (architecture 23 item 7; design 12.5). The states are RequestsSamples', which the screen tests walk too.

internal class RequestsStates : PreviewParameterProvider<RequestsUiState> {
  override val values: Sequence<RequestsUiState> = RequestsSamples.requestsStates.map { it.second }.asSequence()
}

internal class DetailStates : PreviewParameterProvider<RequestDetailUiState> {
  override val values: Sequence<RequestDetailUiState> = RequestsSamples.detailStates.map { it.second }.asSequence()
}

internal class AddStates : PreviewParameterProvider<AddClipUiState> {
  override val values: Sequence<AddClipUiState> = RequestsSamples.addStates.map { it.second }.asSequence()
}

@PreviewLightDark
@Composable
private fun RequestsScreenPreview(@PreviewParameter(RequestsStates::class) state: RequestsUiState) {
  PhonoPreviewSurface { RequestsScreen(state, RequestsActions()) }
}

@PreviewLightDark
@Composable
private fun RequestDetailScreenPreview(@PreviewParameter(DetailStates::class) state: RequestDetailUiState) {
  PhonoPreviewSurface { RequestDetailScreen(state, DetailActions()) }
}

@PreviewLightDark
@Composable
private fun AddClipScreenPreview(@PreviewParameter(AddStates::class) state: AddClipUiState) {
  PhonoPreviewSurface { AddClipScreen(state, AddClipActions()) }
}
