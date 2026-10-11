package com.mapineda48.phonotrainer.core.designsystem.screenshot

import com.mapineda48.phonotrainer.core.designsystem.brand.PhonoMarkPreview
import com.mapineda48.phonotrainer.core.designsystem.component.BarsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.ButtonsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.CardsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.ChipsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.IconButtonsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.IllustrationPreview
import com.mapineda48.phonotrainer.core.designsystem.component.NoticePreview
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoTextPreview
import com.mapineda48.phonotrainer.core.designsystem.component.ProgressBarPreview
import com.mapineda48.phonotrainer.core.designsystem.component.SelectionPreview
import com.mapineda48.phonotrainer.core.designsystem.component.SheetsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.StepsPreview
import com.mapineda48.phonotrainer.core.designsystem.component.TextFieldPreview
import com.mapineda48.phonotrainer.core.designsystem.component.TypeSortPreview
import com.mapineda48.phonotrainer.core.designsystem.texture.TexturesPreview
import com.mapineda48.phonotrainer.core.designsystem.trace.StylusTracePreview

/** Every component preview of `:core:designsystem` (design 12.1), in the order of the sign-off sheets. */
internal val DesignSystemCatalog: List<CatalogPreview> = listOf(
  CatalogPreview("ButtonsPreview") { ButtonsPreview() },
  CatalogPreview("IconButtonsPreview") { IconButtonsPreview() },
  CatalogPreview("SelectionPreview") { SelectionPreview() },
  CatalogPreview("TextFieldPreview") { TextFieldPreview() },
  CatalogPreview("ChipsPreview") { ChipsPreview() },
  CatalogPreview("NoticePreview") { NoticePreview() },
  CatalogPreview("ProgressBarPreview") { ProgressBarPreview() },
  CatalogPreview("CardsPreview") { CardsPreview() },
  CatalogPreview("BarsPreview") { BarsPreview() },
  CatalogPreview("SheetsPreview") { SheetsPreview() },
  CatalogPreview("StepsPreview") { StepsPreview() },
  CatalogPreview("TypeSortPreview") { TypeSortPreview() },
  CatalogPreview("PhonoTextPreview") { PhonoTextPreview() },
  CatalogPreview("IllustrationPreview") { IllustrationPreview() },
  CatalogPreview("TexturesPreview") { TexturesPreview() },
  CatalogPreview("StylusTracePreview") { StylusTracePreview() },
  CatalogPreview("PhonoMarkPreview") { PhonoMarkPreview() },
)
