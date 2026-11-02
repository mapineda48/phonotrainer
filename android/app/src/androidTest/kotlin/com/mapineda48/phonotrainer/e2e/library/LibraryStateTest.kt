package com.mapineda48.phonotrainer.e2e.library

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipQuery
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.DrawnTagWatch
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.e2e.support.TestTime
import com.mapineda48.phonotrainer.feature.library.LibraryTags
import com.mapineda48.phonotrainer.feature.library.RowMenuItem
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Library's state across the feed, the tabs and the activity (work-plan WP-16 Proof; spec F-04-AC5; architecture
 * 16.2): "Watch" on row k opens the feed In order on clip k at its resume short and Back shows the Library at the same
 * scroll position, search and filter; and the Library's ViewModel and state survive tab switches and recreates, and
 * its state a restore from the saved state alone.
 *
 * Every repository is the real one: the catalogue of about fifty clips ([LibraryCatalogue]) is on this test's computer
 * and reaches the phone through the real stack ([TestSeeder]: pairing, a sync pass, the indexer) before the app opens.
 *
 * The per-tab proof opens the Library on the Learn tab's stack: the shell always displays the Home stack, so only an
 * entry of a tab stack leaves the displayed list when another tab shows (what `e2e/smoke/PerTabStateTest` proved on
 * the M2 stub, which change request CR-WP16-1 retires in favour of this class). The ViewModel is told apart by its
 * first state (decisions I-22): a new Library ViewModel draws its loading state until the catalogue arrives, and a kept
 * one never draws it again ([DrawnTagWatch] looks at every frame, which the idle-time test API cannot).
 */
