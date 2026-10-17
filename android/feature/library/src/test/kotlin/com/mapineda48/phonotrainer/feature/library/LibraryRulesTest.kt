package com.mapineda48.phonotrainer.feature.library

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant
import org.junit.Test

/** The Library's rules (spec F-04): order, filters, search, the "In progress" group and the row menu. */
class LibraryRulesTest {
  private val second = Instant.parse("2026-10-04T13:35:19Z")
  private val a = LibrarySamples.clip("20261004-133519-a77c", "Coffee shop small talk", addedAt = second)
  private val b = LibrarySamples.clip("20261004-133519-0b1d", "At the station", addedAt = second)
  private val c = LibrarySamples.clip("20261004-133520-ffff", "A walk in the park", addedAt = second.plusSeconds(1))

  @Test
  fun clipsOfTheSameSecondKeepOneOrderWhateverOrderTheyArriveIn() {
    val orders = listOf(listOf(a, b, c), listOf(b, a, c), listOf(c, b, a), listOf(a, c, b))
    ClipSort.entries.forEach { sort ->
      val shown = orders.map { list -> LibraryRules.arrange(list, "", ClipFilter.ALL, sort).map { it.id.value } }
      assertThat(shown.distinct()).hasSize(1)
    }
    assertThat(LibraryRules.arrange(listOf(a, b, c), "", ClipFilter.ALL, ClipSort.NEWEST).map { it.id.value })
      .containsExactly("20261004-133520-ffff", "20261004-133519-a77c", "20261004-133519-0b1d").inOrder()
    assertThat(LibraryRules.arrange(listOf(a, b, c), "", ClipFilter.ALL, ClipSort.OLDEST).map { it.id.value })
      .containsExactly("20261004-133519-0b1d", "20261004-133519-a77c", "20261004-133520-ffff").inOrder()
    assertThat(LibraryRules.arrange(listOf(a, b, c), "", ClipFilter.ALL, ClipSort.TITLE).map { it.title })
      .containsExactly("A walk in the park", "At the station", "Coffee shop small talk").inOrder()
  }

