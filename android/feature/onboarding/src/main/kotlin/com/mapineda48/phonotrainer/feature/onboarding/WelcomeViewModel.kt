package com.mapineda48.phonotrainer.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ComputerStatusRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.navigation.ConnectKey
import com.mapineda48.phonotrainer.core.navigation.ConnectMode
import com.mapineda48.phonotrainer.core.navigation.Navigator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the Welcome goes when it ends (spec F-01 behaviour 2, P-13). */
internal enum class WelcomeExit {
  /** Back to where the Welcome was opened: Home on a first launch, Settings > Help when the tour was replayed. */
  BACK,

  /** "Connect to your computer" in its first-run mode, with "Not now" (F-01 behaviour 2). */
  CONNECT,
}

/** How the Welcome leaves: [WelcomeExit.CONNECT] replaces the Welcome with Connect, so Back from Connect is Home. */
internal object WelcomeExits {
  fun apply(exit: WelcomeExit, navigator: Navigator) {
    navigator.back()
    if (exit == WelcomeExit.CONNECT) navigator.navigate(ConnectKey(ConnectMode.FIRST_RUN))
  }
}

/**
 * The Welcome's one decision (spec F-01): it marks the Welcome as done (`UserSettings.welcomeDone`) however it ends
 * ("Get started", "Skip" or Back on the first page), so the next launch opens Home (F-01-AC2); then a phone that is not
 * paired goes on to Connect, and a paired one (paired from a share before the Welcome was ever seen) straight back to
 * Home. Replayed from Settings > Help ([restart]) it only goes back: it never re-pairs and never changes the settings
 * (Help has already re-armed the coach marks, spec F-01 behaviour 4). No runtime permission is asked here (F-01-AC1).
 */
@HiltViewModel
internal class WelcomeViewModel @Inject constructor(
  private val settings: SettingsRepository,
  private val computer: ComputerStatusRepository,
) : ViewModel() {
  private val finishing = MutableStateFlow(false)

  /** True from the first finishing tap until the Welcome has gone (a second tap does nothing). */
  val busy: StateFlow<Boolean> = finishing.asStateFlow()

  /**
   * Ends the Welcome. [connect] is false for Back on the first page (the learner chose to leave, not to go on); [then]
   * receives the exit once the settings are written, or once writing them failed (the learner is never kept here).
   */
  fun finish(restart: Boolean, connect: Boolean = true, then: (WelcomeExit) -> Unit) {
    if (finishing.value) return
    finishing.value = true
    if (restart) {
      then(WelcomeExit.BACK)
      return
    }
    viewModelScope.launch {
      try {
        settings.update { it.copy(welcomeDone = true) }
      } catch (failure: Exception) {
        // A failed write only means the Welcome shows again next launch (DataStore throws IOException).
        if (failure is CancellationException) throw failure
      }
      then(if (connect && computer.paired.value == null) WelcomeExit.CONNECT else WelcomeExit.BACK)
    }
  }
}