@HiltAndroidTest
class LibraryStateTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var time: TestTime

  @Inject lateinit var clips: ClipRepository

  @Inject lateinit var computer: FakeComputer

  private val phono = PhonoRobot(compose)
  private val library = LibraryRobot(compose, phono)

  /** The Library's loading state in every frame: only a new ViewModel draws it. */
  private val loading = DrawnTagWatch(LibraryTags.LOADING)

  private lateinit var catalogue: LibraryCatalogue

  /** The row the tests scroll to and watch: near the end of the searched and filtered list. */
  private val clip: JobId get() = catalogue.id(5)

  @Before
  fun setUp() {
    hilt.inject()
    catalogue = runBlocking { LibraryCatalogue.seed(seeder, time, clips) }
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  @After
  fun tearDown() {
    loading.close()
    if (phono.isLaunched()) phono.scenario.close()
  }

  private fun availability(id: JobId): ClipAvailability =
    runBlocking { clips.clips(ClipQuery()).first() }.single { it.id == id }.availability

  /** Searches "walk", selects "On this phone" and scrolls to [clip]; returns where its row is. */
  private fun searchFilterAndScroll(): Float {
    library.awaitRows()
    library.search(LibraryCatalogue.SEARCH)
    library.filter(ClipFilter.ON_THIS_PHONE)
    library.awaitCount(LibraryCatalogue.SEARCHED_ON_PHONE, LibraryCatalogue.SIZE)
    library.scrollTo(clip)
    return library.rowTop(clip)
  }

  private fun assertTheLibraryKept(top: Float) = library.assertKept(catalogue.searchedOnPhone, clip, top)

  /** Last, as it scrolls: the search field, the selected chip and the live count. */
  private fun assertTheControlsKept() = library.assertControls(
    search = LibraryCatalogue.SEARCH,
    filter = ClipFilter.ON_THIS_PHONE,
    shown = LibraryCatalogue.SEARCHED_ON_PHONE,
    total = LibraryCatalogue.SIZE,
  )

  @Test
  fun watchOnRowKOpensTheFeedInOrderAndBackRestoresTheLibrary() {
    // F-04-AC5, from the Library as Home's "All clips" opens it (on the Home stack).
    library.open()
    val top = searchFilterAndScroll()

    library.watch(clip)
    phono.assertShown(WatchKey())
    phono.assertNotShown(LibraryKey)
    // In order on clip k, neither at a given short nor from the start: the feed resumes it (FeedStart.InOrder).
    assertThat(library.stacks(Tab.WATCH).second).containsExactly(WatchKey(FeedStart.InOrder(clip, null, false)))

    phono.back()
    assertTheLibraryKept(top)
    assertThat(library.stacks(Tab.HOME).first).containsExactly(HomeKey, LibraryKey).inOrder()

    // The same after switching to another tab and back.
    phono.tab(Tab.LEARN)
    phono.assertShown(LearnKey)
    phono.assertNotShown(LibraryKey)
    phono.tab(Tab.HOME)
    assertTheLibraryKept(top)
    assertTheControlsKept()
  }

  @Test
  fun aTabSwitchKeepsTheViewModelAndItsState() {
    openOnTheLearnStack()
    val top = searchFilterAndScroll()
    loading.start(phono.scenario)
    listOf(Tab.HOME, Tab.PRACTICE, Tab.WATCH).forEach { other ->
      leaveTheLibraryFor(other)
      phono.tab(OWNER)
      assertTheLibraryKept(top)
      // The same ViewModel: a new one would have drawn its loading state before the catalogue arrived.
      assertThat(loading.drawn).isFalse()
    }
    assertTheControlsKept()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  @Test
  fun aRecreateKeepsTheViewModelAndItsState() {
    openOnTheLearnStack()
    val top = searchFilterAndScroll()

    // Recreated while the Library's tab is in the background: its stack is decorated but not displayed.
    leaveTheLibraryFor(Tab.HOME)
    phono.scenario.recreate()
    phono.assertShown(HomeKey)
    phono.assertNotShown(LibraryKey)
    phono.tab(OWNER)
    assertTheLibraryKept(top)

    // Recreated while the Library shows: the same ViewModel, so the new activity never draws the loading state.
    loading.start(phono.scenario)
    phono.scenario.recreate()
    assertTheLibraryKept(top)
    assertThat(loading.drawn).isFalse()
    assertTheControlsKept()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  @Test
  fun aRestoreFromSavedStateAloneKeepsTheState() {
    openOnTheLearnStack()
    val top = searchFilterAndScroll()
    leaveTheLibraryFor(Tab.HOME)
    loading.start(phono.scenario)
    // Process death as far as the app's state goes: every ViewModel is gone and the activity comes back from its
    // saved state alone (back stacks, saveable state, SavedStateHandles).
    phono.scenario.onActivity { it.viewModelStore.clear() }
    phono.scenario.recreate()
    phono.assertShown(HomeKey)
    phono.tab(OWNER)

    assertTheLibraryKept(top)
    // A new ViewModel, which drew its loading state first: what is kept came from the saved state alone.
    assertThat(loading.drawn).isTrue()
    assertTheControlsKept()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  @Test
  fun theClipPageOpensFromItsRowAndBackFromItsFeedReturnsToIt() {
    // F-05-AC5's Back order, through "Watch" (the Shorts tab's rows need the corpus of a synced clip).
    library.open()
    library.awaitRows()
    val number = LibraryCatalogue.SIZE - 1
    library.openClip(catalogue.id(number))
    phono.assertShown(ClipKey(catalogue.id(number)))
    library.awaitClip(LibraryCatalogue.title(number))

    library.clipWatch()
    phono.assertShown(WatchKey())
    assertThat(library.stacks(Tab.WATCH).second)
      .containsExactly(WatchKey(FeedStart.InOrder(catalogue.id(number), null, false)))
    phono.back()
    phono.assertShown(ClipKey(catalogue.id(number)))
    library.awaitClip(LibraryCatalogue.title(number))
    phono.back()
    phono.assertShown(LibraryKey)
  }

  @Test
  fun removeFromThisPhoneAsksFirstAndSendsNothingToTheComputer() {
    // F-05-AC11 on the device, from the Library's menu and from the Clip page.
    library.open()
    library.awaitRows()
    val sentBefore = computer.requests.size
    val first = LibraryCatalogue.SIZE - 1
    library.menu(catalogue.id(first), RowMenuItem.REMOVE_FROM_PHONE)
    library.assertRemoveDialog(LibraryCatalogue.title(first))
    library.cancelRemove()
    assertThat(availability(catalogue.id(first))).isEqualTo(ClipAvailability.OnThisPhone)
    library.menu(catalogue.id(first), RowMenuItem.REMOVE_FROM_PHONE)
    library.confirmRemove()

    val second = LibraryCatalogue.SIZE - 2
    library.openClip(catalogue.id(second))
    library.awaitClip(LibraryCatalogue.title(second))
    library.clipRemoveFromPhone()
    library.assertRemoveDialog(LibraryCatalogue.title(second))
    library.confirmRemove()

    val removed = listOf(catalogue.id(first), catalogue.id(second))
    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { removed.all { availability(it) == ClipAvailability.NotOnThisPhone } }
    // Only this phone's copies went: the computer, which the paired phone keeps reading, was asked to change nothing.
    assertThat(computer.requests.drop(sentBefore).filter { it.method != "GET" }).isEmpty()
  }

  @Test
  fun aKeyboardUserLandsOnCancelInTheRemovalConfirmation() {
    // Design 9.23: a destructive confirmation focuses "Cancel" first. Input focus exists out of touch mode only, so the
    // phone leaves touch mode as it does when a keyboard is used, and the menu is opened without a touch.
    library.open()
    library.awaitRows()
    phono.device.pressKeyCode(KeyEvent.KEYCODE_TAB)
    compose.waitForIdle()
    val number = LibraryCatalogue.SIZE - 1
    library.menuWithoutTouch(catalogue.id(number), RowMenuItem.REMOVE_FROM_PHONE)
    library.assertRemoveDialog(LibraryCatalogue.title(number))
    library.assertCancelFocused()
    library.cancelRemove()
    assertThat(availability(catalogue.id(number))).isEqualTo(ClipAvailability.OnThisPhone)
  }

  private fun openOnTheLearnStack() {
    phono.tab(OWNER)
    phono.assertShown(OWNER.rootKey)
    library.open()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  /** Shows [other]; the Library's entry leaves the displayed list (it is on [OWNER]'s stack, not Home's). */
  private fun leaveTheLibraryFor(other: Tab) {
    phono.tab(other)
    phono.assertShown(other.rootKey)
    phono.assertNotShown(LibraryKey)
  }

  private fun assertTheLibraryIsOnTopOfTheLearnStackOnly() {
    val (home, owner) = library.stacks(OWNER)
    assertThat(home).containsExactly(HomeKey)
    assertThat(owner).containsExactly(OWNER.rootKey, LibraryKey).inOrder()
  }

  private companion object {
    /** The tab whose stack holds the Library in the per-tab proofs: a tab stack, displayed only while it is current. */
    val OWNER = Tab.LEARN
  }
}
