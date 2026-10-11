package com.mapineda48.phonotrainer.core.notifications.android

import android.app.Notification
import android.app.PendingIntent
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress

/**
 * The Android-facing part of the notifier (architecture 3.4): the ongoing notification a foreground transfer (upload,
 * download, share copy) shows, on the silent "Transfers" channel (spec F-23). Kept out of `:core:domain`'s `Notifier`
 * so that module stays JVM. The caller posts it (as a worker's foreground notification) under an id of its own; the
 * notifier's own notifications all carry a tag, so they never collide with it.
 */
fun interface TransferNotifier {
  /**
   * [stop] is the transfer's own cancel intent (for a worker, `WorkManager.createCancelPendingIntent`), shown as the
   * "Stop" action of spec F-23; null shows no action.
   */
  fun transferNotification(kind: TransferKind, progress: TransferProgress, stop: PendingIntent?): Notification

  /** The same without a "Stop" action. */
  fun transferNotification(kind: TransferKind, progress: TransferProgress): Notification =
    transferNotification(kind, progress, null)
}
