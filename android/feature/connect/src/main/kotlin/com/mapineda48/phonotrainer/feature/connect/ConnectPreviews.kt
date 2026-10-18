package com.mapineda48.phonotrainer.feature.connect

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface

// Previews of every state of Connect and Connection details (work-plan 2.5 item 7), light and dark.

@PreviewLightDark
@Composable
private fun ConnectSearchingPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.firstRun, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectFoundPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.found, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectConflictPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.conflict, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectNothingFoundPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.nothingFound, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectNotOnWifiPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.notOnWifi, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectPhoneAccessOffPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.phoneAccessOff, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectTypedPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.typed, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectWrongCodePreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.codeError(CodeError.Invalid), ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectRateLimitedPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.codeError(CodeError.RateLimited(30)), ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectAgainPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.connectAgain, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectNewAddressPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.newAddress, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectSuccessPreview() {
  PhonoPreviewSurface { ConnectScreen(ConnectSamples.paired, ConnectActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectionDetailsConnectedPreview() {
  PhonoPreviewSurface { ConnectionDetailsScreen(ConnectSamples.detailsConnected, ConnectSamples.platform, DetailsActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectionDetailsPhoneAccessOffPreview() {
  PhonoPreviewSurface { ConnectionDetailsScreen(ConnectSamples.detailsPhoneAccessOff, ConnectSamples.platform, DetailsActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectionDetailsNoAnswerPreview() {
  PhonoPreviewSurface { ConnectionDetailsScreen(ConnectSamples.detailsNoAnswer, ConnectSamples.platform, DetailsActions()) }
}

@PreviewLightDark
@Composable
private fun ConnectionDetailsNotConnectedPreview() {
  PhonoPreviewSurface { ConnectionDetailsScreen(ConnectSamples.detailsNotConnected, ConnectSamples.platform, DetailsActions()) }
}
