package com.mapineda48.phonotrainer.core.player.android

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.player.feed.FeedSurfaces

/**
 * The video of the feed's page [index] (work-plan 4.2, architecture 11.2); `:feature:watch` depends on `:core:player`
 * for this composable. Only the current page renders to a surface: every other page, a page whose short has no video
 * and a stopped pool draw nothing here, so the card shows its poster (or the generated art) beneath, and keeps it over
 * the surface until `FeedPlaybackState.firstFrameRendered`, so a surface switch never flashes black. The video keeps
 * its aspect ratio inside [modifier]'s bounds: [ContentScale.Fit] shows a landscape video whole (spec F-06 item 7),
 * [ContentScale.Crop] lets a portrait one fill the card.
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
  val player = target?.takeIf { it.index == index }?.player
  if (player == null) {
    Box(modifier = modifier)
    return
  }
  ContentFrame(
    player = player,
    modifier = modifier,
    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
    contentScale = contentScale,
    keepContentOnReset = false,
    shutter = {},
  )
}
