package com.mapineda48.phonotrainer.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.component.ClipCardTags
import com.mapineda48.phonotrainer.core.ui.component.ConnectionAction
import com.mapineda48.phonotrainer.core.ui.component.ConnectionTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of Home (spec F-03 States, design 9.16) at font scale 2.0 in light and dark with the accessibility audit,
 * the order of its blocks (F-25 list item 3), the Today's set card's sentence and single control (F-03-AC10), the
 * chips in the short unit (D-48), and what every control asks for.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window: the whole page is composed and on screen, so the audit measures every control (a phone scrolls).
@Config(qualifiers = "w412dp-h6000dp")
class HomeScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()
  private val actions = HomeActions(
    onSettings = { asked += "settings" },
    onRefresh = { asked += "refresh" },
    onConnection = { action, request -> asked += "connection:$action:$request" },
    onConnect = { asked += "connect" },
    onLearn = { asked += "learn" },
    onAdd = { asked += "add" },
    onPaste = { asked += "paste" },
    onChooseFile = { asked += "file" },
    onRetry = { asked += "retry" },
    onStartToday = { asked += "today" },
    onAnotherSet = { asked += "another" },
    onKeepWatching = { asked += "keep:${it.jobId.value}:${it.firstSegment}" },
    onWatchClip = { asked += "watch:${it.value}" },
    onOpenClip = { asked += "open:${it.value}" },
    onAllClips = { asked += "library" },
    onChange = { label, shorts -> asked += "change:${label.value}:$shorts" },
    onPractice = { asked += "practice" },
    onSaved = { asked += "saved" },
    onInsights = { asked += "insights" },
  )

  private fun description(tag: String): String =
    compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

  private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

  /** The blocks on screen, top to bottom. */
  private fun blockOrder(): List<String> = HomeTags.BLOCK_NAMES
    .mapNotNull { name -> compose.onAllNodesWithTag(HomeTags.block(name)).fetchSemanticsNodes().singleOrNull()?.let { name to it.boundsInRoot.top } }
    .sortedBy { it.second }
    .map { it.first }

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    val states = listOf(
      "populated" to HomeSamples.populated,
      "first run" to HomeSamples.firstRun,
      "empty" to HomeSamples.empty,
      "loading" to HomeSamples.loading,
      "unreachable" to HomeSamples.unreachable,
      "error" to HomeSamples.error,
      "storage and background" to HomeSamples.limits,
      "mid-set" to HomeSamples.populated.copy(today = HomeSamples.midSet),
      "done for today" to HomeSamples.populated.copy(today = HomeSamples.finished),
      "short set" to HomeSamples.populated.copy(today = HomeSamples.shortSet),
      "nothing new" to HomeSamples.populated.copy(today = TodayCard.NothingNew),
      "pulled while unreachable" to HomeSamples.unreachable.copy(notice = HomeNotice.NOT_REACHABLE),
      "insights" to HomeSamples.populated.copy(insights = "About 7 in 10 words you hear don't sound the way the dictionary writes them."),
    )
    var shown by mutableStateOf(states.first().second)
    screen.setContent(ScreenVariant.REQUIRED.first()) { HomeScreen(shown, actions) }
    states.forEach { (name, state) ->
      shown = state
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          compose.onNodeWithTag(ConnectionTags.STATUS).assertIsDisplayed()
          assertThat(blockOrder()).isEqualTo(HomeRules.blocks(state).map { it.name.lowercase() })
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("$name at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun thePopulatedHomeShowsItsBlocksInOrder() {
    screen.setContent { HomeScreen(HomeSamples.populated, actions) }
    assertThat(blockOrder()).containsExactly("status", "today", "keep_watching", "add", "new", "shelf", "changes", "practice").inOrder()
    // The masthead: the wordmark is a heading named "PhonoTrainer", then the dateline and the lede.
    compose.onNodeWithText("Sunday, 4 October · Home").assertIsDisplayed()
    compose.onNodeWithText("What to study today, and how your clips are doing.").assertIsDisplayed()
  }

  @Test
  fun theTodayCardReadsTheSetsSentenceAndStartIsItsOnlyControl() {
    screen.setContent { HomeScreen(HomeSamples.populated, actions) }
    // F-03-AC10, as part of the card's sentence.
    assertThat(description(HomeTags.TODAY_CARD)).contains(
      "Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; 2 saved shorts back; then 5 questions.",
    )
    val controls = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(HomeTags.block("today")))).fetchSemanticsNodes()
    assertThat(controls).hasSize(1)
    compose.onNodeWithTag(HomeTags.TODAY_START).assertIsDisplayed()
    compose.onNode(hasText("Start") and hasAnyAncestor(hasTestTag(HomeTags.block("today")))).performClick()
    assertThat(asked).containsExactly("today")
  }

  @Test
  fun midSetContinuesAndAFinishedSetOffersAnotherSet() {
    var state by mutableStateOf(HomeSamples.populated.copy(today = HomeSamples.midSet))
    screen.setContent { HomeScreen(state, actions) }
    assertThat(description(HomeTags.TODAY_CARD)).startsWith("Today, 3 of 8 done.")
    compose.onNode(hasText("Continue") and hasAnyAncestor(hasTestTag(HomeTags.block("today")))).performClick()
    state = HomeSamples.populated.copy(today = HomeSamples.finished)
    compose.waitForIdle()
    assertThat(description(HomeTags.TODAY_CARD)).startsWith("Done for today.")
    compose.onNodeWithTag(HomeTags.TODAY_ANOTHER).performClick()
    assertThat(asked).containsExactly("today", "another").inOrder()
    // First run: the card says the set starts with the first clip, and offers nothing to start.
    state = HomeSamples.firstRun
    compose.waitForIdle()
    compose.onNodeWithText("Today's set starts with your first clip.").assertIsDisplayed()
    assertThat(exists(HomeTags.TODAY_START)).isFalse()
  }

  @Test
  fun aChipReadsItsShortCountAndAZeroOpensLearn() {
    screen.setContent { HomeScreen(HomeSamples.populated, actions) }
    // D-48: "Flapping · 34" is ChangeCount.shorts, and the chip asks for the change mode with that many.
    val flapping = HomeTags.chip(ChangeLabel("flapping"))
    compose.onNode(hasText("Flapping · 34") and hasTestTag(flapping)).assertIsDisplayed()
    assertThat(description(flapping)).isEqualTo("Flapping, t/d processes, 34 shorts")
    compose.onNodeWithTag(flapping).performClick()
    val th = HomeTags.chip(ChangeLabel("th_stopping"))
    assertThat(description(th)).isEqualTo("Th-stopping, Assimilation, not in your clips yet")
    compose.onNode(hasText("Not in your clips yet") and hasTestTag(th)).assertIsDisplayed()
    compose.onNodeWithTag(th).performClick()
    assertThat(asked).containsExactly("change:flapping:34", "change:th_stopping:0").inOrder()
  }

  @Test
  fun everyControlAsksForItsDestination() {
    screen.setContent { HomeScreen(HomeSamples.populated, actions) }
    compose.onNodeWithTag(HomeTags.SETTINGS).performClick()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.SYNC_NOW)).performClick()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.SEE_REQUESTS)).performClick()
    compose.onNodeWithTag(HomeTags.KEEP_WATCHING).performClick()
    compose.onNodeWithTag(HomeTags.ADD_PASTE).performClick()
    compose.onNodeWithTag(HomeTags.ADD_FILE).performClick()
    compose.onNodeWithTag(HomeTags.newWatch(HomeSamples.coffee.id)).performClick()
    compose.onNodeWithTag(HomeTags.LINK_LIBRARY).performClick()
    compose.onNodeWithTag(HomeTags.PRACTICE_SAVED).performClick()
    compose.onNodeWithTag(HomeTags.PRACTICE_START).performClick()
    assertThat(asked).containsExactly(
      "settings",
      "connection:SYNC_NOW:null",
      "connection:SEE_REQUESTS:null",
      "keep:${HomeSamples.radio.id.value}:9",
      "paste",
      "file",
      "watch:${HomeSamples.coffee.id.value}",
      "library",
      "saved",
      "practice",
    ).inOrder()
  }

  @Test
  fun aShelfCardOpensItsClipPageAndOffersWatch() {
    screen.setContent { HomeScreen(HomeSamples.populated, actions) }
    val card = compose.onNode(hasTestTag(ClipCardTags.OPEN) and hasAnyAncestor(hasTestTag(HomeTags.shelfCard(HomeSamples.coffee.id))))
    card.performClick()
    // F-03-AC8's play action: "Watch", a custom action of the card (and an item of its menu).
    val watch = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Watch" }
    compose.runOnIdle { watch.action() }
    card.performSemanticsAction(SemanticsActions.OnLongClick)
    compose.onNodeWithTag(HomeTags.SHELF_MENU_WATCH).performClick()
    assertThat(asked).containsExactly(
      "open:${HomeSamples.coffee.id.value}",
      "watch:${HomeSamples.coffee.id.value}",
      "watch:${HomeSamples.coffee.id.value}",
    ).inOrder()
  }

  @Test
  fun theFirstRunTeachesAndTheEmptyStateAndTheErrorOfferTheirActions() {
    var state by mutableStateOf(HomeSamples.firstRun)
    screen.setContent { HomeScreen(state, actions) }
    compose.onNodeWithText("Share a clip from YouTube or X, or pick one on this phone.").assertIsDisplayed()
    compose.onNodeWithTag(HomeTags.TEACH_CONNECT).performClick()
    compose.onNodeWithTag(HomeTags.TEACH_LEARN).performClick()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.CONNECT)).performClick()
    // "All clips" is always there, even before a clip (the Library holds the requests in progress).
    compose.onNodeWithTag(HomeTags.LINK_LIBRARY).assertIsDisplayed()

    state = HomeSamples.empty
    compose.waitForIdle()
    compose.onNodeWithText("Your computer has no clips yet.").assertIsDisplayed()
    compose.onNode(hasTestTag("${UiTags.EMPTY_ACTION}.0") and hasAnyAncestor(hasTestTag(HomeTags.block("empty")))).performClick()

    state = HomeSamples.error
    compose.waitForIdle()
    compose.onNodeWithText("Your clips could not be loaded.").assertIsDisplayed()
    compose.onNodeWithTag(HomeTags.ERROR_RETRY).performClick()
    assertThat(asked).containsExactly("connect", "learn", "connection:CONNECT:null", "add", "retry").inOrder()
  }

  @Test
  fun theStatusCardSaysWhatIsTrueAboutTheComputerInOneSentence() {
    var state by mutableStateOf(HomeSamples.populated)
    screen.setContent { HomeScreen(state, actions) }
    assertThat(description(ConnectionTags.STATUS)).isEqualTo(
      "Connected to Desk PC; Checked 10 s ago, analyzing 1, 2 in line; 2 waiting on this phone; 1 analyzing, 40 %, Hear the phones; 2 in line.",
    )
    state = HomeSamples.unreachable
    compose.waitForIdle()
    assertThat(description(ConnectionTags.STATUS))
      .isEqualTo("Your computer isn't reachable; last seen 3 h ago, your clips work offline; 2 waiting on this phone.")
    state = HomeSamples.limits
    compose.waitForIdle()
    compose.onNodeWithTag(ConnectionTags.action(ConnectionAction.BATTERY_SETTINGS)).performScrollTo().performClick()
    assertThat(asked).containsExactly("connection:BATTERY_SETTINGS:null")
  }

  @Test
  fun pullingWhileUnreachableSaysSoAndTalkBackCanSyncFromThePage() {
    screen.setContent { HomeScreen(HomeSamples.unreachable.copy(notice = HomeNotice.NOT_REACHABLE), actions) }
    compose.onNodeWithText("Your computer isn't reachable right now.").assertIsDisplayed()
    val refresh = compose.onNodeWithTag(HomeTags.REFRESH).fetchSemanticsNode().config[SemanticsActions.CustomActions]
      .single { it.label == "Sync now" }
    compose.runOnIdle { refresh.action() }
    assertThat(asked).containsExactly("refresh")
  }
}
