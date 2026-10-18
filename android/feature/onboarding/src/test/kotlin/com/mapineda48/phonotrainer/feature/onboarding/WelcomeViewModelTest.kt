package com.mapineda48.phonotrainer.feature.onboarding

import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.connectivity.FakeComputerStatusRepository
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The Welcome's decision (spec F-01 behaviour 2, F-01-AC2): it is marked done however it ends, an unpaired phone goes
 * on to Connect in its first-run mode, a paired one back to Home, Back on the first page goes back without Connect, a
 * replayed tour only goes back (it never re-pairs and leaves the settings alone), and a second tap does nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val settings = FakeSettingsRepository()
  private val computer = FakeComputerStatusRepository()
  private val exits = mutableListOf<WelcomeExit>()

  private fun viewModel(store: SettingsRepository = settings) = WelcomeViewModel(store, computer)

  @Test
  fun getStartedMarksTheWelcomeDoneAndGoesOnToConnect() = runTest {
    viewModel().finish(restart = false) { exits += it }
    assertThat(settings.settings.value.welcomeDone).isTrue()
    assertThat(exits).containsExactly(WelcomeExit.CONNECT)
  }

  @Test
  fun aPhonePairedFromAShareGoesStraightHome() = runTest {
    computer.pairedState.value = PAIRED
    viewModel().finish(restart = false) { exits += it }
    assertThat(settings.settings.value.welcomeDone).isTrue()
    assertThat(exits).containsExactly(WelcomeExit.BACK)
  }

  @Test
  fun backOnTheFirstPageMarksItDoneWithoutConnect() = runTest {
    viewModel().finish(restart = false, connect = false) { exits += it }
    assertThat(settings.settings.value.welcomeDone).isTrue()
    assertThat(exits).containsExactly(WelcomeExit.BACK)
  }

  @Test
  fun theReplayedTourOnlyGoesBack() = runTest {
    val before = settings.settings.value
    viewModel().finish(restart = true) { exits += it }
    assertThat(exits).containsExactly(WelcomeExit.BACK)
    assertThat(settings.settings.value).isEqualTo(before)
  }

  @Test
  fun aSecondTapDoesNothing() = runTest {
    val viewModel = viewModel()
    viewModel.finish(restart = false) { exits += it }
    viewModel.finish(restart = false) { exits += it }
    assertThat(exits).hasSize(1)
    assertThat(viewModel.busy.value).isTrue()
  }

  @Test
  fun aFailedWriteNeverKeepsTheLearnerOnTheWelcome() = runTest {
    val failing = object : SettingsRepository {
      override val settings: StateFlow<UserSettings> = this@WelcomeViewModelTest.settings.settings

      override suspend fun update(change: (UserSettings) -> UserSettings) = throw IOException("disk full")
    }
    viewModel(failing).finish(restart = false) { exits += it }
    assertThat(exits).containsExactly(WelcomeExit.CONNECT)
  }

  @Test
  fun connectReplacesTheWelcomeSoBackFromConnectIsHome() {
    val navigator = RecordingNavigator()
    WelcomeExits.apply(WelcomeExit.CONNECT, navigator)
    WelcomeExits.apply(WelcomeExit.BACK, navigator)
    assertThat(navigator.calls).containsExactly("back", "navigate:${ConnectKey(ConnectMode.FIRST_RUN)}", "back").inOrder()
  }

  private class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()

    override fun navigate(key: NavKey) {
      calls += "navigate:$key"
    }

    override fun back() {
      calls += "back"
    }

    override fun switchTab(tab: Tab) {
      calls += "tab:$tab"
    }

    override fun open(target: NavTarget) {
      calls += "open:$target"
    }
  }

  private companion object {
    val PAIRED = PairedComputer(
      serverId = ServerId("a1b2c3d4e5f6a7b8"),
      name = "Study desk",
      deviceId = DeviceId("d1"),
      deviceName = "Galaxy phone",
      address = HostPort("192.0.2.10", 8000),
      libraryId = LibraryId("l1"),
      pairedAt = Instant.parse("2026-10-18T09:00:00Z"),
      lastSeen = null,
      lastSync = null,
      apiVersion = 6,
      capabilities = null,
    )
  }
}
