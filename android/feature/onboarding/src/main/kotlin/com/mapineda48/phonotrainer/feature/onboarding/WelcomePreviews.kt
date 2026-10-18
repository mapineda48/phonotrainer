package com.mapineda48.phonotrainer.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface

// Previews of every state of the Welcome (work-plan 2.5 item 7), light and dark.

@PreviewLightDark
@Composable
private fun WelcomeListenPreview() {
  PhonoPreviewSurface { WelcomeScreen(WelcomePage.LISTEN, restart = false, busy = false, actions = WelcomeActions()) }
}

@PreviewLightDark
@Composable
private fun WelcomeClipsPreview() {
  PhonoPreviewSurface { WelcomeScreen(WelcomePage.CLIPS, restart = false, busy = false, actions = WelcomeActions()) }
}

@PreviewLightDark
@Composable
private fun WelcomeDailyPreview() {
  PhonoPreviewSurface { WelcomeScreen(WelcomePage.DAILY, restart = false, busy = false, actions = WelcomeActions()) }
}

@PreviewLightDark
@Composable
private fun WelcomeReplayedPreview() {
  PhonoPreviewSurface { WelcomeScreen(WelcomePage.DAILY, restart = true, busy = false, actions = WelcomeActions()) }
}

@PreviewLightDark
@Composable
private fun WelcomeFinishingPreview() {
  PhonoPreviewSurface { WelcomeScreen(WelcomePage.DAILY, restart = false, busy = true, actions = WelcomeActions()) }
}
