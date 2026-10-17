package com.mapineda48.phonotrainer.core.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library row and the shelf card (design 9.18): what TalkBack hears is the row's words, the row opens the Clip
 * page, the long press and "More actions" open the menu, "Watch" is an accessibility action, and every variant passes
 * the audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2000dp")
class ClipRowTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()

  private fun SemanticsNodeInteraction.description(): String =
    fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

  private fun SemanticsNodeInteraction.actionLabels(): List<String> =
    fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

  @Test
  fun aRowReadsAsOneSentenceAndOpensTheClipPage() {
    val row = ClipCardSamples.rows.first()
    screen.checkEach(ScreenVariant.REQUIRED, content = {
      ClipRow(row, onWatch = { asked += "watch" }, onOpen = { asked += "open" }, onMore = { asked += "more" })
    }) {
      assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).description())
        .isEqualTo(ClipCardCopy.rowSentence(row.clip, ApplicationProvider.getApplicationContext<Context>().resources))
    }
    compose.onNodeWithTag(ClipCardTags.OPEN).performClick()
    compose.onNodeWithTag(ClipCardTags.OPEN).performTouchInput { longClick() }
    compose.onNodeWithContentDescription("More actions for “Coffee shop small talk”").assertIsDisplayed().performClick()
    assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).actionLabels()).containsExactly("Watch", "More actions").inOrder()
    val watch = compose.onNodeWithTag(ClipCardTags.OPEN).fetchSemanticsNode().config[SemanticsActions.CustomActions].first()
    watch.action()
    assertThat(asked).containsExactly("open", "more", "more", "watch").inOrder()
  }

  @Test
  fun everyAvailabilityAndTagIsShownAndSpoken() {
    var current by mutableStateOf(ClipCardSamples.rows.first())
    val cases = listOf(
      ClipAvailability.OnThisPhone to "On this phone",
      ClipAvailability.Bringing(45) to "Bringing to this phone 45 %",
      ClipAvailability.NotOnThisPhone to "Not on this phone",
      ClipAvailability.GettingReadyOnComputer to "Getting ready on your computer",
      ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED) to "Download paused: storage budget reached",
      ClipAvailability.NeedsDownloadingAgain to "Needs downloading again",
    )
    screen.setContent(ScreenVariant.REQUIRED.first()) { Column { ClipRow(current, {}, {}, {}) } }
    cases.forEach { (availability, words) ->
      current = current.copy(clip = current.clip.copy(availability = availability))
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).description()).contains(words)
        screen.audit()
      }
    }
    current = current.copy(clip = current.clip.copy(olderRules = true, origin = ClipOrigin.FORGOTTEN_COMPUTER))
    compose.waitForIdle()
    assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).description())
      .contains("Older rules: re-analyze on your computer to update, From a computer you forgot")
    current = current.copy(clip = current.clip.copy(origin = ClipOrigin.OTHER_LIBRARY))
    compose.waitForIdle()
    assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).description()).endsWith("From another library")
  }

  @Test
  fun aShelfCardReadsAsOneSentenceWithItsFamiliesAndOffersWatch() {
    screen.checkEach(ScreenVariant.REQUIRED, content = {
      ClipCard(ClipCardSamples.shelf, onWatch = { asked += "watch" }, onOpen = { asked += "open" }, onMore = { asked += "more" })
    }) {
      assertThat(compose.onNodeWithTag(ClipCardTags.OPEN).description()).isEqualTo(
        "Coffee shop small talk, 4 min 21 s · 15 shorts, Reduction 21, t/d processes 14, Assimilation 3, " +
          "Word boundary 9, On this phone, Kept",
      )
    }
    compose.onNodeWithTag(ClipCardTags.OPEN).performClick()
    compose.onNodeWithTag(ClipCardTags.OPEN).performSemanticsAction(SemanticsActions.OnLongClick)
    compose.onNodeWithTag(ClipCardTags.OPEN).fetchSemanticsNode().config[SemanticsActions.CustomActions]
      .single { it.label == "Watch" }.action()
    assertThat(asked).containsExactly("open", "more", "watch").inOrder()
  }
}
