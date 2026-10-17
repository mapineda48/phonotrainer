package com.mapineda48.phonotrainer.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.navigation.ClipTab

// Previews of every Library and Clip page state (work-plan 2.5 item 7), light and dark.

@Composable
private fun Library(state: LibraryUiState) = PhonoPreviewSurface { LibraryScreen(state, LibraryActions()) }

@Composable
private fun Clip(state: ClipUiState) = PhonoPreviewSurface { ClipScreen(state, ClipActions()) }

@PreviewLightDark
@Composable
private fun LibraryReadyPreview() = Library(LibrarySamples.ready)

@PreviewLightDark
@Composable
private fun LibraryLoadingPreview() = Library(LibraryUiState())

@PreviewLightDark
@Composable
private fun LibraryErrorPreview() = Library(LibraryUiState(catalogue = Catalogue.Failed))

@PreviewLightDark
@Composable
private fun LibraryFirstRunPreview() = Library(LibraryUiState(catalogue = Catalogue.Ready(emptyList())))

@PreviewLightDark
@Composable
private fun LibraryEmptyPreview() = Library(LibraryUiState(catalogue = Catalogue.Ready(emptyList()), paired = true))

@PreviewLightDark
@Composable
private fun LibraryOfflinePreview() = Library(
  LibrarySamples.ready.copy(offline = true, lastSync = LibrarySamples.now.minusSeconds(5_400), inProgress = emptyList()),
)

@PreviewLightDark
@Composable
private fun LibraryNoMatchPreview() = Library(LibrarySamples.ready.copy(search = "kitchen", clips = emptyList()))

@PreviewLightDark
@Composable
private fun LibraryFilteredPreview() = Library(
  LibrarySamples.ready.copy(
    filter = ClipFilter.ON_THIS_PHONE,
    clips = LibrarySamples.ready.clips.filter { LibraryRules.passes(it.clip, ClipFilter.ON_THIS_PHONE) },
    inProgress = emptyList(),
    startedOnComputer = 0,
  ),
)

@PreviewLightDark
@Composable
private fun ClipShortsPreview() = Clip(LibrarySamples.page)

@PreviewLightDark
@Composable
private fun ClipSummaryPreview() = Clip(LibrarySamples.page.copy(tab = ClipTab.SUMMARY))

@PreviewLightDark
@Composable
private fun ClipBringingPreview() = Clip(LibrarySamples.bringing)

@PreviewLightDark
@Composable
private fun ClipNotHereOfflinePreview() = Clip(LibrarySamples.notHereOffline)

@PreviewLightDark
@Composable
private fun ClipNoAudioPreview() = Clip(LibrarySamples.noAudio)

@PreviewLightDark
@Composable
private fun ClipProcessingPreview() = Clip(LibrarySamples.processing)

@PreviewLightDark
@Composable
private fun ClipDeletedPreview() = Clip(
  LibrarySamples.page.copy(presence = ClipPresence.Known(LibrarySamples.detail(), deleted = true)),
)

@PreviewLightDark
@Composable
private fun ClipGonePreview() = Clip(ClipUiState(presence = ClipPresence.Gone))

@PreviewLightDark
@Composable
private fun ClipShortsLoadingPreview() = Clip(LibrarySamples.page.copy(shorts = ShortsState.Loading))
