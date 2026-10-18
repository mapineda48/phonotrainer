package com.mapineda48.phonotrainer.core.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The example row (design 9.25; WP-20): the word marked by its change, the dictionary form and what was said, the clip
 * and the time, the play circle that becomes "Stop" while it plays, the lesson as the row's action, a change across a
 * boundary showing both words, and the accessibility audit across design 11's matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class OccurrenceRowTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val water = PreviewData.occurrence.copy(wordText = "water?", startMs = 45_000)

  private fun unmerged(tag: String) = compose.onNode(hasTestTag(tag), useUnmergedTree = true)

  @Test
  fun theRowShowsTheWordItsFormsAndWhereItIs() =
    screen.checkEach(content = { OccurrenceRow(OccurrenceRowState(water), onPlay = {}, onOpenLesson = {}) }) {
      compose.onNode(hasText("water") and hasAnyAncestor(hasTestTag(OccurrenceTags.WORDS)), useUnmergedTree = true).assertExists()
      // The dictionary form loses its stress marks to compare with what was said (web `stripStress`).
      unmerged(OccurrenceTags.FORMS).assertTextEquals("/wɔtɚ/ → [ˈwɔɾɚ]")
      unmerged(OccurrenceTags.META).assertTextEquals("A walk in the park · 0:45")
      compose.onNodeWithTag(OccurrenceTags.PLAY).assertContentDescriptionEquals("Play “water”")
    }

  @Test
  fun playingTurnsTheCircleIntoStopAndTheActionsReachTheCaller() {
    var playing by mutableStateOf(false)
    val calls = mutableListOf<String>()
    screen.setContent(ScreenVariant(fontScale = 2f)) {
      OccurrenceRow(OccurrenceRowState(water, playing), onPlay = { calls += "play" }, onOpenLesson = { calls += "lesson" })
    }
    compose.onNodeWithTag(OccurrenceTags.PLAY).performClick()
    playing = true
    compose.waitForIdle()
    compose.onNodeWithTag(OccurrenceTags.PLAY).assertContentDescriptionEquals("Stop “water”")
    assertThat(compose.onNodeWithTag(OccurrenceTags.PLAY).fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo("Playing")
    compose.onNodeWithTag(OccurrenceTags.LESSON).performClick()
    assertThat(calls).containsExactly("play", "lesson").inOrder()
    val lesson = compose.onNodeWithTag(OccurrenceTags.LESSON).fetchSemanticsNode()
    assertThat(lesson.config[SemanticsActions.OnClick].label).isEqualTo("Open in its lesson")
  }

  @Test
  fun aChangeAcrossTheBoundaryShowsBothWordsAndPlaysIntoTheNext() {
    val didYou = water.copy(wordText = "Did", dictIpa = "dɪd", realizedIpa = "dɪdʒ", family = Family.BOUNDARY, crossesBoundary = true, nextWordText = "you")
    screen.setContent { OccurrenceRow(OccurrenceRowState(didYou), onPlay = {}, onOpenLesson = {}) }
    compose.onNode(hasText("you") and hasAnyAncestor(hasTestTag(OccurrenceTags.WORDS)), useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(OccurrenceTags.PLAY).assertContentDescriptionEquals("Play “Did you”")
    val span = OccurrenceRowPlayback.span(didYou)
    assertThat(span.endMs - didYou.endMs).isEqualTo(OccurrenceRowPlayback.BOUNDARY_PAD_AFTER_MS)
  }

  @Test
  fun nothingHeardAndNoDictionaryFormAreSaidPlainly() {
    val silent = water.copy(dictIpa = null, realizedIpa = null)
    screen.setContent { OccurrenceRow(OccurrenceRowState(silent), onPlay = {}, onOpenLesson = {}) }
    unmerged(OccurrenceTags.FORMS).assertTextEquals("[∅]")
  }

  @Test
  fun theFormsAreReadWithTheSymbolsNames() {
    screen.setContent { OccurrenceRow(OccurrenceRowState(water), onPlay = {}, onOpenLesson = {}) }
    val forms = unmerged(OccurrenceTags.FORMS).fetchSemanticsNode()
    // Without the shell's reading the symbols have no names, so IpaText leaves the words as they are.
    assertThat(forms.config.getOrNull(SemanticsProperties.ContentDescription)).isNull()
  }
}
