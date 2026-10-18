package com.mapineda48.phonotrainer.feature.settings

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Open-source licences (spec F-24-AC1, AC2; architecture 20.6) over the APK's own assets: the rendered list equals the
 * report (a sample in the report's shape; the managed-device test of `e2e/settings` compares the build's real report)
 * and `bundled.json` (the three font families and the Lucide set), every row says its version and licence, and every
 * licence text is read from the APK, so it is readable with no network at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class LicencesScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val apk = AndroidLicenceSource(context)
  private val report: String = requireNotNull(javaClass.classLoader?.getResource("report-sample.json")).readText()

  /** The APK's assets, with the sample report in place of the application module's generated one. */
  private val source = object : LicenceSource by apk {
    override suspend fun report(): String = report
  }

  private val version = object : AppVersion {
    override val name: String = "1.0.0"
  }

  private val calls = mutableListOf<String>()
  private val actions = LicencesActions(onBack = { calls += "back" }, onOpen = { calls += "open:${it.id}" }, onClose = { calls += "close" })

  private fun catalogue(): LicenceCatalogue = runBlocking { loadCatalogue(source, version) }

  @Test
  fun theListIsTheAppTheBundledFontsAndIconsAndEveryLibraryOfTheReport() {
    val catalogue = catalogue()
    // The sources, read on their own: bundled.json from :core:designsystem's tree, the report as given.
    val bundled = Licences.bundled(File("../../core/designsystem/src/main/assets/licenses/bundled.json").readText())
    assertThat(catalogue.bundled.map { it.name }).containsExactlyElementsIn(bundled.map { it.name }).inOrder()
    assertThat(catalogue.bundled.map { it.name }).containsExactly("Atkinson Hyperlegible Next", "Newsreader", "Charis", "Lucide").inOrder()
    assertThat(catalogue.libraries.map { it.id }).containsExactlyElementsIn(Licences.libraries(report).map { it.id })

    screen.setContent { LicencesScreen(LicencesUiState(LicencesRead.Ready(catalogue)), actions) }
    catalogue.all.forEach { entry ->
      val tag = SettingsTags.licence(entry.id)
      compose.onNodeWithTag(SettingsTags.LICENCES_LIST).performScrollToNode(hasTestTag(tag))
      val row = compose.textsOf(tag)
      assertWithMessage(entry.id).that(row).contains(entry.name)
      assertWithMessage(entry.id).that(row).contains(entry.licenceLine)
      entry.version?.let { assertWithMessage(entry.id).that(row).contains(it) }
    }
    assertThat(compose.textsOf(SettingsTags.licence("bundled:Lucide"))).contains("Icon set · 122 icons · 1.51.0 · ISC AND MIT")
    assertThat(compose.textsOf(SettingsTags.licence("bundled:Newsreader"))).contains("Font · 2 font files · 1.003 · OFL-1.1")
    assertThat(compose.textsOf(SettingsTags.licence("app"))).contains("PhonoTrainer")
    assertThat(compose.textsOf(SettingsTags.licence("app"))).contains("1.0.0 · GPL-3.0-or-later")
  }

  @Test
  fun everyLicenceTextIsReadFromTheApk() {
    val catalogue = catalogue()
    val reader = LicenceReader(source)
    catalogue.all.forEach { entry ->
      val sections = runBlocking { reader.sections(entry) }
      assertWithMessage(entry.id).that(sections).isNotEmpty()
      sections.forEach { section ->
        assertWithMessage("${entry.id}: ${section.licence.id}").that(section.paragraphs).isNotNull()
        // A bundled licence file is a whole licence; a text the report carries is the holder's own, of any length.
        if (section.licence.text == null) {
          assertWithMessage("${entry.id}: ${section.licence.id}").that(section.paragraphs!!.joinToString(" ").length).isGreaterThan(400)
        }
      }
    }
    // The app's text is the GPL; the fonts' are the OFL with each family's own copyright line.
    val gpl = runBlocking { reader.sections(catalogue.app) }.single().paragraphs!!
    assertThat(gpl.first()).isEqualTo("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007")
    val charis = runBlocking { reader.sections(catalogue.bundled.single { it.name == "Charis" }) }.single().paragraphs!!
    assertThat(charis.joinToString(" ")).contains("SIL OPEN FONT LICENSE")
  }

  @Test
  fun theListReadsAtLargeTextInLightAndDarkAndARowOpensItsLicence() {
    val catalogue = catalogue()
    screen.checkEach(ScreenVariant.REQUIRED, { LicencesScreen(LicencesUiState(LicencesRead.Ready(catalogue)), actions) }) {
      assertThat(compose.textsOf(SettingsTags.LICENCES_LIST)).contains("FONTS AND ICONS")
      assertThat(compose.textsOf(SettingsTags.LICENCES_LIST)).contains("LIBRARIES")
    }
    compose.onNodeWithTag(SettingsTags.licence("bundled:Charis")).performClick()
    assertThat(calls).containsExactly("open:bundled:Charis")
  }

  @Test
  fun anOpenLicenceShowsWhoHoldsItItsTextAndTheTemplateNote() {
    val catalogue = catalogue()
    val protobuf = catalogue.libraries.single { it.licenceLine == "BSD-3-Clause" }
    val sections = runBlocking { LicenceReader(source).sections(protobuf) }
    // The sheet's content on its own: the shared sheet's drag handle has no label (WP-03's, known; see WatchAudit).
    screen.checkEach(ScreenVariant.REQUIRED, { LicenceText(LicenceTextUi(protobuf, sections), onClose = actions.onClose) }) {
      val text = compose.textsOf(SettingsTags.LICENCE_TEXT)
      assertThat(text).contains("Preferences External Protobuf 1.2.1")
      assertThat(text).contains("By The Android Open Source Project")
      assertThat(text).contains("Licence: BSD-3-Clause")
      assertThat(text).contains("The licence's standard text: its copyright line stands for the holder named above.")
      assertThat(text).contains("Redistribution and use in source and binary forms")
    }
    compose.onNodeWithTag(SettingsTags.LICENCE_CLOSE).performClick()
    assertThat(calls).containsExactly("close")
  }

  @Test
  fun aTextNotBundledSaysWhereTheLicenceIsPublished() {
    val entry = SettingsSamples.catalogue.libraries.first()
    val missing = LicenceTextUi(entry, listOf(LicenceSection(entry.licences.single(), null)))
    screen.setContent { LicenceText(missing, onClose = {}) }
    assertThat(compose.textsOf(SettingsTags.LICENCE_TEXT))
      .contains("This licence's text is not bundled. It is published at https://www.apache.org/licenses/LICENSE-2.0.txt.")
  }

  @Test
  fun loadingAndAnUnreadableCatalogueAreStatesOfTheirOwn() {
    screen.checkEach(ScreenVariant.REQUIRED, { LicencesScreen(LicencesUiState(), actions) }) {
      compose.onNodeWithTag(SettingsTags.LICENCES_LOADING).assertExists()
    }
  }

  @Test
  fun anUnreadableCatalogueOffersToTryAgain() {
    var retried = 0
    screen.checkEach(ScreenVariant.REQUIRED, { LicencesScreen(LicencesUiState(LicencesRead.Failed), LicencesActions(onRetry = { retried++ })) }) {
      assertThat(compose.textsOf(SettingsTags.LICENCES_ERROR)).contains("Couldn't read the licences on this phone.")
    }
    compose.onNode(hasText("Try again") and hasClickAction()).performClick()
    assertThat(retried).isEqualTo(1)
  }
}
