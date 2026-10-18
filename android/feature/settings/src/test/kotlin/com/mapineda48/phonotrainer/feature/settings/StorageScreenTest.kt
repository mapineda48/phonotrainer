package com.mapineda48.phonotrainer.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.testing.screen.PhonoScreenRule
import com.mapineda48.phonotrainer.core.testing.screen.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > Storage (spec F-21 Storage, F-05 item 5 and F-05-AC11; design 9.20, 9.23): the total against the budget,
 * the meter and its table twin, the budget and "Download videos automatically", the clips largest first with their
 * actions, both removal confirmations (words, buttons, "Cancel" focused), the limit and pause notices, the first run,
 * loading and the error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_WINDOW)
class StorageScreenTest {
  @get:Rule
  val screen = PhonoScreenRule()

  private val compose get() = screen.compose
  private val calls = mutableListOf<String>()

  private val actions = StorageActions(
    onBack = { calls += "back" },
    onRetry = { calls += "retry" },
    onBudget = { calls += "budget:$it" },
    onDownloadVideos = { calls += "downloadVideos:$it" },
    onRemoveVideo = { calls += "removeVideo:${it.value}" },
    onRemoveFromPhone = { calls += "removeFromPhone:${it.value}" },
    onKeep = { id, kept -> calls += "keep:${id.value}:$kept" },
    onRemoveAllVideos = { calls += "removeAllVideos" },
    onBatterySettings = { calls += "battery" },
  )

