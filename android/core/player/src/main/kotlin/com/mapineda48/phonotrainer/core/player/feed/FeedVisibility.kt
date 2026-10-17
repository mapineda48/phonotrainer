package com.mapineda48.phonotrainer.core.player.feed

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * How the Watch screen tells the feed's player pool whether Watch is the visible tab (architecture 11.2, 16.2): the
 * Watch ViewModel outlives tab switches, so while Watch is hidden the pool stops every player (codecs released, the
 * current position kept) and prepares them again, paused, when Watch is shown. The pool does the same by itself when
 * the app leaves the foreground.
 *
 * `FeedPlayer` gains the same method through change request CR-WP11-1; until then the pool implements this interface,
 * so `(feedPlayer as? FeedVisibility)?.setVisible(false)` reaches it, and the method keeps its name and signature so
 * the pool's one implementation overrides both once the request is applied.
 */
interface FeedVisibility {
  fun setVisible(visible: Boolean)
}

/** The player that renders page [index] (the current short's; its follower in follower mode). */
internal data class SurfaceTarget(val index: Int, val player: Player)

/** What `PlayerSurface` reads from the pool: the one page that may render video, or null while the pool is stopped. */
internal interface FeedSurfaces {
  val surface: StateFlow<SurfaceTarget?>
}
