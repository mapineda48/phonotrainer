package com.mapineda48.phonotrainer.core.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import java.time.Duration
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
import org.robolectric.shadows.ShadowSystemClock

/**
 * The "Ready to watch" summary (architecture 13, spec F-23 "grouped when several") against a system that behaves like
 * Android's: a post is listed only once the notification service ran it, never when `notify` returns. Robolectric's
 * own notification manager lists a post at once, so these tests put [EnqueuingSystem] in front of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClipsReadySummaryTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val scope = CoroutineScope(UnconfinedTestDispatcher())
  private val settings = FakeSettingsRepository()
  private val system = EnqueuingSystem(manager)
  private val notifier =
    AndroidNotifier(context, settings, NotificationPermissionMonitor(context, settings, scope, SDK), scope, system)

  private val clipA = JobId("20261004-133519-a77c")
  private val clipB = JobId("20261005-101010-b88d")
  private val clipC = JobId("20261006-090807-c99e")

  @Before
  fun grantPermission() {
    shadowOf(context).grantPermissions(NotificationPermissionRule.POST_NOTIFICATIONS)
  }

  @After
  fun tearDown() = scope.cancel()

  @Test
  fun theClipJustPostedCountsBeforeTheSystemListsIt() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    system.runPending()
    assertThat(summary()).isNull()

    // The verifier's device run: A shows, B is posted, and the system does not list B yet when the summary is decided.
    notifier.clipReady(clipB, "At the station", 9)
    system.runPending()

    val summary = checkNotNull(summary())
    assertThat(summary.text()).isEqualTo("2 clips ready to watch")
    assertThat(summary.lines()).containsExactly("Ready to watch: Coffee with Ana", "Ready to watch: At the station")
    assertThat(summary.group).isEqualTo(AndroidNotifier.GROUP_CLIPS_READY)
    assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
    assertThat(readyTags()).containsExactly(tag(clipA), tag(clipB))
  }

  @Test
  fun clipsPostedTogetherAreGroupedBeforeTheSystemListsAnyOfThem() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    notifier.clipReady(clipB, "At the station", 9)
    notifier.clipReady(clipC, "A long talk", 4)
    system.runPending()

    assertThat(summary()?.text()).isEqualTo("3 clips ready to watch")
    assertThat(readyTags()).containsExactly(tag(clipA), tag(clipB), tag(clipC))
  }

  @Test
  fun onceListedTheSystemDecidesSoADismissedClipNoLongerCounts() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    system.runPending()
    notifier.clipReady(clipB, "At the station", 9) // the summary decided here sees A listed
    system.runPending()
    assertThat(summary()?.text()).isEqualTo("2 clips ready to watch")

    manager.cancel(tag(clipA), AndroidNotifier.ID_CLIP_READY) // the learner swipes A away
    notifier.clipReady(clipC, "A long talk", 4)
    system.runPending()

    val summary = checkNotNull(summary())
    assertThat(summary.text()).isEqualTo("2 clips ready to watch")
    assertThat(summary.lines()).containsExactly("Ready to watch: At the station", "Ready to watch: A long talk")
  }

  @Test
  fun aPostTheSystemNeverListsStopsCountingAfterTheGracePeriod() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    system.dropPending() // the service never shows it

    ShadowSystemClock.advanceBy(Duration.ofMillis(AndroidNotifier.UNLISTED_GRACE_MS + 1))
    notifier.clipReady(clipB, "At the station", 9)
    system.runPending()

    assertThat(summary()).isNull()
    assertThat(readyTags()).containsExactly(tag(clipB))
  }

  @Test
  fun aSingleClipHasNoSummary() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    system.runPending()
    notifier.clipReady(clipA, "Coffee with Ana", 15) // the same clip again replaces its notification
    system.runPending()

    assertThat(summary()).isNull()
    assertThat(readyTags()).containsExactly(tag(clipA))
  }

  private fun tag(clip: JobId) = AndroidNotifier.TAG_CLIP + clip.value

  private fun summary(): Notification? = manager.activeNotifications
    .singleOrNull { it.tag == AndroidNotifier.TAG_SUMMARY && it.id == AndroidNotifier.ID_CLIPS_READY_SUMMARY }
    ?.notification

  private fun readyTags(): List<String> = manager.activeNotifications
    .filter { it.tag?.startsWith(AndroidNotifier.TAG_CLIP) == true }
    .map { it.tag }

  private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  private fun Notification.lines(): List<String> =
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }

  /**
   * Android's notification service: `notify` and `cancel` only enqueue, and the post shows in [active] once the
   * service ran it ([runPending]), or never if it dropped it ([dropPending]).
   */
  private class EnqueuingSystem(private val manager: NotificationManager) : SystemNotifications {
    private val pending = ArrayDeque<() -> Unit>()

    override fun notify(tag: String, id: Int, notification: Notification) {
      pending.addLast { manager.notify(tag, id, notification) }
    }

    override fun cancel(tag: String, id: Int) {
      pending.addLast { manager.cancel(tag, id) }
    }

    override fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    fun runPending() {
      while (pending.isNotEmpty()) pending.removeFirst().invoke()
    }

    fun dropPending() = pending.clear()
  }

  private companion object {
    const val SDK = 35
  }
}
