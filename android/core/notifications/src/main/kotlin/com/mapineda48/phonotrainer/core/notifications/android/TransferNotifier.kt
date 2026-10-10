package com.mapineda48.phonotrainer.core.notifications.android

import android.app.Notification
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress

/**
 * The Android-facing part of the notifier (architecture 3.4): the ongoing notification a foreground transfer (upload,
 * download, share copy) shows. Kept out of `:core:domain`'s `Notifier` so that module stays JVM.
 */
fun interface TransferNotifier {
  fun transferNotification(kind: TransferKind, progress: TransferProgress): Notification
}
