package com.mapineda48.phonotrainer.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * The system's notification service, as [AndroidNotifier] uses it. A post or a cancel is only enqueued: the system
 * runs it on its own thread and lists the result in [active] a moment later, so a caller must never expect to read
 * its own post back at once.
 */
internal interface SystemNotifications {
  fun notify(tag: String, id: Int, notification: Notification)

  fun cancel(tag: String, id: Int)

  /** This app's notifications the system shows now. */
  fun active(): List<StatusBarNotification>

  companion object {
    fun of(context: Context): SystemNotifications = Manager(context.getSystemService(NotificationManager::class.java))
  }

  private class Manager(private val manager: NotificationManager) : SystemNotifications {
    override fun notify(tag: String, id: Int, notification: Notification) = manager.notify(tag, id, notification)

    override fun cancel(tag: String, id: Int) = manager.cancel(tag, id)

    override fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()
  }
}
