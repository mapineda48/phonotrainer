package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.SyncState
import com.mapineda48.phonotrainer.core.domain.model.TransferProgress
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The words of the computer status card (design 9.17, spec 7.5 and F-03 item 2), without drawing it: every
 * `ComputerStatus` with its title, icon and action, the subtitles, the rows in order, and the one sentence TalkBack
 * reads.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionStatusCardCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
  private val now = Instant.parse("2026-10-04T10:30:00Z")
  private val none = RequestCounts(0, 0, 0, 0)

  private fun words(
    status: ComputerStatus,
    sync: SyncState = SyncState.Idle,
    counts: RequestCounts = none,
    transfers: TransferProgress? = null,
    extras: ConnectionCardExtras = ConnectionCardExtras(now = now),
  ) = ConnectionStatusCardCopy.words(status, sync, counts, transfers, extras, resources)

  @Test
  fun everyStatusHasItsWordsItsIconAndItsAction() {
    data class Row(val title: String, val icon: Int, val actions: List<ConnectionAction>)
    val connected = ComputerStatus.Connected("Desk PC", analyzing = 0, inLine = 0, lowOnSpace = false)
    val expected = mapOf<ComputerStatus, Row>(
      ComputerStatus.NotConnected to Row("Not connected to a computer", PhonoIcons.ConnNotConnected, listOf(ConnectionAction.CONNECT)),
      ComputerStatus.Offline to Row("Offline · requests wait on this phone", PhonoIcons.ConnOffline, emptyList()),
      ComputerStatus.NotOnWifi to Row("Not on Wi-Fi · your computer is reachable only at home", PhonoIcons.ConnNotOnWifi, emptyList()),
      ComputerStatus.Looking to Row("Looking for your computer…", PhonoIcons.ConnChecking, emptyList()),
      ComputerStatus.Checking to Row("Checking your computer…", PhonoIcons.ConnChecking, emptyList()),
      connected to Row("Connected to Desk PC", PhonoIcons.ConnConnected, listOf(ConnectionAction.SYNC_NOW)),
      ComputerStatus.NotReachable("Desk PC", null, addressMayHaveChanged = false, ProbeOutcome.NoAnswer(3)) to
        Row("Your computer isn't reachable", PhonoIcons.ConnUnreachable, listOf(ConnectionAction.WHY)),
      ComputerStatus.DifferentComputer("Other PC") to Row("A different computer answered", PhonoIcons.ConnDifferent, listOf(ConnectionAction.DETAILS)),
      ComputerStatus.ConnectAgain to
        Row("Your computer no longer recognizes this phone", PhonoIcons.ConnConnectAgain, listOf(ConnectionAction.CONNECT_AGAIN)),
      ComputerStatus.ComputerTooOld to Row("Update PhonoTrainer on your computer", PhonoIcons.ConnUpdate, listOf(ConnectionAction.WHY)),
      ComputerStatus.AppTooOld to Row("Update this app", PhonoIcons.ConnUpdate, listOf(ConnectionAction.WHY)),
    )
    // One row per subtype of spec 7.5's table.
    assertThat(expected.keys.map { it::class }.toSet()).hasSize(11)
    expected.forEach { (status, row) ->
      val words = words(status)
      assertWithMessage("$status").that(words.title).isEqualTo(row.title)
      assertWithMessage("$status").that(words.icon).isEqualTo(row.icon)
      assertWithMessage("$status").that(words.actions).isEqualTo(row.actions)
      assertWithMessage("$status").that(words.connected).isEqualTo(status is ComputerStatus.Connected)
      // The title is the spec's one mapping, the same everywhere a status shows.
      assertWithMessage("$status").that(ConnectionStatusCardCopy.title(status, SyncState.Idle, resources)).isEqualTo(row.title)
    }
  }

  @Test
  fun connectedSaysWhenItWasCheckedAndWhatTheComputerIsDoing() {
    // F-03-AC2: a computer that answered 10 s ago reads "Connected to {name}" with the time.
    val words = words(
      ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 2, lowOnSpace = false),
      extras = ConnectionCardExtras(now = now, checkedAt = now.minusSeconds(10)),
    )
    assertThat(words.title).isEqualTo("Connected to Desk PC")
    assertThat(words.subtitle).isEqualTo("Checked 10 s ago · analyzing 1 · 2 in line")
    assertThat(words.sentence).isEqualTo("Connected to Desk PC; Checked 10 s ago, analyzing 1, 2 in line.")
  }

  @Test
  fun checkingClaimsNothingAndOffersNothing() {
    // F-03-AC3: "Checking your computer…" at once, with no action that claims to run now.
    val words = words(ComputerStatus.Checking, counts = RequestCounts(waiting = 1, onComputer = 0, needsAttention = 0, readyNew = 0))
    assertThat(words.title).isEqualTo("Checking your computer…")
    assertThat(words.subtitle).isNull()
    assertThat(words.actions).isEmpty()
  }

  @Test
  fun notReachableSaysWhenTheComputerWasLastSeenAndThatClipsWorkOffline() {
    val words = words(ComputerStatus.NotReachable("Desk PC", now.minusSeconds(3 * 3_600), addressMayHaveChanged = true, ProbeOutcome.Refused))
    assertThat(words.title).isEqualTo("Your computer isn't reachable")
    assertThat(words.subtitle).isEqualTo("last seen 3 h ago · your clips work offline")
    assertThat(words.actions).containsExactly(ConnectionAction.WHY, ConnectionAction.NEW_ADDRESS).inOrder()
    assertThat(words(ComputerStatus.NotReachable("Desk PC", null, false, ProbeOutcome.Reset)).subtitle).isEqualTo("your clips work offline")
  }

  @Test
  fun notConnectedSaysClipsWaitOnThisPhone() {
    assertThat(words(ComputerStatus.NotConnected).subtitle).isEqualTo("Clips you add wait on this phone.")
  }

  @Test
  fun aSyncNamesTheClipsTheBytesAndTheClipsAlreadyHere() {
    val words = words(
      ComputerStatus.Connected("Desk PC", 0, 0, lowOnSpace = false),
      sync = SyncState.Active(bringing = 2, percent = 42, sending = 0, doneBytes = 34_400_000, totalBytes = 61_000_000),
      extras = ConnectionCardExtras(now = now, clipsOnPhone = 3, clipsTotal = 10),
    )
    assertThat(words.title).isEqualTo("Syncing · bringing 2 clips · 42 %")
    assertThat(words.subtitle).isEqualTo("Bringing your clips to this phone: 3 of 10")
    assertThat(words.actions).isEmpty()
    assertThat(words.syncProgress).isWithin(0.001f).of(0.42f)
    val bringing = words.rows.single { it.kind == ConnectionRowKind.BRINGING }
    assertThat(bringing.text).isEqualTo("Bringing 2 clips to this phone · 34 MB of 61 MB")
    assertThat(bringing.progress!!).isWithin(0.01f).of(34.4f / 61f)
    // A sync without a count of clips, and one not yet sized.
    assertThat(words(ComputerStatus.Connected("Desk PC", 0, 0, false), sync = SyncState.Active(0, null, 1)).title).isEqualTo("Syncing…")
    assertThat(words(ComputerStatus.Connected("Desk PC", 0, 0, false), sync = SyncState.Active(1, null, 0)).title)
      .isEqualTo("Syncing · bringing 1 clip")
  }

  @Test
  fun aPausedSyncStillOffersSyncNow() {
    val words = words(ComputerStatus.Connected("Desk PC", 0, 0, false), sync = SyncState.Paused(PauseReason.BUDGET_REACHED))
    assertThat(words.title).isEqualTo("Connected to Desk PC")
    assertThat(words.actions).containsExactly(ConnectionAction.SYNC_NOW)
  }

  @Test
  fun theRowsComeInTheOrderOfTheDesign() {
    val words = words(
      ComputerStatus.Connected("Desk PC", analyzing = 1, inLine = 1, lowOnSpace = true),
      sync = SyncState.Idle,
      counts = RequestCounts(waiting = 2, onComputer = 2, needsAttention = 1, readyNew = 0, analyzing = 1, inLine = 1),
      transfers = TransferProgress("Coffee shop small talk", doneBytes = 20_000_000, totalBytes = 40_000_000, items = 1),
      extras = ConnectionCardExtras(
        now = now,
        analyzing = RequestProgress(Stage.PHONES, 40, null, null, null, null),
        attentionAction = RequestAction.TRY_AGAIN,
        storageNearlyFull = true,
        backgroundRestricted = true,
      ),
    )
    assertThat(words.rows.map { it.kind }).containsExactly(
      ConnectionRowKind.WAITING,
      ConnectionRowKind.ANALYZING,
      ConnectionRowKind.IN_LINE,
      ConnectionRowKind.ATTENTION,
      ConnectionRowKind.BRINGING,
      ConnectionRowKind.LOW_SPACE,
      ConnectionRowKind.STORAGE,
      ConnectionRowKind.BACKGROUND,
    ).inOrder()
    assertThat(words.rows.map { it.text }).containsExactly(
      "2 waiting on this phone",
      "1 analyzing · 40 % · Hear the phones",
      "1 in line",
      "1 needs your attention",
      "Bringing 1 clip to this phone · 20 MB of 40 MB",
      "Your computer is low on space",
      "Storage almost full: videos of older clips will be removed first.",
      "Android is limiting PhonoTrainer in the background: clips arrive when you open the app.",
    ).inOrder()
    assertThat(words.rows.first { it.kind == ConnectionRowKind.ATTENTION }.action).isEqualTo(ConnectionAction.ATTENTION)
    assertThat(words.rows.first { it.kind == ConnectionRowKind.STORAGE }.action).isEqualTo(ConnectionAction.STORAGE)
    assertThat(words.rows.first { it.kind == ConnectionRowKind.BACKGROUND }.action).isEqualTo(ConnectionAction.BATTERY_SETTINGS)
    assertThat(words.rows.filter { it.isRequestCount }.map { it.kind })
      .containsExactly(ConnectionRowKind.WAITING, ConnectionRowKind.ANALYZING, ConnectionRowKind.IN_LINE, ConnectionRowKind.ATTENTION)
  }

  @Test
  fun moreThanOneAnalyzingIsCountedWithoutOneRequestsProgress() {
    val words = words(
      ComputerStatus.Offline,
      counts = RequestCounts(waiting = 0, onComputer = 2, needsAttention = 3, readyNew = 0, analyzing = 2, inLine = 0),
      extras = ConnectionCardExtras(analyzing = RequestProgress(Stage.PHONES, 40, null, null, null, null)),
    )
    assertThat(words.rows.map { it.text }).containsExactly("2 analyzing", "3 need your attention").inOrder()
    // No primary action known: the attention row has no inline action.
    assertThat(words.rows.last().action).isNull()
  }

  @Test
  fun countsThatDoNotSplitTheComputersQueueStillSayHowManyAreThere() {
    val words = words(ComputerStatus.Offline, counts = RequestCounts(waiting = 0, onComputer = 2, needsAttention = 0, readyNew = 0))
    assertThat(words.rows.map { it.text }).containsExactly("2 on your computer")
  }

  @Test
  fun theWholeCardIsOneSentence() {
    val words = words(
      ComputerStatus.NotReachable("Desk PC", now.minusSeconds(7_200), false, ProbeOutcome.NoAnswer(3)),
      counts = RequestCounts(waiting = 2, onComputer = 0, needsAttention = 1, readyNew = 0),
      extras = ConnectionCardExtras(now = now, storageNearlyFull = true),
    )
    assertThat(words.sentence).isEqualTo(
      "Your computer isn't reachable; last seen 2 h ago, your clips work offline; 2 waiting on this phone; " +
        "1 needs your attention; Storage almost full: videos of older clips will be removed first.",
    )
    assertThat(words(ComputerStatus.Offline).sentence).isEqualTo("Offline, requests wait on this phone.")
  }

  @Test
  fun timesAreSaidInTheAppsOwnWords() {
    fun ago(seconds: Long) = ConnectionStatusCardCopy.ago(now.minusSeconds(seconds), now, resources)
    assertThat(ago(0)).isEqualTo("just now")
    assertThat(ago(-30)).isEqualTo("just now")
    assertThat(ago(10)).isEqualTo("10 s ago")
    assertThat(ago(125)).isEqualTo("2 min ago")
    assertThat(ago(3 * 3_600 + 59)).isEqualTo("3 h ago")
    assertThat(ago(86_400)).isEqualTo("1 day ago")
    assertThat(ago(3 * 86_400)).isEqualTo("3 days ago")
    assertThat(ConnectionStatusCardCopy.megabytes(999_999, resources)).isEqualTo("0 MB")
    assertThat(ConnectionStatusCardCopy.megabytes(61_000_000, resources)).isEqualTo("61 MB")
  }
}