  @Test
  fun onThisPhoneShowsExactlyTheClipsWhoseStudyPackIsHere() {
    // F-04-AC1: 12 clips, 3 on this phone.
    val twelve = (1..12).map { n ->
      LibrarySamples.clip(
        "20261001-0000%02d-aaaa".format(n),
        "Clip $n",
        availability = when {
          n <= 3 -> ClipAvailability.OnThisPhone
          n <= 6 -> ClipAvailability.NotOnThisPhone
          n <= 8 -> ClipAvailability.Bringing(10 * n)
          n <= 10 -> ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED)
          n == 11 -> ClipAvailability.GettingReadyOnComputer
          else -> ClipAvailability.NeedsDownloadingAgain
        },
      )
    }
    assertThat(LibraryRules.arrange(twelve, "", ClipFilter.ON_THIS_PHONE, ClipSort.NEWEST)).hasSize(3)
    assertThat(LibraryRules.arrange(twelve, "", ClipFilter.NOT_ON_THIS_PHONE, ClipSort.NEWEST)).hasSize(9)
    assertThat(LibraryRules.arrange(twelve, "", ClipFilter.ALL, ClipSort.NEWEST)).hasSize(12)
  }

  @Test
  fun searchMatchesTheTitleWhateverTheCase() {
    val all = listOf(a, b, c)
    assertThat(LibraryRules.arrange(all, "  WALK ", ClipFilter.ALL, ClipSort.NEWEST)).containsExactly(c)
    assertThat(LibraryRules.arrange(all, "TALK", ClipFilter.ALL, ClipSort.NEWEST).map { it.title })
      .containsExactly("Coffee shop small talk")
    assertThat(LibraryRules.arrange(all, "t", ClipFilter.ALL, ClipSort.NEWEST).map { it.title })
      .containsExactly("A walk in the park", "Coffee shop small talk", "At the station").inOrder()
    assertThat(LibraryRules.arrange(all, "kitchen", ClipFilter.ALL, ClipSort.NEWEST)).isEmpty()
  }

  @Test
  fun otherFiltersSelectWhatTheySay() {
    val kept = a.copy(kept = true)
    val audio = b.copy(video = VideoAvailability.AudioOnlyClip)
    val coming = c.copy(availability = ClipAvailability.Bringing(5))
    val all = listOf(kept, audio, coming)
    assertThat(LibraryRules.arrange(all, "", ClipFilter.KEPT, ClipSort.NEWEST)).containsExactly(kept)
    assertThat(LibraryRules.arrange(all, "", ClipFilter.AUDIO_ONLY, ClipSort.NEWEST)).containsExactly(audio)
    assertThat(LibraryRules.arrange(all, "", ClipFilter.VIDEO, ClipSort.NEWEST)).containsExactly(kept, coming)
    assertThat(LibraryRules.arrange(all, "", ClipFilter.IN_PROGRESS, ClipSort.NEWEST)).containsExactly(coming)
  }

  @Test
  fun inProgressHoldsTheNonFinalRequestsAttentionFirst() {
    val states = RequestState.entries.mapIndexed { index, state ->
      LibrarySamples.request("request-%02d".format(index), state, state.name, createdAt = second.plusSeconds(index.toLong()))
    }
    val rerun = LibrarySamples.request("request-rerun", RequestState.NEEDS_ATTENTION, "re-run", createdAt = second)
      .copy(rerunPending = true)
    val shown = LibraryRules.inProgress(states + rerun).map { it.title }
    assertThat(shown).containsExactly(
      "COPY_FAILED", "COPY_MISSING", "NEEDS_ATTENTION",
      "PREPARING",
      "WAITING",
      "SENDING",
      "re-run", "SENT", "IN_LINE", "ANALYZING", "CANCEL_WAITING",
    ).inOrder()
  }

  @Test
  fun theStartedOnYourComputerLineCountsTheBrowsersJobsThatAreNotHidden() {
    fun job(id: String, status: RemoteStatus, hidden: Boolean = false) = ComputerJob(
      JobId(id), "title", status, null, 0, null, ClipSource(SourceKind.UPLOAD, null, null, null), null, second, hidden,
    )
    // F-04-AC6: a failed browser job is counted on the line, never a row.
    val jobs = listOf(job("1", RemoteStatus.RUNNING), job("2", RemoteStatus.ERROR), job("3", RemoteStatus.QUEUED, hidden = true),
      job("4", RemoteStatus.DONE))
    assertThat(LibraryRules.startedOnComputer(jobs)).isEqualTo(2)
  }

  @Test
  fun everyRequestActionHasOnePlace() {
    val table = RequestAction.entries.associateWith { LibraryRules.route(it) }
    assertThat(table.filterValues { it == RequestRoute.PERFORM }.keys).containsExactly(
      RequestAction.SEND_NOW, RequestAction.STOP_SENDING, RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY,
      RequestAction.UNDO_CANCEL, RequestAction.CANCEL_PREPARING,
    )
    assertThat(table.filterValues { it == RequestRoute.DETAIL }.keys).containsExactly(
      RequestAction.CANCEL, RequestAction.REMOVE, RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS, RequestAction.WHY,
    )
    assertThat(table[RequestAction.CONNECT_AGAIN]).isEqualTo(RequestRoute.CONNECT_AGAIN)
  }

  @Test
  fun theRowMenuOffersWhatAppliesAndNeverDeletesFromTheComputer() {
    assertThat(LibraryRules.menu(LibrarySamples.coffee)).containsExactly(
      RowMenuItem.WATCH, RowMenuItem.OPEN, RowMenuItem.STOP_KEEPING, RowMenuItem.REMOVE_FROM_PHONE, RowMenuItem.REMOVE_VIDEO,
    ).inOrder()
    assertThat(LibraryRules.menu(LibrarySamples.lake)).containsExactly(
      RowMenuItem.WATCH, RowMenuItem.OPEN, RowMenuItem.KEEP, RowMenuItem.DOWNLOAD,
    ).inOrder()
    assertThat(LibraryRules.menu(LibrarySamples.radio)).containsExactly(
      RowMenuItem.WATCH, RowMenuItem.OPEN, RowMenuItem.KEEP, RowMenuItem.REMOVE_FROM_PHONE,
    ).inOrder()
    val ready = LibrarySamples.coffee.copy(availability = ClipAvailability.GettingReadyOnComputer, video = VideoAvailability.GettingReadyOnComputer)
    assertThat(LibraryRules.menu(ready)).containsExactly(RowMenuItem.WATCH, RowMenuItem.OPEN, RowMenuItem.STOP_KEEPING).inOrder()
    // D-54: the two removals are never next to each other; the enum has no computer-side item at all.
    assertThat(RowMenuItem.entries.map { it.name }).containsNoneOf("DELETE_FROM_COMPUTER", "DELETE")
  }
}
