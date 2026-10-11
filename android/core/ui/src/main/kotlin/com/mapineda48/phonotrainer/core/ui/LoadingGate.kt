package com.mapineda48.phonotrainer.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoText
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoRadius
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoSpacing
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme
import kotlinx.coroutines.delay

/**
 * The loading rule of the spec, implemented once (architecture 3.1, design 9.21): nothing for 300 ms, then static
 * galley placeholder blocks, then after 1 s the words ([label], "Loading…" by default) as a polite live region; never
 * an unlabelled spinner. [content] as soon as [loading] is false.
 */
@Composable
fun LoadingGate(
  loading: Boolean,
  modifier: Modifier = Modifier,
  label: String? = null,
  content: @Composable () -> Unit,
) {
  if (!loading) {
    content()
    return
  }
  var stage by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    delay(PLACEHOLDER_AFTER_MS)
    stage = 1
    delay(LABEL_AFTER_MS - PLACEHOLDER_AFTER_MS)
    stage = 2
  }
  LoadingPlaceholder(stage, label ?: stringResource(R.string.ui_loading), modifier.testTag(UiTags.LOADING))
}

private const val PLACEHOLDER_AFTER_MS = 300L
private const val LABEL_AFTER_MS = 1_000L

/** The gate's look at [stage] (0 nothing, 1 blocks, 2 blocks and words). */
@Composable
internal fun LoadingPlaceholder(stage: Int, label: String, modifier: Modifier = Modifier) {
  val colors = PhonoTheme.colors
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10)) {
    if (stage >= 1) {
      val shape = RoundedCornerShape(PhonoRadius.medium)
      Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(PhonoSpacing.s10),
      ) {
        Box(Modifier.fillMaxWidth(0.6f).height(22.dp).clip(shape).background(colors.galley))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(shape).background(colors.galley))
        Box(Modifier.fillMaxWidth(0.8f).height(16.dp).clip(shape).background(colors.galley))
      }
    }
    if (stage >= 2) {
      PhonoText(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}

@PreviewLightDark
@Composable
internal fun LoadingGatePreview() {
  PhonoPreviewSurface {
    LoadingPlaceholder(stage = 1, label = "Loading your clips…")
    LoadingPlaceholder(stage = 2, label = "Loading your clips…")
  }
}
