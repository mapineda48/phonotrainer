package com.mapineda48.phonotrainer.core.player.android

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer

/**
 * The video surface of the feed's page [index] (work-plan 4.2, architecture 11.2); `:feature:watch` depends on
 * `:core:player` for this composable only. Placeholder at the contract freeze: an empty box.
 */
@Composable
fun PlayerSurface(feedPlayer: FeedPlayer, index: Int, modifier: Modifier = Modifier) {
  Box(modifier = modifier)
}
