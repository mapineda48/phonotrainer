package com.mapineda48.phonotrainer.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapineda48.phonotrainer.core.navigation.Navigator
import com.mapineda48.phonotrainer.core.navigation.WelcomeKey

/**
 * The Welcome's entry (P-13): the page survives rotation and process death (saved with the entry's state); finishing
 * goes through the ViewModel, which says where to go next.
 */
@Composable
internal fun WelcomeRoute(key: WelcomeKey, navigator: Navigator, viewModel: WelcomeViewModel = hiltViewModel()) {
  var index by rememberSaveable { mutableIntStateOf(0) }
  val busy by viewModel.busy.collectAsStateWithLifecycle()
  val page = WelcomePage.at(index)
  val actions = remember(viewModel, navigator, key) {
    val exit: (WelcomeExit) -> Unit = { WelcomeExits.apply(it, navigator) }
    WelcomeActions(
      onNext = { index = (index + 1).coerceAtMost(WelcomePage.count - 1) },
      onPrevious = { index = (index - 1).coerceAtLeast(0) },
      onSkip = { viewModel.finish(key.restart, then = exit) },
      onFinish = { viewModel.finish(key.restart, then = exit) },
      onLeave = { viewModel.finish(key.restart, connect = false, then = exit) },
    )
  }
  WelcomeScreen(page = page, restart = key.restart, busy = busy, actions = actions)
}
