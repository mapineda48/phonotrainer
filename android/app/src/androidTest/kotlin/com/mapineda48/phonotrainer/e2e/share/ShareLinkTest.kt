package com.mapineda48.phonotrainer.e2e.share

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.MainActivity
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.component.ShareBridgeTags
import com.mapineda48.phonotrainer.feature.share.R
import com.mapineda48.phonotrainer.feature.share.ShareActivity
import com.mapineda48.phonotrainer.feature.share.ShareTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * Shared links (work-plan WP-23 Proof; spec 8.1, 8.2, 8.4, SH-AC1, SH-AC2, SH-AC7, SH-AC8, F-01-AC6): a real other
 * app shares text with PhonoTrainer through the system, on a fresh install with no computer paired.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ShareLinkTest : ShareAreaTest() {
  @Test
  fun phonoTrainerIsListedForSharedTextAndMedia() {
    val manager = app.packageManager
    fun targets(intent: Intent) = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
      .filter { it.activityInfo.packageName == app.packageName }

    // SH-AC1 (automated part): a generic text/plain share lists "PhonoTrainer"; so do video, audio and multiple ones.
    val text = targets(Intent(Intent.ACTION_SEND).setType("text/plain"))
    assertThat(text.map { it.activityInfo.name }).containsExactly(ShareActivity::class.java.name)
    assertThat(text.single().loadLabel(manager).toString()).isEqualTo("PhonoTrainer")
    assertThat(targets(Intent(Intent.ACTION_SEND).setType("video/mp4"))).hasSize(1)
    assertThat(targets(Intent(Intent.ACTION_SEND).setType("audio/mpeg"))).hasSize(1)
    assertThat(targets(Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*"))).hasSize(1)
    // Nothing else: no image, no other text type.
    assertThat(targets(Intent(Intent.ACTION_SEND).setType("image/png"))).isEmpty()
  }

  @Test
  fun aSharedLinkOpensTheSheetWithoutWelcomeAndWaitsOnThePhone() {
    share.shareText("Great scene https://youtu.be/abc?si=xyz", subject = "Great scene")

    // SH-AC2: the site and the link as it will be sent, tracking parameter removed.
    share.awaitText(words(R.string.share_site_youtube))
    share.awaitText("youtu.be/abc")
    assertThat(compose.onAllNodes(hasTestTag(ShareBridgeTags.LINE)).fetchSemanticsNodes()).hasSize(1)
    // F-01-AC6 / SH-AC11: a cold share is the sheet alone, no Welcome, no Home.
    assertThat(share.activities(Stage.RESUMED).map { it.javaClass }).containsExactly(ShareActivity::class.java)
    assertThat(share.activities(Stage.CREATED).filterIsInstance<MainActivity>()).isEmpty()

    share.addToQueue()
    share.awaitText(words(R.string.share_done_added))
    share.notNowIfAsked()
    share.awaitClosed()
    share.assertSharingAppOnTop()

    // SH-AC12: stored and waiting on this phone (not paired), with the link that will be sent.
    val request = share.awaitRequests { it.size == 1 }.single()
    assertThat(request.kind).isEqualTo(RequestKind.LINK)
    assertThat(request.link).isEqualTo("https://youtu.be/abc")
    assertThat(request.site).isEqualTo(Site.YOUTUBE)
    assertThat(request.state).isEqualTo(RequestState.WAITING)
  }

  @Test
  fun eachSiteHasItsLabelAndOtherWebsitesTheirNote() {
    val shares = listOf(
      "https://www.youtube.com/shorts/Xy12abc?feature=share" to R.string.share_site_shorts,
      "Look https://x.com/someone/status/1234567890?s=20" to R.string.share_site_x,
      "https://example.org/talks/42" to R.string.share_site_other,
    )
    shares.forEach { (text, label) ->
      share.shareText(text)
      share.awaitText(words(label))
      val note = compose.onAllNodes(hasTestTag(ShareTags.SITE_NOTE)).fetchSemanticsNodes()
      assertThat(note).hasSize(if (label == R.string.share_site_other) 1 else 0)
      share.tap(ShareTags.CANCEL)
      share.awaitClosed()
      share.assertSharingAppOnTop()
    }
    // "Cancel" creates nothing.
    assertThat(share.requests()).isEmpty()
  }

  @Test
  fun textWithoutALinkSaysSoAndCreatesNothing() {
    share.shareText("What a great scene!")

    share.awaitText(words(R.string.share_no_link))
    assertThat(compose.onAllNodes(hasTestTag(ShareTags.PRIMARY)).fetchSemanticsNodes()).isEmpty()
    share.tap(ShareTags.CLOSE)
    share.awaitClosed()
    share.assertSharingAppOnTop()
    assertThat(share.requests()).isEmpty()
  }

  @Test
  fun theSameVideoSharedAgainOffersOpenItOrAnalyzeAgain() {
    share.shareText("https://youtu.be/abc")
    share.addToQueue()
    share.notNowIfAsked()
    share.awaitClosed()

    // Another form of the same video (spec 7.7): the duplicate is found when the sheet opens.
    share.shareText("https://www.youtube.com/watch?v=abc&t=42")
    share.awaitText(words(R.string.share_duplicate))
    share.awaitTag(ShareTags.OPEN_IT)
    assertThat(share.requests()).hasSize(1)

    share.tap(ShareTags.ANALYZE_AGAIN)
    share.awaitText(words(R.string.share_done_added))
    share.awaitClosed()
    assertThat(share.awaitRequests { it.size == 2 }.map { it.link }.toSet()).containsExactly("https://youtu.be/abc", "https://www.youtube.com/watch?v=abc")
  }
}
