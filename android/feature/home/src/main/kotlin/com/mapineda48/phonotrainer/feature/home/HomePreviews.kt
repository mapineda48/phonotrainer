package com.mapineda48.phonotrainer.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface

// Previews of every Home state (work-plan 2.5 item 7), light and dark.

@Composable
private fun Home(state: HomeUiState) = PhonoPreviewSurface { HomeScreen(state, HomeActions()) }

@PreviewLightDark
@Composable
private fun HomePopulatedPreview() = Home(HomeSamples.populated)

@PreviewLightDark
@Composable
private fun HomeFirstRunPreview() = Home(HomeSamples.firstRun)

@PreviewLightDark
@Composable
private fun HomeEmptyPreview() = Home(HomeSamples.empty)

@PreviewLightDark
@Composable
private fun HomeLoadingPreview() = Home(HomeSamples.loading)

@PreviewLightDark
@Composable
private fun HomeUnreachablePreview() = Home(HomeSamples.unreachable)

@PreviewLightDark
@Composable
private fun HomeErrorPreview() = Home(HomeSamples.error)

@PreviewLightDark
@Composable
private fun HomeLimitsPreview() = Home(HomeSamples.limits)

@PreviewLightDark
@Composable
private fun HomeMidSetPreview() = Home(HomeSamples.populated.copy(today = HomeSamples.midSet))

@PreviewLightDark
@Composable
private fun HomeDoneForTodayPreview() = Home(HomeSamples.populated.copy(today = HomeSamples.finished))

@PreviewLightDark
@Composable
private fun HomeShortSetPreview() = Home(HomeSamples.populated.copy(today = HomeSamples.shortSet))

@PreviewLightDark
@Composable
private fun HomeNothingNewPreview() = Home(HomeSamples.populated.copy(today = TodayCard.NothingNew, keepWatching = null))

@PreviewLightDark
@Composable
private fun HomeNotReachableNoticePreview() = Home(HomeSamples.unreachable.copy(notice = HomeNotice.NOT_REACHABLE))

@PreviewLightDark
@Composable
private fun HomeInsightsPreview() =
  Home(HomeSamples.populated.copy(insights = "About 7 in 10 words you hear don't sound the way the dictionary writes them."))
