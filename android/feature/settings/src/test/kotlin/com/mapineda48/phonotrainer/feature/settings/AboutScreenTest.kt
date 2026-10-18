package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * About PhonoTrainer (spec F-24, F-22's privacy statement): the version, the computer's API version last seen, the
 * licence line, the source location, the privacy statement and the way to the open-source licences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class AboutScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = AboutActions(
    onBack = { calls += "back" },
    onReadLicence = { calls += "readLicence" },
    onCloseLicence = { calls += "closeLicence" },
    onSource = { calls += "source" },
    onLicences = { calls += "licences" },
  )

  @Test
  fun aboutSaysTheVersionTheLicenceTheSourceAndThePrivacyStatement() {
    screen.checkEach(ScreenVariant.REQUIRED, { AboutScreen(AboutUiState(version = "1.0.0", computerApi = 6), actions) }) {
      assertThat(compose.textsOf(SettingsTags.ABOUT_VERSION)).isEqualTo("This app Version 1.0.0")
      assertThat(compose.textsOf(SettingsTags.ABOUT_API)).isEqualTo("Your computer API version 6, when last seen")
      assertThat(compose.textsOf(SettingsTags.ABOUT_LICENCE)).contains("PhonoTrainer is free software under the GNU GPL-3.0-or-later.")
      assertThat(compose.textsOf(SettingsTags.ABOUT_READ_LICENCE)).contains("GPL-3.0-or-later")
      assertThat(compose.textsOf(SettingsTags.ABOUT_SOURCE)).contains("https://github.com/mapineda48/phenomena")
      assertThat(compose.textsOf(SettingsTags.ABOUT_PRIVACY)).contains(
        "PhonoTrainer has no account and sends nothing to the internet. Your clips and recordings go only to your own computer.",
      )
      assertThat(compose.textsOf(SettingsTags.ABOUT_LICENCES)).contains("Open-source licences")
    }
  }

  @Test
  fun withoutAComputerTheApiLineSaysSo() {
    screen.checkEach(ScreenVariant.REQUIRED, { AboutScreen(AboutUiState(version = "1.0.0"), actions) }) {
      assertThat(compose.textsOf(SettingsTags.ABOUT_API)).isEqualTo("Your computer Not connected yet")
    }
  }

  @Test
  fun eachRowCallsItsAction() {
    screen.setContent { AboutScreen(AboutUiState(version = "1.0.0"), actions) }
    compose.onNodeWithTag(SettingsTags.ABOUT_READ_LICENCE).performClick()
    compose.onNodeWithTag(SettingsTags.ABOUT_SOURCE).performClick()
    compose.onNodeWithTag(SettingsTags.ABOUT_LICENCES).performClick()
    assertThat(calls).containsExactly("readLicence", "source", "licences").inOrder()
  }

  @Test
  fun theAppsLicenceOpensInASheet() {
    val open = LicenceTextUi(
      SettingsSamples.app,
      listOf(LicenceSection(SettingsSamples.app.licences.single(), listOf("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007"))),
    )
    screen.setContent { AboutScreen(AboutUiState(version = "1.0.0", licence = open), actions) }
    compose.onNodeWithTag(SettingsTags.LICENCE_SHEET).assertExists()
    assertThat(compose.textsOf(SettingsTags.LICENCE_TEXT)).contains("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007")
    compose.onNodeWithTag(SettingsTags.LICENCE_CLOSE).performClick()
    assertThat(calls).containsExactly("closeLicence")
  }
}
