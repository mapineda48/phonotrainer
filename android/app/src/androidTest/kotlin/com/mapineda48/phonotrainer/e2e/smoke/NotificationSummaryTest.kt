package com.mapineda48.phonotrainer.e2e.smoke

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.api.Notifier
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.notifications.AndroidNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Architecture 13 and spec F-23 ("grouped when several") on a real notification service, which lists a post only a
 * moment after `notify` returns (Robolectric's lists it at once, so only a device shows this): several "Ready to watch"
 * clips show the summary, whether one arrives while the others show or they arrive together. The deterministic check of
 * the same decision, over a service that always lists late, is :core:notifications' `ClipsReadySummaryTest`.
 */
@HiltAndroidTest
class NotificationSummaryTest {
  @get:Rule
  val hilt = HiltAndroidRule(this)

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var notifier: Notifier

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val manager = context.getSystemService(NotificationManager::class.java)

  private val clipA = JobId("20261004-133519-a77c")
  private val clipB = JobId("20261005-101010-b88d")
  private val clipC = JobId("20261006-090807-c99e")

  @Before
  fun setUp() {
    hilt.inject()
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
    runBlocking { settings.update { it.copy(notifyReady = true) } }
    // A cancel is enqueued like a post: start only once no notification of an earlier test is listed.
    manager.cancelAll()
    awaitListed("no notification of an earlier test") { ours().isEmpty() }
  }

  @After
  fun tearDown() = manager.cancelAll()

  @Test
  fun aSecondClipReadyWhileTheFirstShowsBringsTheSummary() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    awaitListed("clip A") { readyTags() == setOf(tag(clipA)) }
    assertThat(summary()).isNull()

    notifier.clipReady(clipB, "At the station", 9)

    awaitListed("clips A and B") { readyTags() == setOf(tag(clipA), tag(clipB)) }
    awaitListed("the summary of two") { summary()?.text() == "2 clips ready to watch" }
    val summary = checkNotNull(summary())
    assertThat(summary.group).isEqualTo(AndroidNotifier.GROUP_CLIPS_READY)
    assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
    assertThat(summary.lines()).containsExactly("Ready to watch: Coffee with Ana", "Ready to watch: At the station")
  }

  @Test
  fun clipsReadyTogetherBringTheSummaryOfAll() {
    // One sync pass can find several clips ready: each post is decided before the service lists the one before it.
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    notifier.clipReady(clipB, "At the station", 9)
    notifier.clipReady(clipC, "A long talk", 4)

    awaitListed("clips A, B and C") { readyTags() == setOf(tag(clipA), tag(clipB), tag(clipC)) }
    awaitListed("the summary of three") { summary()?.text() == "3 clips ready to watch" }
    assertThat(checkNotNull(summary()).lines()).containsExactly(
      "Ready to watch: Coffee with Ana",
      "Ready to watch: At the station",
      "Ready to watch: A long talk",
    )
  }

  @Test
  fun aDismissedClipLeavesTheSummary() {
    notifier.clipReady(clipA, "Coffee with Ana", 15)
    awaitListed("clip A") { readyTags() == setOf(tag(clipA)) }
    notifier.clipReady(clipB, "At the station", 9)
    awaitListed("clips A and B") { readyTags() == setOf(tag(clipA), tag(clipB)) }

    manager.cancel(tag(clipA), AndroidNotifier.ID_CLIP_READY) // what a swipe does
    awaitListed("clip B alone") { readyTags() == setOf(tag(clipB)) }
    notifier.clipReady(clipC, "A long talk", 4)

    awaitListed("the summary of B and C") {
      summary()?.lines()?.toSet() == setOf("Ready to watch: At the station", "Ready to watch: A long talk")
    }
    assertThat(checkNotNull(summary()).text()).isEqualTo("2 clips ready to watch")
  }

  /** Waits up to [TIMEOUT_MS] for the notification service to list what [condition] expects. */
  private fun awaitListed(what: String, condition: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + TIMEOUT_MS
    var met = condition()
    while (!met && SystemClock.uptimeMillis() < end) {
      SystemClock.sleep(POLL_MS)
      met = condition()
    }
    assertWithMessage("listed within $TIMEOUT_MS ms: $what; listed: ${ours().map { it.tag }}").that(met).isTrue()
  }

  private fun ours(): List<StatusBarNotification> = manager.activeNotifications
    .filter { it.tag?.startsWith(AndroidNotifier.TAG_CLIP) == true || it.tag == AndroidNotifier.TAG_SUMMARY }

  private fun readyTags(): Set<String> =
    ours().filter { it.tag.startsWith(AndroidNotifier.TAG_CLIP) }.map { it.tag }.toSet()

  private fun summary(): Notification? = ours()
    .singleOrNull { it.tag == AndroidNotifier.TAG_SUMMARY && it.id == AndroidNotifier.ID_CLIPS_READY_SUMMARY }
    ?.notification

  private fun tag(clip: JobId) = AndroidNotifier.TAG_CLIP + clip.value

  private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  private fun Notification.lines(): List<String> =
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }

  private companion object {
    const val TIMEOUT_MS = 5_000L
    const val POLL_MS = 50L
  }
}
