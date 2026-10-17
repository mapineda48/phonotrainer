package com.mapineda48.phonotrainer.feature.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.FailureContext
import com.mapineda48.phonotrainer.core.ui.component.RequestFailureCopy
import com.mapineda48.phonotrainer.core.ui.component.ShareBridgeTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of the share sheet (spec 8.4.1, design 9.22) at font scale 2.0 in light and dark, each followed by the
 * accessibility audit (architecture 18, 21.1), and what each control asks for.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window: at font scale 2.0 the whole sheet is on screen, so the audit measures every control (a phone scrolls).
@Config(qualifiers = "w412dp-h3000dp")
class ShareSheetScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
  private val asked = mutableListOf<String>()
  private val actions = ShareActions(
    onAudioOnly = { asked += "audioOnly:$it" },
    onSubmit = { asked += "submit" },
    onAnalyzeAgain = { asked += "analyzeAgain" },
    onClose = { asked += "close" },
    onBack = { asked += "back" },
    onOpenApp = { asked += "openApp" },
    onSeeRequests = { asked += "seeRequests" },
    onOpenDuplicate = { asked += "openIt:${it.requestId?.value}" },
    onTryAudioOnly = { asked += "tryAudioOnly" },
    onRemove = { asked += "remove" },
    onKeepAdding = { asked += "keepAdding" },
    onStop = { asked += "stop" },
    onNotNow = { asked += "notNow" },
    onNotifyMe = { asked += "notifyMe" },
  )
  private var current by mutableStateOf(ShareSamples.checking)

  /** Shows each of [states] at each required variant, runs [check] and the audit. */
  private fun each(states: List<Pair<String, ShareUiState>>, check: (String) -> Unit) {
    current = states.first().second
    screen.setContent(ScreenVariant.REQUIRED.first()) { ShareSheet(current, actions) }
    states.forEach { (name, state) ->
      current = state
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          check(name)
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  private fun line(): String =
    compose.onNodeWithTag(ShareBridgeTags.LINE).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

  private fun text(tag: String): String =
    compose.onNodeWithTag(tag).fetchSemanticsNode().config
      .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

  private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    val bridgeless = setOf("no link", "unsupported", "no space", "duplicate")
    each(ShareSamples.all) { name ->
      compose.onNodeWithTag(ShareTags.TITLE).assertTextContains("Analyze with PhonoTrainer")
      assertThat(exists(ShareBridgeTags.BRIDGE)).isEqualTo(name !in bridgeless)
    }
  }

  @Test
  fun theConnectionLineAndThePrimaryFollowTheComputer() {
    val expected = mapOf(
      "checking" to ("Checking your computer…" to "Analyze"),
      "connected" to ("Your computer is connected: the analysis starts now. It goes in line after 2 other clips." to "Analyze"),
      "queued" to (
        "Your computer isn't reachable right now. This waits on your phone and is sent when you're back on your home " +
          "Wi-Fi." to "Add to queue"
        ),
      "not paired" to (
        "This phone isn't connected to your computer yet. It waits on your phone until you connect." to "Add to queue"
        ),
      "needs attention" to (
        "Your computer needs attention (it no longer recognizes this phone). This waits on your phone." to "Add to queue"
        ),
    )
    each(ShareSamples.all.filter { it.first in expected }) { name ->
      val (words, primary) = checkNotNull(expected[name])
      assertThat(line()).isEqualTo(words)
      compose.onNodeWithTag(ShareTags.PRIMARY).assertTextContains(primary).assertIsEnabled()
      compose.onNodeWithTag(ShareTags.LEGAL).assertExists()
      compose.onNodeWithTag(ShareTags.TIP).assertExists()
      compose.onNodeWithTag(ShareTags.AUDIO_ONLY).assertExists()
    }
  }

  @Test
  fun theLinkIsShortenedUntilShowItAllIsTapped() {
    current = ShareSamples.queuedLink
    screen.setContent { ShareSheet(current, actions) }
    compose.onNodeWithText("youtube.com/watch?v=Ab3x…").assertIsDisplayed()

    compose.onNodeWithTag(ShareTags.SHOW_ALL).performClick()
    compose.onNodeWithText("https://www.youtube.com/watch?v=Ab3xYz09Qw1").assertIsDisplayed()
  }

  @Test
  fun anOtherWebsiteLinkCarriesItsNote() {
    each(listOf("other site" to ShareSamples.otherSite)) {
      compose.onNodeWithTag(ShareTags.SITE_NOTE)
        .assertTextContains("PhonoTrainer is made for YouTube and X links. Other websites may not download.", substring = true)
    }
  }

  @Test
  fun aLargeVideoHasAudioOnlyOnAndSaysSendingTakesMinutes() {
    each(listOf("large video" to ShareSamples.largeVideo)) {
      compose.onNodeWithTag(ShareTags.AUDIO_ONLY).assertIsOn()
      compose.onNodeWithTag(ShareTags.LARGE_FILE).assertExists()
      assertThat(exists(ShareTags.LEGAL)).isFalse()
    }
  }

  @Test
  fun aCopyShowsItsProgressAndBackAsksFirst() {
    each(listOf("preparing" to ShareSamples.preparing)) {
      compose.onNodeWithTag(ShareTags.PREPARING).assertExists()
      compose.onNodeWithText("Preparing the file… 40 %").assertExists()
    }
    compose.onNodeWithTag(ShareTags.CANCEL).performClick()
    assertThat(asked).containsExactly("back")
  }

  @Test
  fun stopAddingThisVideoDefaultsToKeepAdding() {
    current = ShareSamples.stop
    screen.setContent { ShareSheet(current, actions) }
    compose.onNodeWithText("Stop adding this video?").assertIsDisplayed()

    compose.onNodeWithText("Keep adding").performClick()
    compose.onNodeWithText("Stop").performClick()
    assertThat(asked).containsExactly("keepAdding", "stop").inOrder()
  }

  @Test
  fun aCopyRefusedForSpaceOffersTryAudioOnlyOnlyWhileTheRequestOffersIt() {
    val failed = ShareSamples.copyFailed.phase as SharePhase.CopyFailed
    val readable = ShareSamples.copyFailed
    val closed = readable.copy(phase = failed.copy(request = failed.request.copy(actions = setOf(RequestAction.REMOVE))))
    each(listOf("copy failed" to readable, "copy failed, source gone" to closed)) { name ->
      val state = if (name == "copy failed") readable else closed
      val request = (state.phase as SharePhase.CopyFailed).request
      val copy = RequestFailureCopy.of(failed.error, FailureContext(RequestKind.FILE, null, request.actions, null), resources)
      compose.onNodeWithTag(ShareTags.NOTICE).assertTextContains(copy.title, substring = true)
      assertThat(exists(ShareTags.TRY_AUDIO_ONLY)).isEqualTo(name == "copy failed")
      compose.onNodeWithTag(ShareTags.REMOVE).assertExists()
    }
    current = readable
    compose.waitForIdle()
    compose.onNodeWithTag(ShareTags.TRY_AUDIO_ONLY).performClick()
    compose.onNodeWithTag(ShareTags.REMOVE).performClick()
    assertThat(asked).containsExactly("tryAudioOnly", "remove").inOrder()
  }

  @Test
  fun afterTheTapTheMessageIsCurrentAndTheAppCanBeOpened() {
    val messages = mapOf(
      "added" to "Added to your queue.",
      "sending" to "Sending to your computer…",
      "sent" to "Sent to your computer.",
    )
    each(ShareSamples.all.filter { it.first in messages }) { name ->
      assertThat(text(ShareTags.DONE)).contains(messages.getValue(name))
      compose.onNodeWithTag(ShareTags.OPEN_APP).assertExists()
      compose.onNodeWithTag(ShareTags.SEE_REQUESTS).assertExists()
      assertThat(exists(ShareTags.PRIMARY)).isFalse()
    }
    compose.onNodeWithTag(ShareTags.OPEN_APP).performClick()
    compose.onNodeWithTag(ShareTags.SEE_REQUESTS).performClick()
    assertThat(asked).containsExactly("openApp", "seeRequests").inOrder()
  }

  @Test
  fun aPartialMultipleShareNamesWhatWasNotAdded() {
    each(listOf("partial" to ShareSamples.partial)) {
      compose.onNodeWithTag(ShareTags.PARTIAL)
        .assertTextContains("2 of 3 added. 1 file type isn't supported: voice.amr.", substring = true)
      compose.onNodeWithTag(ShareTags.SHARED).assertTextContains("3 files · 393 MB", substring = true)
    }
  }

  @Test
  fun emptyAndErrorStatesSayWhyAndOnlyClose() {
    val expected = mapOf(
      "no link" to "There's no link in what you shared.",
      "unsupported" to "This file type can't be analyzed. Supported: MP4, MOV, MKV, WebM, AVI, M4V, MP3, M4A, AAC, WAV, " +
        "FLAC, OGG, Opus.",
    )
    each(ShareSamples.all.filter { it.first in expected }) { name ->
      compose.onNodeWithTag(ShareTags.NOTICE).assertTextContains(expected.getValue(name), substring = true)
      compose.onNodeWithTag(ShareTags.CLOSE).assertExists()
      assertThat(exists(ShareTags.PRIMARY)).isFalse()
      assertThat(exists(ShareTags.TRY_AUDIO_ONLY)).isFalse()
    }
    compose.onNodeWithTag(ShareTags.CLOSE).performClick()
    assertThat(asked).containsExactly("close")
  }

  @Test
  fun aVideoRefusedForSpaceStatesTheSpaceNeededAndOffersAudioOnly() {
    each(listOf("no space" to ShareSamples.noSpace)) {
      compose.onNodeWithTag(ShareTags.NOTICE).assertTextContains(
        "Not enough space on this phone to keep this video until your computer is reachable. Free 859 MB or choose " +
          "Audio only.",
        substring = true,
      )
      compose.onNodeWithTag(ShareTags.TRY_AUDIO_ONLY).assertExists()
    }
  }

  @Test
  fun aDuplicateOffersOpenItOrAnalyzeAgain() {
    each(listOf("duplicate" to ShareSamples.duplicate)) {
      compose.onNodeWithTag(ShareTags.NOTICE).assertTextContains("You already added this link.", substring = true)
    }
    compose.onNodeWithTag(ShareTags.OPEN_IT).performClick()
    compose.onNodeWithTag(ShareTags.ANALYZE_AGAIN).performClick()
    assertThat(asked).containsExactly("openIt:6b1d5d6f-1c7f-4ce7-8a64-2a2c1ccf8d22", "analyzeAgain").inOrder()
  }

  @Test
  fun theNotificationQuestionHasNotNowAndNotifyMe() {
    current = ShareSamples.question
    screen.setContent { ShareSheet(current, actions) }
    compose.onNodeWithText("Get a notification when your clip is ready?").assertIsDisplayed()

    compose.onNodeWithText("Not now").performClick()
    compose.onNodeWithText("Notify me").performClick()
    assertThat(asked).containsExactly("notNow", "notifyMe").inOrder()
  }

  @Test
  fun theConfirmButtonsAskForWhatTheySay() {
    current = ShareSamples.queuedLink
    screen.setContent { ShareSheet(current, actions) }

    compose.onNodeWithTag(ShareTags.AUDIO_ONLY).performScrollTo().performClick()
    compose.onNodeWithTag(ShareTags.PRIMARY).performClick()
    compose.onNodeWithTag(ShareTags.CANCEL).performClick()
    compose.onNodeWithTag(ShareTags.SCRIM).performTouchInput { click(Offset(10f, 10f)) }
    assertThat(asked).containsExactly("audioOnly:true", "submit", "close", "back").inOrder()
  }

  @Test
  fun whileStoringTheButtonsWait() {
    current = ShareSamples.queuedLink.copy(phase = SharePhase.Saving)
    screen.setContent { ShareSheet(current, actions) }

    compose.onNodeWithTag(ShareTags.PRIMARY).assertIsNotEnabled()
    compose.onNodeWithTag(ShareTags.CANCEL).assertIsNotEnabled()
    compose.onAllNodesWithTag(ShareTags.AUDIO_ONLY).assertCountEquals(1)
    compose.onNodeWithTag(ShareTags.AUDIO_ONLY).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
  }
}
