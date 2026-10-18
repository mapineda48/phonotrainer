package com.mapineda48.phonotrainer.e2e.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.LicencesKey
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.settings.SettingsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Open-source licences on the managed device (spec F-24-AC1, AC2; architecture 20.6): with the device in airplane mode,
 * the list shows every runtime library of this build's own generated report (`res/raw/aboutlibraries.json`), each with
 * its version and licence, every entry of `bundled.json` (the three font families and the Lucide set) and the app; and
 * the text of every licence opens from the APK. The sources are read here on their own, with `org.json`, so the screen
 * is compared with them and not with its own parser.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class SettingsLicencesTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @get:Rule(order = 2)
  val timeout: Timeout = Timeout.builder().withTimeout(LIMIT_S, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @Inject lateinit var settings: SettingsRepository

  private val phono = PhonoRobot(compose)
  private val robot = SettingsRobot(compose, phono)
  private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private var airplaneWas: String? = null

  @Before
  fun setUp() {
    hilt.inject()
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    // F-24-AC1: no network at all while the licences are read. Restored after the test.
    airplaneWas = shell("settings get global airplane_mode_on").trim()
    shell("cmd connectivity airplane-mode enable")
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
    if (airplaneWas != "1") shell("cmd connectivity airplane-mode disable")
  }

  private fun shell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }

  /** One row as the sources say it should read. */
  private data class Expected(val id: String, val name: String, val version: String?, val licence: String)

  // The report is generated into the application's resources; the test finds it as the screen does, by name.
  @SuppressLint("DiscouragedApi")
  private fun report(): JSONObject {
    val id = app.resources.getIdentifier("aboutlibraries", "raw", app.packageName)
    check(id != 0) { "This build has no licence report (res/raw/aboutlibraries.json)" }
    return JSONObject(app.resources.openRawResource(id).bufferedReader().use { it.readText() })
  }

  private fun asset(path: String): String = app.assets.open(path).bufferedReader().use { it.readText() }

  private fun libraries(report: JSONObject): List<Expected> {
    val table = report.optJSONObject("licenses") ?: JSONObject()
    val list = report.getJSONArray("libraries")
    return (0 until list.length()).map { index ->
      val library = list.getJSONObject(index)
      val keys = library.getJSONArray("licenses")
      val licences = (0 until keys.length()).map { k ->
        val key = keys.getString(k)
        table.optJSONObject(key)?.optString("spdxId")?.takeIf { it.isNotEmpty() } ?: key
      }
      val coordinates = library.getString("uniqueId")
      Expected(
        id = coordinates,
        name = library.optString("name").takeIf { it.isNotBlank() } ?: coordinates,
        version = library.optString("artifactVersion").takeIf { it.isNotBlank() },
        licence = licences.joinToString(" AND "),
      )
    }
  }

  private fun bundled(): List<Expected> {
    val components = JSONObject(asset("licenses/bundled.json")).getJSONArray("components")
    return (0 until components.length()).map { index ->
      val component = components.getJSONObject(index)
      val name = component.getString("name")
      Expected("bundled:$name", name, component.optString("version"), component.getString("licence"))
    }
  }

  private fun rowWords(tag: String): String =
    compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(SettingsTags.LICENCES_LIST).performScrollToNode(hasTestTag(tag))
  }

  /** Opens the licence sheet of the row [tag], checks that it shows [paragraph] of the licence's text, and closes it. */
  private fun assertTextOpens(tag: String, paragraph: String) {
    scrollTo(tag)
    compose.onNodeWithTag(tag).performClick()
    robot.waitForTag(SettingsTags.LICENCE_TEXT)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { robot.text(SettingsTags.LICENCE_TEXT).contains(paragraph) }
    phono.tap(SettingsTags.LICENCE_CLOSE)
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { !robot.has(SettingsTags.LICENCE_SHEET) }
  }

  /** The first paragraph of a plain-text licence as the screen reflows it. */
  private fun firstParagraph(text: String): String =
    text.replace("\r\n", "\n").trim().split(Regex("\n[ \t]*\n")).first().lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ")

  @Test
  fun everyLibraryFontAndIconSetIsListedWithItsLicenceAndEveryTextOpensOffline() {
    val libraries = libraries(report())
    val bundled = bundled()
    assertThat(bundled.map { it.name }).containsExactly("Atkinson Hyperlegible Next", "Newsreader", "Charis", "Lucide").inOrder()
    assertThat(libraries).isNotEmpty()

    phono.launch()
    phono.open(LicencesKey)
    phono.assertShown(LicencesKey)
    robot.waitForTag(SettingsTags.licence("app"))

    // F-24-AC2: every library of the report, every bundled component and the app, each with its version and licence.
    (bundled + libraries).forEach { expected ->
      val tag = SettingsTags.licence(expected.id)
      scrollTo(tag)
      val words = rowWords(tag)
      assertWithMessage(expected.id).that(words).contains(expected.name)
      assertWithMessage(expected.id).that(words).contains(expected.licence)
      expected.version?.let { assertWithMessage(expected.id).that(words).contains(it) }
    }
    scrollTo(SettingsTags.licence("app"))
    assertThat(rowWords(SettingsTags.licence("app"))).contains("GPL-3.0-or-later")

    // F-24-AC1: every licence text, offline: the app's, each bundled component's and one library per licence.
    assertTextOpens(SettingsTags.licence("app"), "GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007")
    val bundledFiles = JSONObject(asset("licenses/bundled.json")).getJSONArray("components")
    (0 until bundledFiles.length()).map { bundledFiles.getJSONObject(it) }.forEach { component ->
      val text = asset("licenses/" + component.getString("licenceFile"))
      assertTextOpens(SettingsTags.licence("bundled:" + component.getString("name")), firstParagraph(text))
    }
    libraries.groupBy { it.licence }.forEach { (licence, list) ->
      licence.split(" AND ").forEach { spdx ->
        assertWithMessage("$spdx has no bundled text").that(app.assets.list("licence-texts")?.toList()).contains("$spdx.txt")
      }
      val first = licence.split(" AND ").first()
      assertTextOpens(SettingsTags.licence(list.first().id), firstParagraph(asset("licence-texts/$first.txt")))
    }
  }

  private companion object {
    const val LIMIT_S = 300L
  }
}
