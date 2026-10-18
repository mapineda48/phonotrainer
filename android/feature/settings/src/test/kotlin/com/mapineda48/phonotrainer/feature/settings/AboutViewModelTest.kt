package com.mapineda48.phonotrainer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** About's state (spec F-24): the version, the computer's API version last seen, and the app's licence text. */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val computer = FakeComputerStatusRepository()
  private val source = object : LicenceSource {
    override suspend fun report(): String? = null

    override suspend fun bundled(): String? = null

    override suspend fun text(path: String): String? =
      if (path == "licence-texts/GPL-3.0-or-later.txt") "GNU GENERAL PUBLIC LICENSE\nVersion 3\n\nPreamble" else null
  }
  private val version = object : AppVersion {
    override val name: String = "1.2.3"
  }

  @Test
  fun theApiVersionIsTheLastProbesElseThePairingsAndNoneWithoutAComputer() = runTest {
    val viewModel = AboutViewModel(computer, source, version)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
    assertThat(viewModel.state.value.version).isEqualTo("1.2.3")
    assertThat(viewModel.state.value.computerApi).isNull()

    computer.pairedState.value = SettingsSamples.computer
    assertThat(viewModel.state.value.computerApi).isEqualTo(6)

    computer.diagnosticsState.value = computer.diagnosticsState.value.copy(apiVersion = 7)
    assertThat(viewModel.state.value.computerApi).isEqualTo(7)
  }

  @Test
  fun readTheLicenceOpensTheGplAndCloses() = runTest {
    val viewModel = AboutViewModel(computer, source, version)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

    viewModel.openLicence()
    val open = viewModel.state.value.licence!!
    assertThat(open.entry.licenceLine).isEqualTo("GPL-3.0-or-later")
    assertThat(open.sections!!.single().paragraphs).containsExactly("GNU GENERAL PUBLIC LICENSE Version 3", "Preamble").inOrder()

    viewModel.closeLicence()
    assertThat(viewModel.state.value.licence).isNull()
  }
}
