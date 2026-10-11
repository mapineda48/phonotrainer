package com.mapineda48.phonotrainer.core.designsystem.texture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.mapineda48.phonotrainer.core.designsystem.component.PhonoPreviewSurface
import com.mapineda48.phonotrainer.core.designsystem.theme.PhonoTheme

/** The two textures of design 5.4 on the page: grain with fibres (tab roots), then the halftone in a corner and full. */
@PreviewLightDark
@Composable
internal fun TexturesPreview() {
  PhonoPreviewSurface {
    Box(Modifier.fillMaxWidth().height(120.dp).paperGrain(fibres = true))
    Box(
      Modifier.fillMaxWidth().height(120.dp)
        .halftone(seed = 7, color = PhonoTheme.colors.halftoneStrong, area = HalftoneArea.CORNER_TOP_END),
    )
    Box(Modifier.fillMaxWidth().height(80.dp).halftone(seed = 11, color = PhonoTheme.colors.halftone))
  }
}
