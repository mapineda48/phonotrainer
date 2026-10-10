package com.mapineda48.phonotrainer.core.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.TransferKind
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.navigation.InternalEntry
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.ui.component.RequestFailureCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Architecture 13 and spec F-23: what is posted, on which channel, private on the lock screen, with a distinct tap per
 * target; only with the permission and the learner's switch, and never before the settings were read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidNotifierTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val shadow: ShadowNotificationManager = shadowOf(manager)
  private val scope = CoroutineScope(UnconfinedTestDispatcher())
  private val settings = FakeSettingsRepository()

  private val clipA = JobId("20261004-133519-a77c")
  private val clipB = JobId("20261005-101010-b88d")
  private val request = RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")
  private val tooLong = RequestError(code = "too_long", message = null, downloaderVersion = null, atSending = false)

  private fun notifier(settings: SettingsRepository = this.settings): AndroidNotifier =
    AndroidNotifier(context, settings, NotificationPermissionMonitor(context, settings, scope, SDK), scope)

  @Before
  fun grantPermission() {
    shadowOf(context).grantPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
  }

  @After
  fun tearDown() = scope.cancel()

  @Test
  fun aReadyClipIsPostedOnItsChannelAndPrivateOnTheLockScreen() {
    notifier().clipReady(clipA, "Coffee with Ana", 15)

    val posted = shadow.getNotification(AndroidNotifier.TAG_CLIP + clipA.value, AndroidNotifier.ID_CLIP_READY)
    assertThat(posted.channelId).isEqualTo(NotificationChannels.CLIPS_READY)
    assertThat(posted.title()).isEqualTo("Ready to watch: Coffee with Ana")
    assertThat(posted.text()).isEqualTo("15 shorts, tap to start.")
    assertThat(posted.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    assertThat(posted.publicVersion.title()).isEqualTo("Clips ready")
    assertThat(posted.publicVersion.extras.toString()).doesNotContain("Coffee")
    assertThat(posted.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    assertThat(posted.target()).isEqualTo(NavTarget.ClipFeed(clipA))

    notifier().clipReady(clipB, "One more", 1)
    assertThat(shadow.getNotification(AndroidNotifier.TAG_CLIP + clipB.value, AndroidNotifier.ID_CLIP_READY).text())
      .isEqualTo("1 short, tap to start.")
  }

  @Test
  fun twoTargetsGiveTwoDistinctPendingIntents() {
    val notifier = notifier()
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    notifier.clipReady(clipB, "At the station", 9)

    val one = shadow.getNotification(AndroidNotifier.TAG_CLIP + clipA.value, AndroidNotifier.ID_CLIP_READY).contentIntent
    val two = shadow.getNotification(AndroidNotifier.TAG_CLIP + clipB.value, AndroidNotifier.ID_CLIP_READY).contentIntent

    assertThat(one).isNotEqualTo(two)
    assertThat(shadowOf(one).requestCode).isNotEqualTo(shadowOf(two).requestCode)
    assertThat(shadowOf(one).savedIntent.filterEquals(shadowOf(two).savedIntent)).isFalse()
    listOf(one to clipA, two to clipB).forEach { (pending, clip) ->
      assertThat(shadowOf(pending).isActivityIntent).isTrue()
      assertThat(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
      // Exactly the alias intent the shell honours once (architecture 16.4), so a recreate does not navigate again.
      val saved = shadowOf(pending).savedIntent
      assertThat(saved.filterEquals(InternalEntry.intent(context, NavTarget.ClipFeed(clip)))).isTrue()
      assertThat(InternalEntry.targetOf(saved)).isEqualTo(NavTarget.ClipFeed(clip))
    }
    // The same target always gives the same PendingIntent, so a repost updates it instead of adding another.
    assertThat(NotificationTargets.contentIntent(context, NavTarget.ClipFeed(clipA))).isEqualTo(one)
  }

  @Test
  fun severalReadyClipsAreGroupedUnderASummary() {
    val notifier = notifier()
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    assertThat(shadow.getNotification(AndroidNotifier.TAG_SUMMARY, AndroidNotifier.ID_CLIPS_READY_SUMMARY)).isNull()

    notifier.clipReady(clipB, "At the station", 9)

    val summary = shadow.getNotification(AndroidNotifier.TAG_SUMMARY, AndroidNotifier.ID_CLIPS_READY_SUMMARY)
    assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
    assertThat(summary.group).isEqualTo(AndroidNotifier.GROUP_CLIPS_READY)
    assertThat(summary.title()).isEqualTo("Clips ready")
    assertThat(summary.text()).isEqualTo("2 clips ready to watch")
    assertThat(summary.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    assertThat(summary.publicVersion.title()).isEqualTo("Clips ready")
    assertThat(summary.target()).isEqualTo(NavTarget.Home)
    assertThat(shadow.getNotification(AndroidNotifier.TAG_CLIP + clipA.value, AndroidNotifier.ID_CLIP_READY).group)
      .isEqualTo(AndroidNotifier.GROUP_CLIPS_READY)
  }

  @Test
  fun needsAttentionReadsTheFailureCopyLineAndOpensTheRequest() {
    notifier().needsAttention(request, "A long talk", Site.YOUTUBE, tooLong)

    val posted = shadow.getNotification(AndroidNotifier.TAG_REQUEST + request.value, AndroidNotifier.ID_NEEDS_ATTENTION)
    assertThat(posted.channelId).isEqualTo(NotificationChannels.NEEDS_ATTENTION)
    assertThat(posted.title()).isEqualTo("A long talk")
    assertThat(posted.text()).isEqualTo(RequestFailureCopy.notificationLine(tooLong, Site.YOUTUBE, context.resources))
    assertThat(posted.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    assertThat(posted.publicVersion.title()).isEqualTo("Needs attention")
    assertThat(posted.target()).isEqualTo(NavTarget.RequestDetail(request))
  }

  @Test
  fun aTitleWithALinkOrAnAddressIsNeverShown() {
    val notifier = notifier()
    notifier.clipReady(clipA, "http://192.0.2.10:8000/api/phone/jobs", 3)
    notifier.clipReady(clipB, "example.com/videos/1", 3)
    notifier.needsAttention(request, "Watch https://example.com/v/1", null, tooLong)

    assertThat(shadow.getNotification(AndroidNotifier.TAG_CLIP + clipA.value, AndroidNotifier.ID_CLIP_READY).title())
      .isEqualTo("Clips ready")
    assertThat(shadow.getNotification(AndroidNotifier.TAG_CLIP + clipB.value, AndroidNotifier.ID_CLIP_READY).title())
      .isEqualTo("Clips ready")
    assertThat(
      shadow.getNotification(AndroidNotifier.TAG_REQUEST + request.value, AndroidNotifier.ID_NEEDS_ATTENTION).title(),
    ).isEqualTo("Needs attention")
    shadow.allNotifications.forEach {
      assertThat(it.extras.toString()).doesNotContain("192.0.2.10")
      assertThat(it.extras.toString()).doesNotContain("example.com")
    }
    assertThat(NotificationText.safe("Connected speech, part 2.5")).isEqualTo("Connected speech, part 2.5")
  }

  @Test
  fun theLearnersSwitchesAreRespected() {
    settings.state.value = UserSettings(notifyReady = false)
    val notifier = notifier()
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    assertThat(shadow.size()).isEqualTo(0)
    notifier.needsAttention(request, "A long talk", null, tooLong)
    assertThat(shadow.size()).isEqualTo(1)

    settings.state.value = UserSettings(notifyAttention = false)
    notifier.needsAttention(RequestId("6b1d5d6f-1c7f-4ce7-8a64-2a2c1ccf8d22"), "Another", null, tooLong)
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    assertThat(shadow.size()).isEqualTo(2)
  }

  @Test
  fun withoutThePermissionNothingIsPosted() {
    shadowOf(context).denyPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
    settings.state.value = UserSettings(notificationsAsked = true)

    notifier().clipReady(clipA, "Coffee with Ana", 15)
    notifier().needsAttention(request, "A long talk", null, tooLong)

    assertThat(shadow.size()).isEqualTo(0)
  }

  @Test
  fun blockedInTheSystemSettingsNothingIsPosted() {
    shadow.setNotificationsEnabled(false)
    notifier().clipReady(clipA, "Coffee with Ana", 15)
    assertThat(shadow.size()).isEqualTo(0)
  }

  @Test
  fun nothingIsDecidedBeforeTheSettingsAreRead() {
    val gate = CompletableDeferred<Unit>()
    val stored = FakeSettingsRepository(UserSettings(notifyReady = false))
    val slow = object : SettingsRepository by stored {
      override suspend fun awaitLoaded() = gate.await()
    }
    val notifier = notifier(slow)

    notifier.clipReady(clipA, "Coffee with Ana", 15)
    notifier.needsAttention(request, "A long talk", null, tooLong)
    assertThat(shadow.size()).isEqualTo(0)

    gate.complete(Unit)
    // The stored "ready" switch is off: only the attention notification appears once the settings are read.
    assertThat(shadow.allNotifications.map { it.channelId }).containsExactly(NotificationChannels.NEEDS_ATTENTION)
  }

  @Test
  fun aTransferIsOngoingSilentAndPrivate() {
    val stop = PendingIntent.getBroadcast(context, 0, Intent("stop"), PendingIntent.FLAG_IMMUTABLE)
    val upload = notifier().transferNotification(
      TransferKind.UPLOAD,
      TransferProgress(title = "Coffee with Ana", doneBytes = 124_800_000, totalBytes = 312_000_000, items = 1),
      stop,
    )

    assertThat(upload.channelId).isEqualTo(NotificationChannels.TRANSFERS)
    assertThat(upload.title()).isEqualTo("Sending to your computer")
    assertThat(upload.text()).isEqualTo("Coffee with Ana · 125 MB of 312 MB")
    assertThat(upload.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
    assertThat(upload.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(40)
    assertThat(upload.extras.getInt(Notification.EXTRA_PROGRESS_MAX)).isEqualTo(100)
    assertThat(upload.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    assertThat(upload.publicVersion.title()).isEqualTo("Transfers")
    assertThat(upload.actions.single().title.toString()).isEqualTo("Stop")
    assertThat(upload.actions.single().actionIntent).isEqualTo(stop)
    assertThat(upload.target()).isEqualTo(NavTarget.Requests)

    val download = notifier().transferNotification(
      TransferKind.DOWNLOAD,
      TransferProgress(title = "At the station", doneBytes = 34_000_000, totalBytes = null, items = 2),
    )
    assertThat(download.title()).isEqualTo("Bringing 2 clips to this phone")
    assertThat(download.text()).isEqualTo("At the station · 34 MB")
    assertThat(download.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)).isTrue()
    assertThat(download.actions).isNull()
    assertThat(download.target()).isEqualTo(NavTarget.Home)

    val copy = notifier().transferNotification(TransferKind.SHARE_COPY, TransferProgress("clip.mp4", 0, 61_000_000, 1))
    assertThat(copy.title()).isEqualTo("Preparing the file")
    assertThat(copy.text()).isEqualTo("clip.mp4 · 0 MB of 61 MB")
  }

  @Test
  fun theChannelsAreCreatedOnceWithTheirImportance() {
    NotificationChannels.ensure(context)
    NotificationChannels.ensure(context)

    assertThat(manager.notificationChannels.map { it.id }).containsExactly(
      NotificationChannels.CLIPS_READY,
      NotificationChannels.NEEDS_ATTENTION,
      NotificationChannels.TRANSFERS,
    )
    val ready = manager.getNotificationChannel(NotificationChannels.CLIPS_READY)
    val attention = manager.getNotificationChannel(NotificationChannels.NEEDS_ATTENTION)
    val transfers = manager.getNotificationChannel(NotificationChannels.TRANSFERS)
    assertThat(ready.name.toString()).isEqualTo("Clips ready")
    assertThat(ready.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    assertThat(attention.name.toString()).isEqualTo("Needs attention")
    assertThat(attention.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    assertThat(transfers.name.toString()).isEqualTo("Transfers")
    assertThat(transfers.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    assertThat(transfers.sound).isNull()
    assertThat(transfers.shouldVibrate()).isFalse()
    listOf(ready, attention, transfers).forEach {
      assertThat(it.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    }
  }

  private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

  private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  private fun Notification.target(): NavTarget? = InternalEntry.targetOf(shadowOf(contentIntent).savedIntent)

  private companion object {
    const val SDK = 35
  }
}
