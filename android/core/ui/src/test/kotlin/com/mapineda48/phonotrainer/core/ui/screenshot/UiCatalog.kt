package com.mapineda48.phonotrainer.core.ui.screenshot

import com.mapineda48.phonotrainer.core.designsystem.screenshot.CatalogPreview
import com.mapineda48.phonotrainer.core.ui.CoachMarkHostPreview
import com.mapineda48.phonotrainer.core.ui.ControlsPreview
import com.mapineda48.phonotrainer.core.ui.DestinationStubPreview
import com.mapineda48.phonotrainer.core.ui.EmptyStatePreview
import com.mapineda48.phonotrainer.core.ui.ExplainPreview
import com.mapineda48.phonotrainer.core.ui.IpaTextPreview
import com.mapineda48.phonotrainer.core.ui.LoadingGatePreview
import com.mapineda48.phonotrainer.core.ui.MarksCvdPatternsPreview
import com.mapineda48.phonotrainer.core.ui.MarksPreview
import com.mapineda48.phonotrainer.core.ui.MediaPreview
import com.mapineda48.phonotrainer.core.ui.StatusPreview

/**
 * Every preview of the `:core:ui` primitives (design 12.2), in the order of the sign-off sheets. The shared components
 * of `ui/component/` (work-plan 4.5) and the extension points of `ui/extension/` have other owners (work-plan 3.2),
 * whose screenshot tests cover them.
 */
internal val UiCatalog: List<CatalogPreview> = listOf(
  CatalogPreview("ControlsPreview") { ControlsPreview() },
  CatalogPreview("MarksPreview") { MarksPreview() },
  CatalogPreview("MarksCvdPatternsPreview") { MarksCvdPatternsPreview() },
  CatalogPreview("IpaTextPreview") { IpaTextPreview() },
  CatalogPreview("StatusPreview") { StatusPreview() },
  CatalogPreview("ExplainPreview") { ExplainPreview() },
  CatalogPreview("CoachMarkHostPreview") { CoachMarkHostPreview() },
  CatalogPreview("MediaPreview") { MediaPreview() },
  CatalogPreview("LoadingGatePreview") { LoadingGatePreview() },
  CatalogPreview("EmptyStatePreview") { EmptyStatePreview() },
  CatalogPreview("DestinationStubPreview", screen = true) { DestinationStubPreview() },
)

/** The parts of `src/main/kotlin` whose previews belong to other work packages. */
internal val OtherOwners: List<String> = listOf("/ui/component/", "/ui/extension/")
