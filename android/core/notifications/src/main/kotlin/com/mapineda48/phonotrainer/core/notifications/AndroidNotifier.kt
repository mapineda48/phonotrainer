package com.mapineda48.phonotrainer.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mapineda48.phonotrainer.core.common.di.ApplicationScope
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.notifications.android.TransferNotifier
import com.mapineda48.phonotrainer.core.ui.component.RequestFailureCopy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.mapineda48.phonotrainer.core.designsystem.R as DesignR

/**
 * The notifications of architecture 13 and spec F-23. Callers pass only this phone's own requests and clips (browser
 * jobs never reach it) and post each outcome once (flags in `request`); the notifier posts only while the permission
 * allows it and the learner's switch for that kind is on, and never before the settings were read. Every notification
 * is private on the lock screen, with the channel's name as its public version; its tap opens its own target through
 * the internal alias. A title that carries a link or an address is replaced by the channel's name, so a notification
 * never shows one.
 */
@Singleton
class AndroidNotifier @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val settings: SettingsRepository,
  private val permission: NotificationPermissionMonitor,
  @param:ApplicationScope private val scope: CoroutineScope,
) : Notifier, TransferNotifier {
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val settingsRead = AtomicBoolean(false)

  init {
    scope.launch {
      settings.awaitLoaded()
      settingsRead.set(true)
    }
  }

  override val permissionState: StateFlow<NotificationPermission> = permission.state

  override fun clipReady(clip: JobId, title: String, shorts: Int) = whenAllowed({ it.notifyReady }) {
    val heading = NotificationText.safe(title)?.let { context.getString(R.string.notification_clip_ready_title, it) }
      ?: NotificationChannels.name(context, NotificationChannels.CLIPS_READY)
    val notification = builder(NotificationChannels.CLIPS_READY, NavTarget.ClipFeed(clip))
      .setContentTitle(heading)
      .setContentText(context.resources.getQuantityString(R.plurals.notification_clip_ready_text, shorts, shorts))
      .setGroup(GROUP_CLIPS_READY)
      .build()
    manager.notify(TAG_CLIP + clip.value, ID_CLIP_READY, notification)
    updateClipsReadySummary()
  }

  override fun needsAttention(request: RequestId, title: String, site: Site?, error: RequestError) =
    whenAllowed({ it.notifyAttention }) {
      val line = RequestFailureCopy.notificationLine(error, site, context.resources)
      val notification = builder(NotificationChannels.NEEDS_ATTENTION, NavTarget.RequestDetail(request))
        .setContentTitle(
          NotificationText.safe(title) ?: NotificationChannels.name(context, NotificationChannels.NEEDS_ATTENTION),
        )
        .setContentText(line)
        .setStyle(NotificationCompat.BigTextStyle().bigText(line))
        .build()
      manager.notify(TAG_REQUEST + request.value, ID_NEEDS_ATTENTION, notification)
    }

  override fun transferNotification(kind: TransferKind, progress: TransferProgress, stop: PendingIntent?): Notification {
    NotificationChannels.ensure(context)
    val title = when (kind) {
      TransferKind.UPLOAD -> context.getString(R.string.notification_transfer_upload)
      TransferKind.DOWNLOAD -> progress.items.coerceAtLeast(1).let {
        context.resources.getQuantityString(R.plurals.notification_transfer_download, it, it)
      }
      TransferKind.SHARE_COPY -> context.getString(R.string.notification_transfer_share_copy)
    }
    val what = NotificationText.safe(progress.title) ?: title
    val total = progress.totalBytes?.takeIf { it > 0 }
    val text = if (total != null) {
      context.getString(R.string.notification_transfer_bytes, what, megabytes(progress.doneBytes), megabytes(total))
    } else {
      context.getString(R.string.notification_transfer_bytes_unknown_total, what, megabytes(progress.doneBytes))
    }
    val target = if (kind == TransferKind.DOWNLOAD) NavTarget.Home else NavTarget.Requests
    return builder(NotificationChannels.TRANSFERS, target)
      .setContentTitle(title)
      .setContentText(text)
      .setAutoCancel(false)
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .apply {
        if (total != null) {
          setProgress(PERCENT, (progress.doneBytes * PERCENT / total).toInt().coerceIn(0, PERCENT), false)
        } else {
          setProgress(0, 0, true)
        }
        if (stop != null) addAction(0, context.getString(R.string.notification_transfer_stop), stop)
      }
      .build()
  }

  /** Runs [post] if the learner's [switch] is on and the permission allows posting, once the settings were read. */
  private fun whenAllowed(switch: (UserSettings) -> Boolean, post: () -> Unit) {
    if (settingsRead.get()) {
      postIfAllowed(switch, post)
    } else {
      scope.launch {
        settings.awaitLoaded()
        postIfAllowed(switch, post)
      }
    }
  }

  private fun postIfAllowed(switch: (UserSettings) -> Boolean, post: () -> Unit) {
    if (!switch(settings.settings.value)) return
    if (!NotificationPermissionRule.allowsPosting(permission.refresh())) return
    NotificationChannels.ensure(context)
    post()
  }

  /** "Ready to watch" notifications are grouped with a summary when several are showing (architecture 13). */
  private fun updateClipsReadySummary() {
    val ready = manager.activeNotifications.filter {
      it.notification.group == GROUP_CLIPS_READY && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
    }
    if (ready.size < 2) {
      manager.cancel(TAG_SUMMARY, ID_CLIPS_READY_SUMMARY)
      return
    }
    val lines = NotificationCompat.InboxStyle()
    ready.forEach { lines.addLine(it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)) }
    val summary = builder(NotificationChannels.CLIPS_READY, NavTarget.Home)
      .setContentTitle(NotificationChannels.name(context, NotificationChannels.CLIPS_READY))
      .setContentText(
        context.resources.getQuantityString(R.plurals.notification_clips_ready_summary, ready.size, ready.size),
      )
      .setStyle(lines)
      .setGroup(GROUP_CLIPS_READY)
      .setGroupSummary(true)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .build()
    manager.notify(TAG_SUMMARY, ID_CLIPS_READY_SUMMARY, summary)
  }

  private fun builder(channel: String, target: NavTarget): NotificationCompat.Builder =
    NotificationCompat.Builder(context, channel)
      .setSmallIcon(DesignR.drawable.ic_stat_phono)
      .setContentIntent(NotificationTargets.contentIntent(context, target))
      .setAutoCancel(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion(channel))

  /** The lock-screen version (security review L-12): the channel's name only. */
  private fun publicVersion(channel: String): Notification = NotificationCompat.Builder(context, channel)
    .setSmallIcon(DesignR.drawable.ic_stat_phono)
    .setContentTitle(NotificationChannels.name(context, channel))
    .build()

  private fun megabytes(bytes: Long): Long = (bytes.coerceAtLeast(0) + HALF_MB) / ONE_MB

  companion object {
    /** Every notification of this notifier has a tag (one per clip or request), so ids never collide with others'. */
    const val TAG_CLIP: String = "clip:"
    const val TAG_REQUEST: String = "request:"
    const val TAG_SUMMARY: String = "clips_ready_summary"
    const val ID_CLIP_READY: Int = 1
    const val ID_NEEDS_ATTENTION: Int = 2
    const val ID_CLIPS_READY_SUMMARY: Int = 3
    const val GROUP_CLIPS_READY: String = "com.mapineda48.phonotrainer.CLIPS_READY"

    private const val PERCENT = 100
    private const val ONE_MB = 1_000_000L
    private const val HALF_MB = ONE_MB / 2
  }
}

/** Notification text never carries a link or an address (architecture 13, spec F-23). */
internal object NotificationText {
  private val LINK_OR_ADDRESS = Regex(
    """(?i)\b[a-z][a-z0-9+.-]*://|\bwww\.|\b(?:\d{1,3}\.){3}\d{1,3}\b|\b[a-z0-9-]+(?:\.[a-z0-9-]+)+/""",
  )

  /** [title] trimmed, or null when it is blank or carries a link or an address. */
  fun safe(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() && !LINK_OR_ADDRESS.containsMatchIn(it) }
}
