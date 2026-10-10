package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import kotlinx.coroutines.flow.StateFlow

/**
 * :core:notifications (WP-02), architecture 3.4 and 13. `transferNotification` returns an Android `Notification`, so
 * it lives in the Android-facing sub-interface of :core:notifications, keeping this module JVM.
 */
interface Notifier {
  fun clipReady(clip: JobId, title: String, shorts: Int)

  /** The text comes from `RequestFailureCopy` (architecture 19). */
  fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError)

  val permissionState: StateFlow<NotificationPermission>
}
