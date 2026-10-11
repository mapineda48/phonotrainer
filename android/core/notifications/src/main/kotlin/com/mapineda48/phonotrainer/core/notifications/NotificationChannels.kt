package com.mapineda48.phonotrainer.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The three channels of architecture 13 and spec F-23: "Clips ready" and "Needs attention" at default importance,
 * "Transfers" low and silent. Creating them is idempotent (only the names follow the current strings), so the
 * application creates them at start and the notifier again before it posts.
 */
object NotificationChannels {
  const val CLIPS_READY: String = "clips_ready"
  const val NEEDS_ATTENTION: String = "needs_attention"
  const val TRANSFERS: String = "transfers"

  fun ensure(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannels(
      listOf(
        channel(context, CLIPS_READY, NotificationManager.IMPORTANCE_DEFAULT),
        channel(context, NEEDS_ATTENTION, NotificationManager.IMPORTANCE_DEFAULT),
        channel(context, TRANSFERS, NotificationManager.IMPORTANCE_LOW).apply {
          setSound(null, null)
          enableVibration(false)
          setShowBadge(false)
        },
      ),
    )
  }

  /** The channel's name: "Clips ready", "Needs attention" or "Transfers". */
  fun name(context: Context, channel: String): String = context.getString(
    when (channel) {
      CLIPS_READY -> R.string.notification_channel_clips_ready
      NEEDS_ATTENTION -> R.string.notification_channel_needs_attention
      TRANSFERS -> R.string.notification_channel_transfers
      else -> throw IllegalArgumentException("Unknown channel: $channel")
    },
  )

  private fun channel(context: Context, id: String, importance: Int) =
    NotificationChannel(id, name(context, id), importance).apply {
      // Clip titles and links are third-party text (security review L-12): never on the lock screen.
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
}
