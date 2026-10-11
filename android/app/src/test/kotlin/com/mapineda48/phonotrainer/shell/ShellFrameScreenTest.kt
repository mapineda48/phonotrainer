package com.mapineda48.phonotrainer.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.UserMessage
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shell's frame on the shared harness (design 9.23, 9.24; decision I-12): the bottom bar with the current tab
 * selected, the banner and its action, the snackbar with "Dismiss", accessible at the required variants; the snackbar
 * leaves after 10 s, or waits for the learner while TalkBack is on.
 */
@RunWith(RobolectricTestRunner::class)
class ShellFrameScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val selected = mutableListOf<Tab>()
  private val done = mutableListOf<Long>()
  private var connectAgain = 0
  private var message by mutableStateOf<UserMessage?>(UserMessage(7, UserMessageKind.WORD_CLIP_GONE))

  private val frame: @Composable () -> Unit = {
    ShellFrame(
      current = Tab.LEARN,
      onSelectTab = { selected += it },
      connectAgain = true,
      onConnectAgain = { connectAgain++ },
      message = message,
      onMessageDone = { id ->
        done += id
        message = null
      },
      announcement = null,
    ) { Box(Modifier.fillMaxSize().testTag(CONTENT)) }
  }

  @Test
  fun theFrameReadsAndWorksAtEveryRequiredVariant() {
    screen.checkEach(ScreenVariant.REQUIRED + ScreenVariant(colors = ColorChoice.COLOR_VISION_FRIENDLY), content = frame) {
      val compose = screen.compose
      Tab.entries.forEach { compose.onNodeWithTag(ShellTags.tab(it)).assertIsDisplayed() }
      compose.onNodeWithTag(ShellTags.tab(Tab.LEARN)).assertIsSelected()
      compose.onNodeWithTag(ShellTags.tab(Tab.HOME)).assertIsNotSelected()
      compose.onNodeWithTag(ShellTags.BANNER_ACTION).assertIsDisplayed()
      compose.onNodeWithTag(ShellTags.SNACKBAR).assertIsDisplayed()
      compose.onNode(hasText("That clip is no longer on your computer or this phone.")).assertIsDisplayed()
      compose.onNodeWithTag(CONTENT).assertIsDisplayed()
    }

    screen.compose.onNodeWithTag(ShellTags.tab(Tab.PRACTICE)).performClick()
    screen.compose.onNodeWithTag(ShellTags.BANNER_ACTION).performClick()
    screen.compose.onNodeWithText("Dismiss").performClick()
    assertThat(selected).containsExactly(Tab.PRACTICE)
    assertThat(connectAgain).isEqualTo(1)
    assertThat(done).containsExactly(7L)
  }

  @Test
  fun theSnackbarLeavesAfterTenSeconds() {
    screen.compose.mainClock.autoAdvance = false
    screen.setContent(content = frame)
    screen.compose.mainClock.advanceTimeBy(9_000)
    assertThat(done).isEmpty()
    screen.compose.mainClock.advanceTimeBy(1_500)
    assertThat(done).containsExactly(7L)
  }

  @Test
  fun withTalkBackTheSnackbarWaitsForTheLearner() {
    screen.compose.mainClock.autoAdvance = false
    screen.setContent(ScreenVariant(talkBackOn = true), content = frame)
    screen.compose.mainClock.advanceTimeBy(60_000)
    assertThat(done).isEmpty()
    screen.compose.onNodeWithTag(ShellTags.SNACKBAR).assertIsDisplayed()
  }

  private companion object {
    const val CONTENT = "test.content"
  }
}
