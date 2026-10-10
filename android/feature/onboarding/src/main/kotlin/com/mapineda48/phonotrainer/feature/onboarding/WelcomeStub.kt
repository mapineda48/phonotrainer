package com.mapineda48.phonotrainer.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.ui.DestinationStub
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Marks the Welcome as done (F-01), so the shell does not show it again on launch. */
@HiltViewModel
internal class WelcomeStubViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
  fun finish(then: () -> Unit) {
    viewModelScope.launch {
      settings.update { it.copy(welcomeDone = true) }
      then()
    }
  }
}

@Composable
internal fun WelcomeRoute(onDone: () -> Unit, viewModel: WelcomeStubViewModel = hiltViewModel()) {
  WelcomeStubScreen(onGetStarted = { viewModel.finish(onDone) })
}

/** The Welcome stub (P-13): the first page's title and sentence and "Get started". WP-13 replaces it. */
@Composable
internal fun WelcomeStubScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
  DestinationStub(
    title = stringResource(R.string.welcome_title),
    lede = stringResource(R.string.welcome_lede),
    modifier = modifier,
  ) {
    PhonoButton(
      text = stringResource(R.string.welcome_get_started),
      onClick = onGetStarted,
      modifier = Modifier.testTag(OnboardingTags.GET_STARTED),
    )
  }
}

@Preview
@Composable
private fun WelcomeStubScreenPreview() {
  PhonoTheme { WelcomeStubScreen(onGetStarted = {}) }
}
