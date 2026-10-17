package com.mapineda48.phonotrainer.e2e.library

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.StorageRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.api.SyncTrigger
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.spi.StartupGate
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LearnKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import com.mapineda48.phonotrainer.core.navigation.rootKey
import com.mapineda48.phonotrainer.core.sync.SyncModule
import com.mapineda48.phonotrainer.core.testing.computer.FakeComputer
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeStartupGate
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSyncTrigger
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeStorageRepository
import com.mapineda48.phonotrainer.core.testing.fake.sync.FakeSyncController
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.feature.library.RowMenuItem
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
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
 * Why a fake repository (work-plan 2.4): these proofs need a catalogue of about fifty clips, and the sync engine that
 * fills the real one (WP-08) is not in this baseline (`ClipRepository` is the B1 stub, which lists nothing). So this
 * class, and only this one, uninstalls `SyncModule` and binds the settable fakes of work-plan 4.6 for each of its
 * bindings; every other repository is the real one. Once WP-08 is merged, `TestSeeder.seedLibrary` replaces the fake
 * and the uninstall goes (hand-off of WP-16).
 *
 * The per-tab proof opens the Library on the Learn tab's stack: the shell always displays the Home stack, so only an
 * entry of a tab stack leaves the displayed list when another tab shows (what `e2e/smoke/PerTabStateTest` proved on
 * the M2 stub, which change request CR-WP16-1 retires in favour of this class). The ViewModel is told apart by the
 * catalogue's subscription count: one subscription per ViewModel while it is shown.
 */
@HiltAndroidTest
@UninstallModules(SyncModule::class)
class LibraryStateTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  private val catalogue = CountingClipRepository()

  @BindValue @JvmField
  val clipRepository: ClipRepository = catalogue

  @BindValue @JvmField
  val storageRepository: StorageRepository = FakeStorageRepository()

  @BindValue @JvmField
  val syncController: SyncController = FakeSyncController()

  @BindValue @JvmField
  val syncTrigger: SyncTrigger = FakeSyncTrigger()

  @BindValue @JvmField
  val startupGate: StartupGate = FakeStartupGate()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var computer: FakeComputer

  private val phono = PhonoRobot(compose)
  private val library = LibraryRobot(compose, phono)

  /** The row the tests scroll to and watch: near the end of the searched and filtered list. */
  private val clip = LibraryCatalogue.id(5)

  @Before
  fun setUp() {
    hilt.inject()
    LibraryCatalogue.seed(catalogue)
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  /** Searches "walk", selects "On this phone" and scrolls to [clip]; returns where its row is. */
  private fun searchFilterAndScroll(): Float {
    library.awaitRows()
    library.search(LibraryCatalogue.SEARCH)
    library.filter(ClipFilter.ON_THIS_PHONE)
    library.awaitCount(LibraryCatalogue.SEARCHED_ON_PHONE, LibraryCatalogue.SIZE)
    library.scrollTo(clip)
    return library.rowTop(clip)
  }

  /** The rows "walk" and "On this phone" leave, newest first. */
  private val order = LibraryCatalogue.searchedOnPhone.map(LibraryCatalogue::id)

  private fun assertTheLibraryKept(top: Float) = library.assertKept(order, clip, top)

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
    val reads = catalogue.catalogueReads
    listOf(Tab.HOME, Tab.PRACTICE, Tab.WATCH).forEach { other ->
      leaveTheLibraryFor(other)
      phono.tab(OWNER)
      assertTheLibraryKept(top)
      assertThat(catalogue.catalogueReads).isEqualTo(reads)
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

    // Recreated while the Library shows: the same ViewModel, so no new subscription.
    val reads = catalogue.catalogueReads
    phono.scenario.recreate()
    assertTheLibraryKept(top)
    assertThat(catalogue.catalogueReads).isEqualTo(reads)
    assertTheControlsKept()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  @Test
  fun aRestoreFromSavedStateAloneKeepsTheState() {
    openOnTheLearnStack()
    val top = searchFilterAndScroll()
    val reads = catalogue.catalogueReads
    leaveTheLibraryFor(Tab.HOME)
    // Process death as far as the app's state goes: every ViewModel is gone and the activity comes back from its
    // saved state alone (back stacks, saveable state, SavedStateHandles).
    phono.scenario.onActivity { it.viewModelStore.clear() }
    phono.scenario.recreate()
    phono.assertShown(HomeKey)
    phono.tab(OWNER)

    assertTheLibraryKept(top)
    assertThat(catalogue.catalogueReads).isGreaterThan(reads)
    assertTheControlsKept()
    assertTheLibraryIsOnTopOfTheLearnStackOnly()
  }

  @Test
  fun theClipPageOpensFromItsRowAndBackFromItsFeedReturnsToIt() {
    // F-05-AC5's Back order, through "Watch" (the Shorts tab's rows need the corpus of a synced clip).
    library.open()
    library.awaitRows()
    val number = LibraryCatalogue.SIZE - 1
    library.openClip(LibraryCatalogue.id(number))
    phono.assertShown(ClipKey(LibraryCatalogue.id(number)))
    library.awaitClip(LibraryCatalogue.title(number))

    library.clipWatch()
    phono.assertShown(WatchKey())
    assertThat(library.stacks(Tab.WATCH).second)
      .containsExactly(WatchKey(FeedStart.InOrder(LibraryCatalogue.id(number), null, false)))
    phono.back()
    phono.assertShown(ClipKey(LibraryCatalogue.id(number)))
    library.awaitClip(LibraryCatalogue.title(number))
    phono.back()
    phono.assertShown(LibraryKey)
  }

  @Test
  fun removeFromThisPhoneAsksFirstAndSendsNothingToTheComputer() {
    // F-05-AC11 on the device, from the Library's menu and from the Clip page.
    library.open()
    library.awaitRows()
    val first = LibraryCatalogue.SIZE - 1
    library.menu(LibraryCatalogue.id(first), RowMenuItem.REMOVE_FROM_PHONE)
    library.assertRemoveDialog(LibraryCatalogue.title(first))
    library.cancelRemove()
    assertThat(catalogue.actions()).isEmpty()
    library.menu(LibraryCatalogue.id(first), RowMenuItem.REMOVE_FROM_PHONE)
    library.confirmRemove()

    val second = LibraryCatalogue.SIZE - 2
    library.openClip(LibraryCatalogue.id(second))
    library.awaitClip(LibraryCatalogue.title(second))
    library.clipRemoveFromPhone()
    library.assertRemoveDialog(LibraryCatalogue.title(second))
    library.confirmRemove()

    compose.waitUntil(PhonoRobot.TIMEOUT_MS) { catalogue.actions().size == 2 }
    assertThat(catalogue.actions()).containsExactly(
      "removeFromPhone:${LibraryCatalogue.id(first).value}",
      "removeFromPhone:${LibraryCatalogue.id(second).value}",
    ).inOrder()
    assertThat(computer.requests).isEmpty()
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
    library.menuWithoutTouch(LibraryCatalogue.id(number), RowMenuItem.REMOVE_FROM_PHONE)
    library.assertRemoveDialog(LibraryCatalogue.title(number))
    library.assertCancelFocused()
    library.cancelRemove()
    assertThat(catalogue.actions()).isEmpty()
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
