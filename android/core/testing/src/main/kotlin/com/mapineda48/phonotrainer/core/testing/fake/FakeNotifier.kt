package com.mapineda48.phonotrainer.core.testing.fake

import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records what would have been posted (work-plan 4.6). The Android-facing `TransferNotifier` of
 * :core:notifications is faked where it is used, since this module never depends on an implementation module.
 */
class FakeNotifier(initial: NotificationPermission = NotificationPermission.GRANTED) : Notifier {
  val permission = MutableStateFlow(initial)
  val ready = mutableListOf<Triple<JobId, String, Int>>()
  val attention = mutableListOf<Pair<RequestId, RequestError>>()

  override fun clipReady(clip: JobId, title: String, shorts: Int) {
    ready += Triple(clip, title, shorts)
  }

  override fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError) {
    attention += request to error
  }

  override val permissionState: StateFlow<NotificationPermission> = permission
}
