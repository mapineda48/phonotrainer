package com.mapineda48.phonotrainer.core.player.android

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.player.feed.FeedSurfaces
import kotlin.math.roundToInt
import androidx.media3.ui.compose.PlayerSurface as Media3Surface

/**
 * The video of the feed's page [index] (work-plan 4.2, architecture 11.2); `:feature:watch` depends on `:core:player`
 * for this composable. Only the current page renders to a surface: every other page, a page whose short has no video
 * and a stopped pool draw nothing here, so the card shows its poster (or the generated art) beneath, and keeps it over
 * the surface until `FeedPlaybackState.firstFrameRendered`, so a surface switch never flashes black.
 *
 * The video keeps its aspect ratio inside [modifier]'s bounds: [ContentScale.Fit] (the default) shows a landscape
 * video whole (spec F-06 item 7) on a `SurfaceView`; [ContentScale.Crop] lets a portrait one fill the card, on a
 * `TextureView` so the overflow is clipped. The ratio is the decoder's once known, else the listing's size and
 * rotation, so a prepared neighbour gets a sized surface at once.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
  feedPlayer: FeedPlayer,
  index: Int,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
) {
  val surfaces = feedPlayer as? FeedSurfaces
  if (surfaces == null) {
    Box(modifier = modifier)
    return
  }
  val target by surfaces.surface.collectAsState()
  val page = target?.takeIf { it.index == index }
  if (page == null) {
    Box(modifier = modifier)
    return
  }
  val crop = contentScale == ContentScale.Crop
  Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
    Media3Surface(
      player = page.player,
      modifier = if (crop) Modifier.fill(page.aspectRatio) else Modifier.aspectRatio(page.aspectRatio),
      surfaceType = if (crop) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
    )
  }
}

/** The smallest size of [ratio] that covers the bounds, centred (the overflow is clipped by the parent). */
private fun Modifier.fill(ratio: Float): Modifier = layout { measurable, constraints ->
  val width = constraints.maxWidth
  val height = constraints.maxHeight
  if (width == Constraints.Infinity || height == Constraints.Infinity || height == 0 || ratio <= 0f) {
    val placeable = measurable.measure(constraints)
    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
  val (w, h) = if (width.toFloat() / height > ratio) width to (width / ratio).roundToInt() else (height * ratio).roundToInt() to height
  val placeable = measurable.measure(Constraints.fixed(w, h))
  layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
}
