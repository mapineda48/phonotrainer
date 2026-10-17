package com.mapineda48.phonotrainer.e2e.share

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.share.R
import com.mapineda48.phonotrainer.feature.share.ShareTags
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The sheet stays in the sharing app's task (work-plan WP-23 Proof; architecture 12, Android review H3, security review
 * L-11, SH-AC4, SH-AC11): launched with and without `FLAG_ACTIVITY_NEW_TASK` while PhonoTrainer's own task exists, it
 * never brings that task forward, and when it closes the sharing app is on top again.
 */
@HiltAndroidTest
class ShareTaskTest : ShareAreaTest() {
  @Inject lateinit var settings: SettingsRepository

  private val phono by lazy { PhonoRobot(compose) }

  @After
  fun closeApp() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  private fun launchPhonoTrainerFirst() {
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  private fun assertPhonoTrainerStaysBehind() {
    assertThat(phono.scenario.state).isEqualTo(Lifecycle.State.CREATED)
    assertThat(share.device.currentPackageName).isEqualTo(share.sharingPackage)
  }

  @Test
  fun aShareFromTheSharingAppsTaskReturnsToIt() = cancelAndAdd(newTask = false)

  @Test
  fun aShareInANewTaskStillReturnsToTheSharingApp() = cancelAndAdd(newTask = true)

  private fun cancelAndAdd(newTask: Boolean) {
    launchPhonoTrainerFirst()

    // "Cancel": back to the sharing app at once.
    share.shareText("https://youtu.be/abc", newTask = newTask)
    assertThat(phono.scenario.state).isEqualTo(Lifecycle.State.CREATED)
    share.tap(ShareTags.CANCEL)
    share.awaitClosed()
    share.assertSharingAppOnTop()
    assertPhonoTrainerStaysBehind()

    // "Add to queue": the sheet confirms, closes by itself and the sharing app is visible again (SH-AC4).
    share.shareText("https://youtu.be/def", newTask = newTask)
    share.addToQueue()
    share.awaitText(words(R.string.share_done_added))
    share.notNowIfAsked()
    share.awaitClosed()
    share.assertSharingAppOnTop()
    assertPhonoTrainerStaysBehind()
    assertThat(share.awaitRequests { it.size == 1 }.single().state).isEqualTo(RequestState.WAITING)
  }

  @Test
  fun backClosesTheSheetAtOnceAndCreatesNothing() {
    launchPhonoTrainerFirst()
    share.shareText("https://youtu.be/abc", newTask = true)

    share.back()
    share.awaitClosed()
    share.assertSharingAppOnTop()
    assertPhonoTrainerStaysBehind()
    assertThat(share.requests()).isEmpty()
  }
}
