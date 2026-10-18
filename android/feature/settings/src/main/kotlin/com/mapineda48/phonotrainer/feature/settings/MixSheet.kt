package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoSwitch
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.ui.PhonoButton
import com.mapineda48.phonotrainer.core.ui.PhonoButtonKind
import com.mapineda48.phonotrainer.core.ui.PhonoSheet

/**
 * "Clips left out of the Mix" (spec F-22, F-06): every clip with a switch that is on while the clip is in the Mix.
 * Turning one off is the same as the short's "Leave this clip out of the Mix" (F-07 More sheet); it applies at once.
 */
@Composable
internal fun MixSheet(clips: List<MixClip>, onInMix: (JobId, Boolean) -> Unit, onDismiss: () -> Unit) {
  val title = stringResource(R.string.mix_title)
  PhonoSheet(onDismiss = onDismiss, title = title, modifier = Modifier.testTag(SettingsTags.MIX_SHEET)) {
    MixContent(clips, onInMix, onDismiss)
  }
}

/** The sheet's content on its own, for previews and screen tests. */
@Composable
internal fun MixContent(clips: List<MixClip>, onInMix: (JobId, Boolean) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PhonoSpacing.s24),
    verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s8),
  ) {
    PhonoText(stringResource(R.string.mix_title), style = MaterialTheme.typography.headlineSmall, color = colors.ink)
    PhonoText(stringResource(R.string.mix_lede), style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
    if (clips.isEmpty()) {
      PhonoText(
        stringResource(R.string.mix_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.ink2,
        modifier = Modifier.padding(vertical = PhonoSpacing.s12).testTag(SettingsTags.MIX_EMPTY),
      )
    }
    clips.forEach { clip ->
      PhonoSwitch(
        label = clip.title,
        description = stringResource(if (clip.inMix) R.string.mix_in else R.string.mix_out),
        checked = clip.inMix,
        onCheckedChange = { onInMix(clip.id, it) },
        modifier = Modifier.testTag(SettingsTags.mixClip(clip.id)),
      )
    }
    PhonoButton(
      text = stringResource(R.string.mix_done),
      onClick = onDone,
      kind = PhonoButtonKind.SECONDARY,
      fillWidth = true,
      modifier = Modifier.padding(top = PhonoSpacing.s8).testTag(SettingsTags.MIX_DONE),
    )
  }
}
