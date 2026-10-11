package com.mapineda48.phonotrainer.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.mapineda48.phonotrainer.core.designsystem.illustration.PhonoIllustrations

/**
 * An illustration of the set (design 7.2), drawn at the width it is given and the height of its viewport's aspect
 * (380:214 for the scenes), never rasterized small. Decorative: hidden from accessibility, since the title and
 * sentence next to it carry the meaning. The dark twin comes from `drawable-night` through the theme's configuration.
 */
@Composable
fun Illustration(@DrawableRes illustration: Int, modifier: Modifier = Modifier) {
  Image(
    painter = painterResource(illustration),
    contentDescription = null,
    contentScale = ContentScale.Fit,
    modifier = modifier.fillMaxWidth().aspectRatio(PhonoIllustrations.aspectOf(illustration)),
  )
}

@PreviewLightDark
@Composable
private fun IllustrationPreview() {
  PhonoPreviewSurface {
    Illustration(PhonoIllustrations.FeedEmpty)
    Illustration(PhonoIllustrations.SetDone)
  }
}
