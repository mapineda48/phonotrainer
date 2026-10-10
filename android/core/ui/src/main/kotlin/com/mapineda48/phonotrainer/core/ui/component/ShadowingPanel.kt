package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoCard
import com.mapineda48.phonotrainer.core.ui.R

/** The panel's options (F-12): how many rounds, and the slow speed of the listening rounds. */
@Immutable
data class ShadowingOptions(val rounds: Int = 3, val slowSpeed: Float = 0.75f)

/** Test tags of the shadowing panel. */
object ShadowingTags {
  const val START = "ui.shadowing.start"
  const val STOP = "ui.shadowing.stop"
}

/** Shadowing (WP-18; API frozen at M2, work-plan 4.5): lesson step 5 and the rail's "Shadow". Placeholder. */
@Composable
fun ShadowingPanel(
  state: ShadowingState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  options: ShadowingOptions,
  modifier: Modifier = Modifier,
) {
  PhonoCard(modifier = modifier) {
    when (state) {
      ShadowingState.Idle ->
        PhonoButton(stringResource(R.string.shadowing_start), onStart, Modifier.testTag(ShadowingTags.START))
      is ShadowingState.Running -> {
        Text(stringResource(R.string.shadowing_round, state.round, state.of), style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(
            if (state.phase == ShadowingPhase.LISTEN) R.string.shadowing_listen else R.string.shadowing_your_turn,
          ),
        )
        PhonoButton(stringResource(R.string.shadowing_stop), onStop, Modifier.testTag(ShadowingTags.STOP))
      }
    }
  }
}

@Preview
@Composable
private fun ShadowingPanelPreview() {
  PhonoTheme { ShadowingPanel(ShadowingState.Idle, onStart = {}, onStop = {}, options = ShadowingOptions()) }
}
