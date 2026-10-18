package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saved, state by state (work-plan WP-21: F-09 Saved screen, AC1 and the Partial state; design 10.2 P-11, render
 * `24`): the rows with their word, change, last watched and unsave, "Watch saved", an item whose clip is not on this
 * phone, the empty state and loading. Every state is checked at font scale 2.0 in light and dark with the audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class SavedScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = SavedActions(
    onBack = { calls += "back" },
    onWatchSaved = { calls += "watchSaved" },
    onGoToWatch = { calls += "goToWatch" },
    onPlay = { calls += "play:${it.id}" },
    onOpenLesson = { calls += "lesson:${it.id}" },
    onWatchShort = { calls += "watchShort:${it.id}" },
    onOpenClip = { calls += "openClip:${it.id}" },
    onLearn = { calls += "learn:${it.id}" },
    onUnsave = { calls += "unsave:$it" },
  )

  private fun show(state: SavedUiState, check: () -> Unit = {}) =
    screen.checkEach(ScreenVariant.REQUIRED, { SavedScreen(state, actions) }) { check() }

  private fun texts(tag: String): String =
    compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) or hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap(::textsOf).joinToString(" ")

  private fun textsOf(node: SemanticsNode): List<String> =
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
      node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

  private fun scrollTo(tag: String) {
    compose.onNodeWithTag(PracticeTags.SAVED_LIST).performScrollToNode(hasTestTag(tag))
  }

  private fun clickIn(row: Long, tag: String) {
    scrollTo(PracticeTags.savedRow(row))
    compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(PracticeTags.savedRow(row))), useUnmergedTree = true).performClick()
  }

  @Test
  fun theRowsShowTheWordTheChangeWhenLastWatchedAndUnsave() {
    show(PracticePreviewData.savedState) {
      compose.onNodeWithText("Shorts and words you want to hear again. Saved shorts come back in your sets.").assertExists()
      compose.onNodeWithTag(PracticeTags.SAVED_WATCH).assertExists()
      scrollTo(PracticeTags.savedRow(1))
      val first = texts(PracticeTags.savedRow(1))
      assertThat(first).contains("Saved short")
      assertThat(first).contains("water")
      assertThat(first).contains("Flapping")
      compose.onNodeWithTag(PracticeTags.savedLast(1), useUnmergedTree = true).assertTextEquals("Last watched today")
      compose.onNodeWithTag(PracticeTags.savedUnsave(1)).assertIsOn()
      scrollTo(PracticeTags.savedRow(2))
      compose.onNodeWithTag(PracticeTags.savedLast(2), useUnmergedTree = true).assertTextEquals("Last watched on 4 Sep")
      // A saved word has no short to watch from its row: its lesson and "Play" are the example row's.
      compose.onNodeWithTag(PracticeTags.savedWatchShort(2)).assertDoesNotExist()
    }
  }

  @Test
  fun eachControlOfARowCallsItsAction() {
    screen.setContent(ScreenVariant()) { SavedScreen(PracticePreviewData.savedState, actions) }
    compose.onNodeWithTag(PracticeTags.SAVED_WATCH).performClick()
    clickIn(1, OccurrenceTags.PLAY)
    clickIn(1, OccurrenceTags.LESSON)
    clickIn(1, PracticeTags.savedWatchShort(1))
    scrollTo(PracticeTags.savedRow(2))
    compose.onNode(hasClickAction() and hasContentDescription("Flapping", substring = true) and
      hasAnyAncestor(hasTestTag(PracticeTags.savedRow(2)))).performClick()
    clickIn(2, PracticeTags.savedUnsave(2))
    assertThat(calls).containsExactly("watchSaved", "play:1", "lesson:1", "watchShort:1", "learn:2", "unsave:2").inOrder()
  }

  @Test
  fun anItemWhoseClipIsNotOnThisPhoneSaysDownloadTheClip() {
    show(PracticePreviewData.savedState) {
      scrollTo(PracticePreviewData.savedState.rows[2].let { PracticeTags.savedRow(it.id) })
      compose.onNodeWithTag(PracticeTags.savedNotOnPhone(3), useUnmergedTree = true).assertTextEquals("Download the clip to watch it.")
      compose.onNodeWithTag(PracticeTags.savedLast(3), useUnmergedTree = true).assertTextEquals("Not watched since you saved it")
      val row = texts(PracticeTags.savedRow(3))
      assertThat(row).contains("From “A walk in the park”")
      // No example row (nothing to play) and no short to watch until the clip is back.
      compose.onNode(hasTestTag(OccurrenceTags.PLAY) and hasAnyAncestor(hasTestTag(PracticeTags.savedRow(3)))).assertDoesNotExist()
      compose.onNodeWithTag(PracticeTags.savedWatchShort(3)).assertDoesNotExist()
    }
    clickIn(3, PracticeTags.savedOpenClip(3))
    assertThat(calls).containsExactly("openClip:3")
  }

  @Test
  fun aRowSaysHowLongAgoItsShortWasWatched() {
    val rows = listOf(
      PracticePreviewData.savedRow(1, lastWatched = LastWatched.Yesterday),
      PracticePreviewData.savedRow(2, kind = SavedKind.WORD, lastWatched = LastWatched.DaysAgo(1)),
      PracticePreviewData.savedRow(3, kind = SavedKind.WORD, lastWatched = LastWatched.DaysAgo(5)),
    )
    screen.setContent(ScreenVariant()) { SavedScreen(SavedUiState(loading = false, rows = rows), actions) }
    scrollTo(PracticeTags.savedRow(1))
    compose.onNodeWithTag(PracticeTags.savedLast(1), useUnmergedTree = true).assertTextEquals("Last watched yesterday")
    scrollTo(PracticeTags.savedRow(2))
    compose.onNodeWithTag(PracticeTags.savedLast(2), useUnmergedTree = true).assertTextEquals("Last watched 1 day ago")
    scrollTo(PracticeTags.savedRow(3))
    compose.onNodeWithTag(PracticeTags.savedLast(3), useUnmergedTree = true).assertTextEquals("Last watched 5 days ago")
  }

  @Test
  fun nothingSavedYetOffersToGoToWatch() {
    show(SavedUiState(loading = false)) {
      compose.onNodeWithText("Nothing saved yet").assertExists()
      compose.onNodeWithText("Tap “Save” on a short or a word you want to hear again.").assertExists()
      compose.onNodeWithTag(PracticeTags.SAVED_WATCH).assertDoesNotExist()
    }
    compose.onNodeWithText("Go to Watch").performClick()
    compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
    assertThat(calls).containsExactly("goToWatch", "back").inOrder()
  }

  @Test
  fun loadingShowsTheLoadingGate() {
    show(SavedUiState()) {
      compose.onNodeWithTag(PracticeTags.SAVED_LOADING).assertExists()
      compose.onNodeWithTag(PracticeTags.SAVED_EMPTY).assertDoesNotExist()
    }
  }
}