  private fun budget(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(SettingsTags.STORAGE_BUDGET)))

  @Test
  fun theTotalTheMeterAndItsTableSayWhatThisPhoneKeeps() {
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(SettingsSamples.storage, actions) }) {
      val total = compose.textsOf(SettingsTags.STORAGE_TOTAL)
      assertThat(total).contains("1.4 GB of 2 GB")
      assertThat(total).contains("21 GB free on this phone")
      val meter = compose.onNodeWithTag(SettingsTags.STORAGE_METER).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
      assertThat(meter.single()).isEqualTo(
        "1.4 GB used: Clips (text and audio), 120 MB; Videos, 1.3 GB; Waiting to send, 30 MB; Cache, 12 MB",
      )
      assertThat(compose.spokenOf(SettingsTags.storageCategory("clips"))).isEqualTo("Clips (text and audio), 120 MB")
      assertThat(compose.spokenOf(SettingsTags.storageCategory("videos"))).isEqualTo("Videos, 1.3 GB")
      assertThat(compose.spokenOf(SettingsTags.storageCategory("waiting"))).isEqualTo("Waiting to send, 30 MB")
      assertThat(compose.spokenOf(SettingsTags.storageCategory("cache"))).isEqualTo("Cache, 12 MB")
      budget("2 GB").assertIsSelected()
      compose.onNodeWithTag(SettingsTags.STORAGE_DOWNLOAD_VIDEOS).assertIsOn()
      // No limit reached, nothing paused.
      listOf(SettingsTags.STORAGE_FLOOR, SettingsTags.STORAGE_BUDGET_REACHED, SettingsTags.STORAGE_PAUSED, SettingsTags.STORAGE_BACKGROUND)
        .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }
  }

  @Test
  fun theClipsAreListedLargestFirstWithTheirSizesAndActions() {
    // Given smallest first: the page still lists them largest first.
    val reversed = StorageUiState(read = StorageRead.Ready(SettingsSamples.usage.copy(perClip = SettingsSamples.usage.perClip.reversed())))
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(reversed, actions) }) {
      val coffee = compose.textsOf(SettingsTags.storageClip(SettingsSamples.coffee))
      assertThat(coffee).contains("Coffee shop small talk")
      assertThat(coffee).contains("Clip data and audio · 21 MB")
      assertThat(coffee).contains("Video · 640 MB")
      assertThat(coffee).contains("Kept on this phone")
      assertThat(coffee).contains("Stop keeping")
      val market = compose.textsOf(SettingsTags.storageClip(SettingsSamples.market))
      assertThat(market).contains("Clip data and audio · 1.8 MB")
      assertThat(market).doesNotContain("Video ·")
      assertThat(market).contains("Keep on this phone")
      // A clip without a video has no "Remove the video".
      compose.onNodeWithTag(SettingsTags.storageRemoveVideo(SettingsSamples.market)).assertDoesNotExist()
    }
    val order = compose.onAllNodes(hasTestTag(SettingsTags.storageClip(SettingsSamples.coffee)) or
      hasTestTag(SettingsTags.storageClip(SettingsSamples.park)) or hasTestTag(SettingsTags.storageClip(SettingsSamples.market)))
      .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[SemanticsProperties.TestTag] }
    assertThat(order).containsExactly(
      SettingsTags.storageClip(SettingsSamples.coffee),
      SettingsTags.storageClip(SettingsSamples.park),
      SettingsTags.storageClip(SettingsSamples.market),
    ).inOrder()
  }

  @Test
  fun eachControlCallsItsAction() {
    screen.setContent { StorageScreen(SettingsSamples.storage, actions) }
    budget("5 GB").performClick()
    budget("No limit except free space").performClick()
    compose.onNodeWithTag(SettingsTags.STORAGE_DOWNLOAD_VIDEOS).performClick()
    compose.onNodeWithTag(SettingsTags.storageRemoveVideo(SettingsSamples.park)).performClick()
    compose.onNodeWithTag(SettingsTags.storageKeep(SettingsSamples.coffee)).performClick()
    compose.onNodeWithTag(SettingsTags.storageKeep(SettingsSamples.park)).performClick()
    assertThat(calls).containsExactly(
      "budget:${StorageBudget.Limit(5_000_000_000)}",
      "budget:${StorageBudget.FreeSpaceOnly}",
      "downloadVideos:false",
      "removeVideo:${SettingsSamples.park.value}",
      "keep:${SettingsSamples.coffee.value}:false",
      "keep:${SettingsSamples.park.value}:true",
    ).inOrder()
  }

  @Test
  fun removeFromThisPhoneAsksFirstWithTheWordsOfF05() {
    screen.setContent { StorageScreen(SettingsSamples.storage, actions) }
    compose.onNodeWithTag(SettingsTags.storageRemove(SettingsSamples.coffee)).performClick()

    compose.onNode(isDialog()).assertIsDisplayed()
    assertThat(compose.textsOf(SettingsTags.REMOVE_TITLE)).isEqualTo("Remove “Coffee shop small talk” from this phone?")
    assertThat(compose.textsOf(SettingsTags.REMOVE_BODY)).isEqualTo(
      "Its clip data, audio and video are deleted from this phone only. It stays on your computer, and you can " +
        "download it again at any time. Your saved items and progress stay.",
    )
    compose.onNode(hasTestTag(SettingsTags.REMOVE_CANCEL) and hasText("Cancel")).assertIsFocused()
    compose.onNode(hasTestTag(SettingsTags.REMOVE_CONFIRM) and hasText("Remove from this phone")).assertIsDisplayed()
    val pane = compose.onNodeWithTag(SettingsTags.REMOVE_DIALOG).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
    assertThat(pane).isEqualTo("Remove “Coffee shop small talk” from this phone?")
    ScreenVariant.REQUIRED.forEach {
      screen.show(it)
      screen.audit()
    }

    compose.onNodeWithTag(SettingsTags.REMOVE_CANCEL).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(calls).isEmpty()

    compose.onNodeWithTag(SettingsTags.storageRemove(SettingsSamples.coffee)).performClick()
    compose.onNodeWithTag(SettingsTags.REMOVE_CONFIRM).performClick()
    assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    assertThat(calls).containsExactly("removeFromPhone:${SettingsSamples.coffee.value}")
  }

  @Test
  fun removeAllVideosAsksFirstWithCancelFocused() {
    screen.setContent { StorageScreen(SettingsSamples.storage, actions) }
    compose.onNodeWithTag(SettingsTags.STORAGE_REMOVE_ALL).performClick()

    assertThat(compose.textsOf(SettingsTags.REMOVE_ALL_TITLE)).isEqualTo("Remove all videos?")
    assertThat(compose.textsOf(SettingsTags.REMOVE_ALL_BODY)).isEqualTo(
      "Every video is deleted from this phone. Clip data and audio stay, so every clip can still be studied, and you " +
        "can download a video again from its clip page.",
    )
    compose.onNodeWithTag(SettingsTags.REMOVE_ALL_CANCEL).assertIsFocused()
    screen.audit()
    compose.onNodeWithTag(SettingsTags.REMOVE_ALL_CANCEL).performClick()
    assertThat(calls).isEmpty()

    compose.onNodeWithTag(SettingsTags.STORAGE_REMOVE_ALL).performClick()
    compose.onNode(hasTestTag(SettingsTags.REMOVE_ALL_CONFIRM) and hasText("Remove all videos")).performClick()
    assertThat(calls).containsExactly("removeAllVideos")
  }

  @Test
  fun theLimitsAPausedSyncAndABackgroundRestrictionAreSaidInWords() {
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(SettingsSamples.storageLimits, actions) }) {
      assertThat(compose.textsOf(SettingsTags.STORAGE_TOTAL)).contains("1.4 GB of 1 GB")
      assertThat(compose.textsOf(SettingsTags.STORAGE_BUDGET_REACHED)).contains("Storage budget reached. Remove clips or raise the budget.")
      assertThat(compose.textsOf(SettingsTags.STORAGE_PAUSED)).contains("Downloads continue the next time this phone reaches your computer.")
      assertThat(compose.textsOf(SettingsTags.STORAGE_BACKGROUND))
        .contains("Android is limiting PhonoTrainer in the background: clips arrive when you open the app.")
      compose.onNodeWithTag(SettingsTags.STORAGE_DOWNLOAD_VIDEOS).assertIsOff()
      budget("1 GB").assertIsSelected()
    }
    compose.onNodeWithTag(SettingsTags.STORAGE_BATTERY).performClick()
    assertThat(calls).containsExactly("battery")
  }

  @Test
  fun aPhoneAlmostFullSaysDownloadsArePaused() {
    val full = SettingsSamples.storage.copy(read = StorageRead.Ready(SettingsSamples.usage.copy(belowFloor = true, budgetReached = true)))
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(full, actions) }) {
      assertThat(compose.textsOf(SettingsTags.STORAGE_FLOOR)).contains("Your phone is almost full. Downloads are paused.")
      // One limit notice at a time: the free space comes first.
      compose.onNodeWithTag(SettingsTags.STORAGE_BUDGET_REACHED).assertDoesNotExist()
    }
  }

  @Test
  fun theFirstRunShowsZerosAndNothingToRemove() {
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(SettingsSamples.storageFirstRun, actions) }) {
      assertThat(compose.textsOf(SettingsTags.STORAGE_TOTAL)).contains("0 bytes of 2 GB")
      assertThat(compose.textsOf(SettingsTags.STORAGE_EMPTY)).contains("Nothing on this phone yet")
      compose.onNodeWithTag(SettingsTags.STORAGE_REMOVE_ALL).assertDoesNotExist()
    }
  }

  @Test
  fun withoutABudgetTheTotalIsWhatIsUsed() {
    val free = SettingsSamples.storage.copy(read = StorageRead.Ready(SettingsSamples.usage.copy(budget = StorageBudget.FreeSpaceOnly)))
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(free, actions) }) {
      assertThat(compose.textsOf(SettingsTags.STORAGE_TOTAL)).contains("1.4 GB used")
      budget("No limit except free space").assertIsSelected()
    }
  }

  @Test
  fun loadingAndAnUnreadableStorageAreStatesOfTheirOwn() {
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(StorageUiState(), actions) }) {
      compose.onNodeWithTag(SettingsTags.STORAGE_LOADING).assertExists()
      compose.onNodeWithTag(SettingsTags.STORAGE_TOTAL).assertDoesNotExist()
    }
  }

  @Test
  fun anUnreadableStorageOffersToTryAgain() {
    screen.checkEach(ScreenVariant.REQUIRED, { StorageScreen(StorageUiState(read = StorageRead.Failed), actions) }) {
      assertThat(compose.textsOf(SettingsTags.STORAGE_ERROR)).contains("Couldn't measure what this phone keeps.")
    }
    compose.onNodeWithTag(SettingsTags.STORAGE_RETRY).performClick()
    assertThat(calls).containsExactly("retry")
  }
}
