package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The licences screen's state (spec F-24): the catalogue from the sources, a licence's text, a source that fails. */
class LicencesViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val report = requireNotNull(javaClass.classLoader?.getResource("report-sample.json")).readText()

  private class Source(var report: String?, val texts: Map<String, String>, var failing: Boolean = false) : LicenceSource {
    override suspend fun report(): String? = if (failing) error("unreadable") else report

    override suspend fun bundled(): String? = null

    override suspend fun text(path: String): String? = texts[path]
  }

  private val version = object : AppVersion {
    override val name: String = "2.1.0"
  }

  @Test
  fun theCatalogueIsTheAppAndTheReportsLibraries() = runTest {
    val viewModel = LicencesViewModel(Source(report, emptyMap()), version)

    val read = viewModel.state.value.read as LicencesRead.Ready
    assertThat(read.catalogue.app.version).isEqualTo("2.1.0")
    assertThat(read.catalogue.bundled).isEmpty()
    assertThat(read.catalogue.libraries).hasSize(4)
  }

  @Test
  fun openingAnEntryReadsItsTextInParagraphsAndClosingForgetsIt() = runTest {
    val texts = mapOf("licence-texts/Apache-2.0.txt" to "Apache License\nVersion 2.0\n\nTERMS AND CONDITIONS")
    val viewModel = LicencesViewModel(Source(report, texts), version)
    val activity = (viewModel.state.value.read as LicencesRead.Ready).catalogue.libraries.first()

    viewModel.open(activity)
    val open = viewModel.state.value.open!!
    assertThat(open.entry).isEqualTo(activity)
    assertThat(open.sections!!.single().paragraphs).containsExactly("Apache License Version 2.0", "TERMS AND CONDITIONS").inOrder()

    // The GPL of the app is not in this source: the sheet says its text is not bundled.
    viewModel.open((viewModel.state.value.read as LicencesRead.Ready).catalogue.app)
    assertThat(viewModel.state.value.open!!.sections!!.single().paragraphs).isNull()

    viewModel.close()
    assertThat(viewModel.state.value.open).isNull()
  }

  @Test
  fun aSourceThatFailsCanBeTriedAgain() = runTest {
    val source = Source(report, emptyMap(), failing = true)
    val viewModel = LicencesViewModel(source, version)
    assertThat(viewModel.state.value.read).isEqualTo(LicencesRead.Failed)

    source.failing = false
    viewModel.retry()
    assertThat(viewModel.state.value.read).isInstanceOf(LicencesRead.Ready::class.java)
  }

  @Test
  fun aBuildWithoutAReportStillListsTheApp() = runTest {
    val viewModel = LicencesViewModel(Source(null, emptyMap()), version)

    val catalogue = (viewModel.state.value.read as LicencesRead.Ready).catalogue
    assertThat(catalogue.libraries).isEmpty()
    assertThat(catalogue.all.map { it.id }).containsExactly("app")
  }
}
