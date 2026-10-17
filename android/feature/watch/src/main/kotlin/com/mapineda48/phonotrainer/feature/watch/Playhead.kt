package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/**
 * The playhead of the current short, read from the player about every frame while it plays (architecture 11.3:
 * captions follow `positionMs()`, which already accounts for the audio latency; the highlight lands within one tick,
 * far inside SC-09's 60 ms) and once whenever [key] changes while it is paused (a seek, a replay, a new short). Only
 * the card on screen reads it.
 *
 * It ticks on a timer, not on the frame clock: a frame-clock loop would keep Compose busy for as long as a short
 * plays, so nothing could ever wait for the screen to be idle. Readers take what they need through `derivedStateOf`
 * (the phrase, the word being said, a coarse progress) or read it while drawing, so a tick recomposes nothing unless
 * what they show changes.
 */
@Composable
internal fun rememberPlayhead(read: () -> Long, playing: Boolean, key: Any?): State<Long> {
  val reader by rememberUpdatedState(read)
  val position = remember { mutableLongStateOf(read()) }
  LaunchedEffect(playing, key) {
    position.longValue = reader()
    if (playing) {
      while (true) {
        delay(PLAYHEAD_TICK_MS)
        position.longValue = reader()
      }
    }
  }
  return position
}

/** One display frame at 60 Hz. */
internal const val PLAYHEAD_TICK_MS = 16L

/** The progress bar's step: fine enough to look continuous, coarse enough not to redraw the row every tick. */
internal const val PROGRESS_STEP_MS = 50L
