package com.mapineda48.phonotrainer.e2e.share

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.feature.share.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * When the sheet closes by itself (work-plan WP-23 Proof; spec 8.4 items 8 and 9, D-51, Android review M8, A-08,
 * A-11): 2 s after the request is stored, never with TalkBack on, never while the in-app notification question or the
 * system's dialog is open, and 2 s again once it is answered.
 */
@HiltAndroidTest
class ShareAutoCloseTest : ShareAreaTest() {
  @Before
  fun notificationsNotYetAllowed() {
    // The question is asked only while notifications are not allowed; another area may have granted them, and taking
    // a runtime permission back would end this process.
    val granted = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    assumeTrue("notifications are already allowed on this device", !granted)
  }

  @Test
  fun theQuestionHoldsTheSheetAndTheCountStartsAgainOnceAnswered() {
    share.shareText("https://youtu.be/abc")
    share.addToQueue()
    share.awaitText(words(R.string.share_notify_title))

    share.assertStaysOpen(HELD_MS)
    share.tapText(words(R.string.share_not_now))
    // The full 2 s again, then it goes.
    share.assertStaysOpen(RESTARTED_MS)
    share.awaitClosed(CLOSE_WITHIN_MS)
    share.assertSharingAppOnTop()
  }

  @Test
  fun theSystemDialogHoldsTheSheetUntilItIsAnswered() {
    share.shareText("https://youtu.be/abc")
    share.addToQueue()
    share.tapText(words(R.string.share_notify_me))

    val deny = share.device.wait(Until.findObject(By.res(PERMISSION_DENY)), ShareRobot.TIMEOUT_MS)
    checkNotNull(deny) { "the system's notification dialog did not open" }
    share.assertStaysOpen(HELD_MS)
    deny.click()
    share.assertStaysOpen(RESTARTED_MS)
    share.awaitClosed(CLOSE_WITHIN_MS)
    share.assertSharingAppOnTop()
    assertThat(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)).isEqualTo(PackageManager.PERMISSION_DENIED)
  }

  @Test
  fun withTalkBackTheSheetNeverClosesByItself() {
    assumeTrue("this device does not let the test turn touch exploration on", share.touchExploration(true))
    try {
      share.shareText("https://youtu.be/abc")
      share.addToQueue()
      share.notNowIfAsked()
      share.awaitText(words(R.string.share_done_added))

      share.assertStaysOpen(TALKBACK_WAIT_MS)
      // It goes only when the learner says so: Back closes it at once.
      share.back()
      share.awaitClosed()
      share.assertSharingAppOnTop()
    } finally {
      share.touchExploration(false)
    }
  }

  private companion object {
    /** Far longer than the 2 s the sheet would wait. */
    const val HELD_MS = 4_000L

    /** Just under the 2 s count that starts again once the question is answered. */
    const val RESTARTED_MS = 1_500L
    const val CLOSE_WITHIN_MS = 6_000L
    const val TALKBACK_WAIT_MS = 6_000L
    const val PERMISSION_DENY = "com.android.permissioncontroller:id/permission_deny_button"
  }
}
