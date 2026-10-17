package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * The playhead of the current short, read from the player every frame while it plays (architecture 11.3: captions
 * follow `positionMs()`, which already accounts for the audio latency) and once whenever [key] changes while it is
 * paused (a seek, a replay, a new short). Only the card on screen reads it.
 */
@Composable
internal fun rememberPlayhead(read: () -> Long, playing: Boolean, key: Any?): State<Long> {
  val reader by rememberUpdatedState(read)
  val position = remember { mutableLongStateOf(read()) }
  LaunchedEffect(playing, key) {
    position.longValue = reader()
    if (playing) {
      while (true) {
        withFrameNanos { }
        position.longValue = reader()
      }
    }
  }
  return position
}
