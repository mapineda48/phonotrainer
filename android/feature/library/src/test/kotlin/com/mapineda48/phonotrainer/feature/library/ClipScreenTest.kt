package com.mapineda48.phonotrainer.feature.library

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.ClipTab
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.core.ui.extension.ClipReductionMeters
import com.mapineda48.phonotrainer.core.ui.extension.ClipTranscriptTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of the Clip page (spec F-05 v1-core, design 10.2 P-02) at font scale 2.0 in light and dark with the
 * accessibility audit, and what its controls ask for: the header, the Shorts and Summary tabs, the "On this phone"
 * block and its "Remove from this phone?" confirmation (F-05-AC11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h5000dp")
class ClipScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val asked = mutableListOf<String>()
  private val actions = ClipActions(
    onBack = { asked += "back" },
    onSeeAll = { asked += "seeAll" },
    onWatch = { asked += "watch" },
    onFromStart = { asked += "fromStart" },
    onShort = { asked += "short:${it.firstSegment}" },
    onChange = { asked += "change:${it.value}" },
    onTab = { asked += "tab:$it" },
    onKeep = { asked += "keep:$it" },
    onDownload = { asked += "download" },
    onDownloadVideo = { asked += "downloadVideo" },
    onRemoveVideo = { asked += "removeVideo" },
    onRemoveFromPhone = { asked += "remove" },
    onOpenLink = { asked += "link:$it" },
    onOpenRequest = { asked += "openRequest:${it.value}" },
  )

  private fun text(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
    .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

  private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

  private fun SemanticsNodeInteraction.description(): String =
    fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

  private fun assertInside(tag: String, words: String) {
    compose.onNode(hasText(words) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).assertIsDisplayed()
  }

  @Test
  fun everyStateRendersAndPassesTheAudit() {
    val states = listOf(
      "shorts" to LibrarySamples.page,
      "summary" to LibrarySamples.page.copy(tab = ClipTab.SUMMARY),
      "shorts loading" to LibrarySamples.page.copy(shorts = ShortsState.Loading),
      "bringing" to LibrarySamples.bringing,
      "not here, offline" to LibrarySamples.notHereOffline,
      "not here, offline, summary" to LibrarySamples.notHereOffline.copy(tab = ClipTab.SUMMARY),
      "no audio" to LibrarySamples.noAudio,
      "processing" to LibrarySamples.processing,
      "deleted while open" to LibrarySamples.deleted,
      "deleted while open, summary" to LibrarySamples.deleted.copy(tab = ClipTab.SUMMARY),
      "deleted, not here" to LibrarySamples.deletedNotHere,
      "deleted while bringing" to LibrarySamples.deletedBringing,
      "deleted while processing" to LibrarySamples.deletedProcessing,
      "gone" to ClipUiState(presence = ClipPresence.Gone),
      "loading" to ClipUiState(),
    )
    val expected = mapOf(
      "shorts" to LibraryTags.short(LibrarySamples.shorts[0].id),
      "summary" to LibraryTags.ON_PHONE,
      "shorts loading" to LibraryTags.SHORTS_LOADING,
      "bringing" to LibraryTags.CLIP_PLAY_NOTE,
      "not here, offline" to LibraryTags.SHORTS_NOT_HERE,
      "not here, offline, summary" to LibraryTags.ON_PHONE,
      "no audio" to LibraryTags.clipNotice(ClipNotice.NO_AUDIO),
      "processing" to LibraryTags.CLIP_PROCESSING,
      "deleted while open" to LibraryTags.CLIP_DELETED_BANNER,
      "deleted while open, summary" to LibraryTags.ON_PHONE,
      "deleted, not here" to LibraryTags.SHORTS_NOT_HERE,
      "deleted while bringing" to LibraryTags.CLIP_DELETED_BANNER,
      "deleted while processing" to LibraryTags.CLIP_DELETED_BANNER,
      "gone" to LibraryTags.CLIP_GONE,
      "loading" to UiTags.LOADING,
    )
    var current by mutableStateOf(states.first().second)
    screen.setContent(ScreenVariant.REQUIRED.first()) { ClipScreen(current, actions) }
    states.forEach { (name, state) ->
      current = state
      ScreenVariant.REQUIRED.forEach { variant ->
        screen.show(variant)
        try {
          assertThat(exists(expected.getValue(name))).isTrue()
          screen.audit()
        } catch (failure: Throwable) {
          throw AssertionError("In state \"$name\" at $variant: ${failure.message}", failure)
        }
      }
    }
  }

  @Test
  fun theHeaderSaysWhatTheClipIsAndResumesWhereTheLearnerWas() {
    screen.setContent { ClipScreen(LibrarySamples.page, actions) }
    assertThat(text(LibraryTags.CLIP_TITLE)).isEqualTo("Coffee shop small talk")
    assertThat(text(LibraryTags.CLIP_META)).isEqualTo("4 min 21 s · 87 phrases · 691 words · Video · added today")
    compose.onNode(hasText("On this phone")).assertIsDisplayed()
    compose.onNode(hasText("Video on this phone")).assertIsDisplayed()
    compose.onNode(hasText("Kept")).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.CLIP_WATCH) and hasText("Watch · short 4")).performClick()
    compose.onNode(hasTestTag(LibraryTags.CLIP_FROM_START) and hasText("From the start")).performClick()
    compose.onNodeWithTag(LibraryTags.CLIP_BACK).performClick()
    assertThat(asked).containsExactly("watch", "fromStart", "back").inOrder()
    // v1-core: no "On your computer" block and no deletion from the computer anywhere on the page.
    assertThat(compose.onAllNodes(hasText("On your computer")).fetchSemanticsNodes()).isEmpty()
    assertThat(compose.onAllNodes(hasText("Delete from your computer…")).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun withNoResumeShortWatchSaysWatch() {
    screen.setContent { ClipScreen(LibrarySamples.page.copy(resume = null), actions) }
    compose.onNode(hasTestTag(LibraryTags.CLIP_WATCH) and hasText("Watch")).assertIsDisplayed()
  }

  @Test
  fun theShortsTabListsEachShortAndOpensTheFeedThere() {
    screen.setContent { ClipScreen(LibrarySamples.page, actions) }
    compose.onNodeWithTag(LibraryTags.tab(ClipTab.SHORTS)).assertIsSelected()
    val first = compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[0].id))
    assertThat(first.description()).isEqualTo("Short 1, Linking in “pick it”, 0:00–0:17 · 9 marked words, Seen")
    assertThat(compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[3].id)).description())
      .isEqualTo("Short 4, Palatalization in “did you”, 0:42–0:58 · 11 marked words, Next")
    assertThat(compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[4].id)).description())
      .isEqualTo("Short 5, No labelled change here: listen to the rhythm., 0:58–1:06 · 0 marked words")
    // F-05-AC5: a short opens the feed In order at that short.
    compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[2].id)).performClick()
    assertThat(asked).containsExactly("short:4")
  }

  @Test
  fun aClipNotOnThisPhoneOffersItsDownloadWithTheWordsOfTheState() {
    var current by mutableStateOf(LibrarySamples.notHereOffline)
    screen.setContent { ClipScreen(current, actions) }
    assertInside(
      LibraryTags.clipNotice(ClipNotice.NOT_HERE),
      "This clip isn't on this phone yet. It downloads the next time your computer is reachable.",
    )
    assertInside(LibraryTags.SHORTS_NOT_HERE, "The transcript comes with the clip.")
    assertThat(exists(LibraryTags.CLIP_WATCH)).isFalse()
    // F-05-AC4.
    compose.onNode(hasTestTag(LibraryTags.CLIP_DOWNLOAD) and hasText("Download when connected")).performClick()

    current = LibrarySamples.notHereOffline.copy(connected = true)
    compose.waitForIdle()
    assertInside(LibraryTags.clipNotice(ClipNotice.NOT_HERE), "This clip isn't on this phone.")
    compose.onNode(hasTestTag(LibraryTags.CLIP_DOWNLOAD) and hasText("Download to this phone")).performClick()

    current = LibrarySamples.notHereOffline.copy(wanted = true)
    compose.waitForIdle()
    assertInside(LibraryTags.clipNotice(ClipNotice.WANTED), "It downloads the next time your computer is reachable.")
    assertThat(exists(LibraryTags.CLIP_DOWNLOAD)).isFalse()
    assertThat(asked).containsExactly("download", "download").inOrder()
  }

  @Test
  fun whileTheAudioComesWatchWaitsAndSaysWhy() {
    screen.setContent { ClipScreen(LibrarySamples.bringing, actions) }
    compose.onNodeWithTag(LibraryTags.CLIP_WATCH).assertIsNotEnabled()
    assertThat(text(LibraryTags.CLIP_PLAY_NOTE)).isEqualTo("Getting the audio… 40 %")
    assertThat(exists(LibraryTags.CLIP_FROM_START)).isFalse()
    compose.onNode(hasText("Older rules: re-analyze on your computer to update")).assertDoesNotExist()
    assertInside(LibraryTags.clipNotice(ClipNotice.OLDER_RULES), "Analyzed with older rules. Re-analyze it on your computer to update.")
  }

  @Test
  fun everyPartialStateHasItsSentence() {
    var current by mutableStateOf(LibrarySamples.noAudio)
    screen.setContent { ClipScreen(current, actions) }
    assertInside(LibraryTags.clipNotice(ClipNotice.NO_AUDIO), "This clip came without its audio: you can read it, but not listen to it.")
    assertThat(exists(LibraryTags.CLIP_WATCH)).isFalse()

    val cases = listOf(
      LibrarySamples.coffee.copy(video = VideoAvailability.NotAvailableFromComputer) to
        (ClipNotice.VIDEO_NOT_AVAILABLE to "Video not available from your computer. Listening and study work."),
      LibrarySamples.coffee.copy(video = VideoAvailability.CannotPlayOnThisPhone) to
        (ClipNotice.VIDEO_CANNOT_PLAY to "This video can't play on this phone. Listening still works."),
      LibrarySamples.coffee.copy(availability = ClipAvailability.NeedsDownloadingAgain) to
        (ClipNotice.NEEDS_AGAIN to "This clip needs downloading again."),
      LibrarySamples.market to (ClipNotice.PAUSED to "Download paused: storage budget reached"),
    )
    cases.forEach { (clip, notice) ->
      current = LibrarySamples.page.copy(presence = ClipPresence.Known(LibrarySamples.detail(clip)))
      compose.waitForIdle()
      assertInside(LibraryTags.clipNotice(notice.first), notice.second)
    }

    current = LibrarySamples.deleted
    compose.waitForIdle()
    assertInside(LibraryTags.CLIP_DELETED_BANNER, "This clip was deleted on your computer. It stays until you leave this page.")

    current = ClipUiState(presence = ClipPresence.Gone)
    compose.waitForIdle()
    compose.onNode(hasText("That clip is no longer on this phone.")).assertIsDisplayed()
    compose.onNodeWithTag("${UiTags.EMPTY_ACTION}.0").performClick()
    assertThat(asked).containsExactly("seeAll")
  }

  @Test
  fun theBannerFollowsTheClipsDetail() {
    // CR-WP08-4: the page reads ClipDetail.deletedOnComputer, the flag the sync engine sets while the page is open.
    var current by mutableStateOf(LibrarySamples.page)
    screen.setContent { ClipScreen(current, actions) }
    assertThat(exists(LibraryTags.CLIP_DELETED_BANNER)).isFalse()
    current = LibrarySamples.deleted
    compose.waitForIdle()
    assertInside(LibraryTags.CLIP_DELETED_BANNER, "This clip was deleted on your computer. It stays until you leave this page.")
    // A polite live region: TalkBack says it when it appears.
    assertThat(compose.onNodeWithTag(LibraryTags.CLIP_DELETED_BANNER).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
      .isEqualTo(LiveRegionMode.Polite)
    // It stays: the clip's title and what it is are still there.
    assertThat(text(LibraryTags.CLIP_TITLE)).isEqualTo("Coffee shop small talk")
    assertThat(text(LibraryTags.CLIP_META)).isEqualTo("4 min 21 s · 87 phrases · 691 words · Video · added today")
  }

  @Test
  @Config(qualifiers = "w412dp-h800dp")
  fun theBannerShowsWhereverTheLearnerHasScrolled() {
    // Design 9.23: a persistent condition's banner sits at the top, outside the scrolling page. The learner who reads
    // "On this phone" when the deletion arrives sees it there, as its actions go.
    var current by mutableStateOf(LibrarySamples.page.copy(tab = ClipTab.SUMMARY))
    screen.setContent { ClipScreen(current, actions) }
    compose.onNodeWithTag(LibraryTags.CLIP_LIST).performScrollToNode(hasTestTag(LibraryTags.REMOVE_FROM_PHONE))
    compose.onNodeWithTag(LibraryTags.REMOVE_FROM_PHONE).assertIsDisplayed()
    assertThat(exists(LibraryTags.CLIP_TITLE)).isFalse()

    current = LibrarySamples.deleted.copy(tab = ClipTab.SUMMARY)
    compose.waitForIdle()
    compose.onNodeWithTag(LibraryTags.CLIP_DELETED_BANNER).assertIsDisplayed()
    compose.onNodeWithTag(LibraryTags.ON_PHONE).assertIsDisplayed()
    assertThat(exists(LibraryTags.REMOVE_FROM_PHONE)).isFalse()
    compose.onNodeWithTag(LibraryTags.CLIP_LIST).performScrollToNode(hasTestTag(LibraryTags.CLIP_TITLE))
    compose.onNodeWithTag(LibraryTags.CLIP_DELETED_BANNER).assertIsDisplayed()
    // The Back button stays reachable under the banner.
    compose.onNodeWithTag(LibraryTags.CLIP_BACK).assertIsDisplayed().performClick()
    assertThat(asked).containsExactly("back")
  }

  @Test
  fun aClipDeletedOnTheComputerNoLongerOpensTheFeed() {
    // The feed leaves out a clip deleted on the computer at once (spec F-06 States; the corpus' playable clips), so
    // "Watch", "From the start", a short and a change would open it empty: they are not offered, and the rows stay as
    // text.
    var current by mutableStateOf(LibrarySamples.deleted)
    screen.setContent { ClipScreen(current, actions) }
    assertThat(exists(LibraryTags.CLIP_WATCH)).isFalse()
    assertThat(exists(LibraryTags.CLIP_FROM_START)).isFalse()
    val short = compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[2].id))
    assertThat(short.description()).isEqualTo("Short 3, Flapping in “better”, 0:36–0:42 · 4 marked words, Seen")
    short.assertHasNoClickAction().performClick()
    assertThat(compose.onNodeWithTag(LibraryTags.short(LibrarySamples.shorts[2].id)).fetchSemanticsNode().config.contains(SemanticsProperties.Role))
      .isFalse()

    current = LibrarySamples.deleted.copy(tab = ClipTab.SUMMARY)
    compose.waitForIdle()
    val change = compose.onNodeWithTag(LibraryTags.change(ChangeLabel("flapping")))
    assertThat(change.description()).isEqualTo("Flapping, 12 in this clip, Safe to produce")
    change.assertHasNoClickAction().performClick()
    assertThat(asked).isEmpty()
  }

  @Test
  fun theHeaderOfAClipDeletedOnTheComputerPromisesNothing() {
    // Spec 7.9: it goes when the learner leaves, kept or not, and its downloads stop for good (the planner leaves it).
    var current by mutableStateOf(LibrarySamples.page)
    screen.setContent { ClipScreen(current, actions) }
    compose.onNode(hasText("Kept")).assertIsDisplayed()
    current = LibrarySamples.deleted
    compose.waitForIdle()
    compose.onNode(hasText("Kept")).assertDoesNotExist()
    compose.onNode(hasText("On this phone")).assertIsDisplayed()
    compose.onNode(hasText("Video on this phone")).assertIsDisplayed()

    current = LibrarySamples.bringing
    compose.waitForIdle()
    compose.onNode(hasText("Bringing to this phone 30 %")).assertIsDisplayed()
    current = LibrarySamples.deletedBringing
    compose.waitForIdle()
    compose.onNode(hasText("Bringing to this phone 30 %")).assertDoesNotExist()
    assertThat(exists(LibraryTags.CLIP_WATCH)).isFalse()
    assertThat(exists(LibraryTags.CLIP_PLAY_NOTE)).isFalse()
    compose.onNode(hasText("Getting the audio… 40 %")).assertDoesNotExist()
    compose.onNode(hasText("Video not on this phone")).assertIsDisplayed()

    current = LibrarySamples.deletedProcessing
    compose.waitForIdle()
    compose.onNode(hasText("Getting ready on your computer")).assertDoesNotExist()
    compose.onNode(hasText("Video getting ready on your computer")).assertDoesNotExist()

    val downloading = LibrarySamples.detail(LibrarySamples.coffee.copy(video = VideoAvailability.Downloading(60)))
    current = LibrarySamples.page.copy(presence = ClipPresence.Known(downloading))
    compose.waitForIdle()
    compose.onNode(hasText("Video downloading 60 %")).assertIsDisplayed()
    current = LibrarySamples.page.copy(presence = ClipPresence.Known(downloading.copy(deletedOnComputer = true)))
    compose.waitForIdle()
    compose.onNode(hasText("Video downloading 60 %")).assertDoesNotExist()

    // "Listening still works" no longer holds once the feed has left it out.
    val cannotPlay = LibrarySamples.detail(LibrarySamples.coffee.copy(video = VideoAvailability.CannotPlayOnThisPhone))
    current = LibrarySamples.page.copy(presence = ClipPresence.Known(cannotPlay.copy(deletedOnComputer = true)))
    compose.waitForIdle()
    assertThat(exists(LibraryTags.clipNotice(ClipNotice.VIDEO_CANNOT_PLAY))).isFalse()
    compose.onNode(hasText("This video can't play on this phone")).assertIsDisplayed()
    assertThat(asked).isEmpty()
  }

  @Test
  fun aClipDeletedOnTheComputerOffersNothingThatNeedsIt() {
    // Spec F-05 States and 7.9: it stays until the learner leaves the page, then goes, kept or not, and never comes
    // again; "Remove from this phone?" would say it stays on the computer and can be downloaded again.
    var current by mutableStateOf(LibrarySamples.deleted.copy(tab = ClipTab.SUMMARY))
    screen.setContent { ClipScreen(current, actions) }
    compose.onNode(hasTestTag(LibraryTags.SIZE_PACK) and hasText("2.1 MB")).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.SIZE_VIDEO) and hasText("17 MB")).assertIsDisplayed()
    listOf(LibraryTags.KEEP, LibraryTags.DOWNLOAD_VIDEO, LibraryTags.REMOVE_VIDEO, LibraryTags.REMOVE_FROM_PHONE).forEach {
      assertThat(exists(it)).isFalse()
    }
    // What it is and its changes stay readable.
    assertInside(LibraryTags.ABOUT, "TIMIT-61")
    compose.onNodeWithTag(LibraryTags.change(ChangeLabel("flapping"))).assertIsDisplayed()

    // Not on this phone: no download offered or promised, and its Shorts tab says nothing is here.
    current = LibrarySamples.deletedNotHere
    compose.waitForIdle()
    assertInside(LibraryTags.CLIP_DELETED_BANNER, "This clip was deleted on your computer. It stays until you leave this page.")
    assertThat(exists(LibraryTags.clipNotice(ClipNotice.NOT_HERE))).isFalse()
    assertThat(exists(LibraryTags.CLIP_DOWNLOAD)).isFalse()
    assertInside(LibraryTags.SHORTS_NOT_HERE, "Nothing of this clip is on this phone.")
    compose.onNode(hasText("The transcript comes with the clip.")).assertDoesNotExist()

    // Deleted while it was still analyzed: it never turns into the clip.
    current = LibrarySamples.deletedProcessing
    compose.waitForIdle()
    assertInside(LibraryTags.CLIP_DELETED_BANNER, "This clip was deleted on your computer. It stays until you leave this page.")
    assertThat(exists(LibraryTags.CLIP_PROCESSING)).isFalse()
    compose.onNode(hasText("This page turns into the clip by itself when it's ready.")).assertDoesNotExist()
    assertThat(asked).isEmpty()
  }

  @Test
  fun aClipStillAnalyzedShowsItsRequestAndNoTabs() {
    screen.setContent { ClipScreen(LibrarySamples.processing, actions) }
    assertInside(LibraryTags.CLIP_PROCESSING, "Getting ready on your computer")
    assertInside(LibraryTags.CLIP_PROCESSING, "How to order coffee like a local")
    assertThat(exists(LibraryTags.tab(ClipTab.SHORTS))).isFalse()
    compose.onNode(hasTestTag("ui.request.open") and hasAnyAncestor(hasTestTag(LibraryTags.CLIP_PROCESSING))).performClick()
    assertThat(asked).containsExactly("openRequest:${LibrarySamples.analyzing.id.value}")
  }

  @Test
  fun inV1CoreThereAreShortsAndSummaryOnly() {
    screen.setContent { ClipScreen(LibrarySamples.page.copy(tab = ClipTab.TRANSCRIPT), actions) }
    assertThat(exists(LibraryTags.tab(ClipTab.TRANSCRIPT))).isFalse()
    compose.onNodeWithTag(LibraryTags.tab(ClipTab.SHORTS)).assertIsSelected()
    compose.onNodeWithTag(LibraryTags.tab(ClipTab.SUMMARY)).performClick()
    assertThat(asked).containsExactly("tab:SUMMARY")
  }

  @Test
  fun boundSlotsAddTheTranscriptTabAndTheMeters() {
    val transcript = object : ClipTranscriptTab {
      @Composable
      override fun Tab(jobId: JobId, onOpenWord: (WordRef) -> Unit, onOpenShort: (Int) -> Unit) {
        Text("Transcript of ${jobId.value}")
      }
    }
    val meters = object : ClipReductionMeters {
      @Composable
      override fun Meters(jobId: JobId, modifier: Modifier) {
        Text("Meters of ${jobId.value}", modifier)
      }
    }
    var current by mutableStateOf(LibrarySamples.page.copy(tab = ClipTab.TRANSCRIPT))
    screen.setContent { ClipScreen(current, actions, slots = ClipSlots(transcript, meters)) }
    compose.onNode(hasText("Transcript of ${LibrarySamples.coffee.id.value}")).assertIsDisplayed()
    current = current.copy(tab = ClipTab.SUMMARY)
    compose.waitForIdle()
    compose.onNode(hasText("Meters of ${LibrarySamples.coffee.id.value}")).assertIsDisplayed()
  }

  @Test
  fun theSummaryListsTheChangesAboutAndWhatIsOnThisPhone() {
    screen.setContent { ClipScreen(LibrarySamples.page.copy(tab = ClipTab.SUMMARY, speechModel = "faster-whisper small (int8)"), actions) }
    assertThat(compose.onNodeWithTag(LibraryTags.change(ChangeLabel("flapping"))).description())
      .isEqualTo("Flapping, 12 in this clip, Safe to produce")
    assertThat(compose.onNodeWithTag(LibraryTags.change(ChangeLabel("palatalization"))).description())
      .isEqualTo("Palatalization, 3 in this clip, Recognize only")
    // F-05-AC1: the row opens the feed in that change, limited to this clip.
    compose.onNodeWithTag(LibraryTags.change(ChangeLabel("flapping"))).performClick()

    listOf("TIMIT-61", "faster-whisper small (int8)", "On", "3").forEach { assertInside(LibraryTags.ABOUT, it) }
    compose.onNode(hasTestTag(LibraryTags.SOURCE_LINK) and hasText("https://www.youtube.com/watch?v=example")).performClick()

    compose.onNode(hasTestTag(LibraryTags.SIZE_PACK) and hasText("2.1 MB")).assertIsDisplayed()
    compose.onNode(hasTestTag(LibraryTags.SIZE_VIDEO) and hasText("17 MB")).assertIsDisplayed()
    compose.onNodeWithTag(LibraryTags.KEEP).assertIsOn().performClick()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_VIDEO) and hasText("Remove the video")).performClick()
    assertThat(exists(LibraryTags.DOWNLOAD_VIDEO)).isFalse()
    assertThat(asked).containsExactly(
      "change:flapping", "link:https://www.youtube.com/watch?v=example", "keep:false", "removeVideo",
    ).inOrder()
  }

  @Test
  fun aClipWithoutItsVideoOffersToDownloadIt() {
    val audioHere = LibrarySamples.coffee.copy(kept = false,
      video = VideoAvailability.NotOnThisPhone)
    screen.setContent {
      ClipScreen(LibrarySamples.page.copy(tab = ClipTab.SUMMARY, presence = ClipPresence.Known(LibrarySamples.detail(audioHere))), actions)
    }
    compose.onNodeWithTag(LibraryTags.KEEP).assertIsOff()
    assertThat(exists(LibraryTags.SIZE_VIDEO)).isFalse()
    compose.onNode(hasTestTag(LibraryTags.DOWNLOAD_VIDEO) and hasText("Download the video")).performClick()
    assertThat(exists(LibraryTags.REMOVE_VIDEO)).isFalse()
    assertThat(asked).containsExactly("downloadVideo")
  }

  @Test
  fun removeFromThisPhoneAsksFirstAndIsLocalOnly() {
    // F-05-AC11 on the Clip page.
    screen.setContent(ScreenVariant.REQUIRED.first()) { ClipScreen(LibrarySamples.page.copy(tab = ClipTab.SUMMARY), actions) }
    compose.onNode(hasTestTag(LibraryTags.REMOVE_FROM_PHONE) and hasText("Remove from this phone…")).performClick()
    compose.onNode(isDialog()).assertIsDisplayed()
    assertThat(text(LibraryTags.REMOVE_TITLE)).isEqualTo("Remove “Coffee shop small talk” from this phone?")
    assertThat(text(LibraryTags.REMOVE_BODY)).contains("It stays on your computer")
    compose.onNodeWithTag(LibraryTags.REMOVE_CANCEL).assertIsFocused()
    ScreenVariant.REQUIRED.forEach {
      screen.show(it)
      screen.audit()
    }
    compose.onNodeWithTag(LibraryTags.REMOVE_CANCEL).performClick()
    assertThat(asked).isEmpty()
    compose.onNodeWithTag(LibraryTags.REMOVE_FROM_PHONE).performClick()
    compose.onNode(hasTestTag(LibraryTags.REMOVE_CONFIRM) and hasText("Remove from this phone")).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(asked).containsExactly("remove")
  }

  @Test
  fun anOpenRemoveConfirmationClosesWhenTheComputerDeletesTheClip() {
    // Its body says the clip stays on the computer and can be downloaded again, which no longer holds; and the clip
    // leaves this phone anyway once the learner leaves the page (spec F-05 States, 7.9).
    var current by mutableStateOf(LibrarySamples.page.copy(tab = ClipTab.SUMMARY))
    screen.setContent { ClipScreen(current, actions) }
    compose.onNodeWithTag(LibraryTags.REMOVE_FROM_PHONE).performClick()
    compose.onNode(isDialog()).assertIsDisplayed()

    current = LibrarySamples.deleted.copy(tab = ClipTab.SUMMARY)
    compose.waitForIdle()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(exists(LibraryTags.REMOVE_CONFIRM)).isFalse()
    compose.onNodeWithTag(LibraryTags.CLIP_DELETED_BANNER).assertIsDisplayed()
    assertThat(asked).isEmpty()
  }

  @Test
  fun aClipNotOnThisPhoneHasNothingToRemove() {
    screen.setContent { ClipScreen(LibrarySamples.notHereOffline.copy(tab = ClipTab.SUMMARY), actions) }
    assertInside(LibraryTags.ON_PHONE, "Nothing of this clip is on this phone.")
    assertThat(exists(LibraryTags.REMOVE_FROM_PHONE)).isFalse()
    assertThat(exists(LibraryTags.REMOVE_VIDEO)).isFalse()
  }
}
