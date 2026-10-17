package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.ComputerJob
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import java.util.Locale

/** The items of a Library row's "More actions" menu (spec F-04, design 9.18), in their order. */
enum class RowMenuItem { WATCH, OPEN, KEEP, STOP_KEEPING, DOWNLOAD, REMOVE_FROM_PHONE, REMOVE_VIDEO }

/** How a request action of an "In progress" row is carried out from the Library. */
internal enum class RequestRoute {
  /** Done at once by `RequestRepository.perform` (the row's inline primary action). */
  PERFORM,

  /** Opens the request detail, which owns its confirmation or explanation ("Cancel", "Remove", "Why?"…). */
  DETAIL,

  /** Opens "Connect to your computer" in its Connect again mode. */
  CONNECT_AGAIN,
}

/**
 * The Library's rules as pure functions (spec F-04), so the screen tests and the unit tests prove them on any list:
 * which clips show and in which order, which requests are "In progress", and what a row's menu offers.
 */
internal object LibraryRules {
  /**
   * The clips of [all] that match [search] and [filter], in [sort]'s order. Ties are broken by job id, so two clips
   * created in the same second keep their order however often the list refreshes (F-04-AC4; job ids start with their
   * creation second, architecture 2.1). The repository orders its query the same way; the screen applies the order
   * again so that what it shows never depends on the order a list arrived in.
   */
  fun arrange(all: List<ClipSummary>, search: String, filter: ClipFilter, sort: ClipSort): List<ClipSummary> {
    val needle = search.trim().lowercase(Locale.ROOT)
    return all.filter { (needle.isEmpty() || it.title.lowercase(Locale.ROOT).contains(needle)) && passes(it, filter) }
      .sortedWith(comparator(sort))
  }

  /** Whether [clip] passes [filter]. "On this phone" means its study pack is here and verified (SC-11). */
  fun passes(clip: ClipSummary, filter: ClipFilter): Boolean = when (filter) {
    ClipFilter.ALL -> true
    ClipFilter.ON_THIS_PHONE -> clip.availability == ClipAvailability.OnThisPhone
    ClipFilter.NOT_ON_THIS_PHONE -> clip.availability != ClipAvailability.OnThisPhone
    ClipFilter.IN_PROGRESS -> clip.availability is ClipAvailability.Bringing ||
      clip.availability == ClipAvailability.GettingReadyOnComputer
    ClipFilter.KEPT -> clip.kept
    ClipFilter.VIDEO -> clip.video != VideoAvailability.AudioOnlyClip
    ClipFilter.AUDIO_ONLY -> clip.video == VideoAvailability.AudioOnlyClip
  }

  private val byId: Comparator<ClipSummary> = compareBy { it.id.value }

  fun comparator(sort: ClipSort): Comparator<ClipSummary> = when (sort) {
    ClipSort.NEWEST -> compareByDescending<ClipSummary> { it.addedAt }.then(byId.reversed())
    ClipSort.OLDEST -> compareBy<ClipSummary> { it.addedAt }.then(byId)
    ClipSort.TITLE -> compareBy<ClipSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
      .thenByDescending { it.addedAt }.then(byId.reversed())
    ClipSort.LONGEST -> compareByDescending<ClipSummary> { it.durationS ?: -1.0 }.then(byId.reversed())
  }

  /** The sorts the Library offers, in the web's order ("Newest first", "Oldest first", "Name A–Z"). */
  val SORTS: List<ClipSort> = listOf(ClipSort.NEWEST, ClipSort.OLDEST, ClipSort.TITLE)

  /** The filter chips ("On this phone", "Not on this phone"); none selected is every clip. */
  val FILTERS: List<ClipFilter> = listOf(ClipFilter.ON_THIS_PHONE, ClipFilter.NOT_ON_THIS_PHONE)

  /**
   * This phone's non-final requests, the "In progress" group (F-04): preparing, a lost copy, waiting, sending, on the
   * computer (in line, analyzing, a cancel waiting, a re-run on its way) and needing attention. A request whose clip is
   * ready, coming to this phone or here is a clip row instead, and a cancelled one is final. Ordered as Requests
   * groups them (attention first, then preparing, waiting, sending, on the computer), oldest first inside a group.
   */
  fun inProgress(requests: List<Request>): List<Request> = requests
    .filter { it.rerunPending || it.state !in FINAL }
    .sortedWith(compareBy<Request> { group(it) }.thenBy { it.createdAt }.thenBy { it.id.value })

  private val FINAL = setOf(
    RequestState.READY_ON_COMPUTER, RequestState.GETTING_ON_PHONE, RequestState.ON_PHONE, RequestState.CANCELLED,
  )

  private fun group(request: Request): Int = when {
    request.rerunPending -> 4
    else -> when (request.state) {
      RequestState.NEEDS_ATTENTION, RequestState.COPY_FAILED, RequestState.COPY_MISSING -> 0
      RequestState.PREPARING -> 1
      RequestState.WAITING -> 2
      RequestState.SENDING -> 3
      else -> 4
    }
  }

  /** The jobs "started on your computer" the line counts: not hidden on this phone and not finished (F-04-AC6). */
  fun startedOnComputer(jobs: List<ComputerJob>): Int = jobs.count { !it.hidden && it.status != RemoteStatus.DONE }

  /** How the Library carries out [action] of an "In progress" row. */
  fun route(action: RequestAction): RequestRoute = when (action) {
    RequestAction.SEND_NOW, RequestAction.STOP_SENDING, RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY,
    RequestAction.UNDO_CANCEL, RequestAction.CANCEL_PREPARING -> RequestRoute.PERFORM
    RequestAction.CONNECT_AGAIN -> RequestRoute.CONNECT_AGAIN
    RequestAction.CANCEL, RequestAction.REMOVE, RequestAction.OPEN_LINK, RequestAction.SHOW_DETAILS,
    RequestAction.WHY -> RequestRoute.DETAIL
  }

  /** Whether something of [clip] is on this phone, so "Remove from this phone…" has something to remove. */
  fun removable(clip: ClipSummary): Boolean = when (clip.availability) {
    ClipAvailability.OnThisPhone, is ClipAvailability.Bringing, is ClipAvailability.DownloadPaused,
    ClipAvailability.NeedsDownloadingAgain -> true
    ClipAvailability.NotOnThisPhone, ClipAvailability.GettingReadyOnComputer -> false
  } || clip.video == VideoAvailability.OnThisPhone || clip.video is VideoAvailability.Downloading

  /** Whether "Download to this phone" applies: the learner removed it, or it was never downloaded (F-04-AC2). */
  fun downloadable(clip: ClipSummary): Boolean = clip.availability == ClipAvailability.NotOnThisPhone

  /** Whether the video alone can be removed ("Remove the video only"). */
  fun videoRemovable(clip: ClipSummary): Boolean =
    clip.video == VideoAvailability.OnThisPhone || clip.video is VideoAvailability.Downloading

  /**
   * The row menu of [clip] (spec F-04): "Watch", "Open clip page", "Keep on this phone" or "Stop keeping", then
   * "Download to this phone" or "Remove from this phone…", and "Remove the video only" when a video is here. It never
   * holds "Delete from your computer…" (that lives only on the Clip page, v1-complete, D-54).
   */
  fun menu(clip: ClipSummary): List<RowMenuItem> = buildList {
    add(RowMenuItem.WATCH)
    add(RowMenuItem.OPEN)
    add(if (clip.kept) RowMenuItem.STOP_KEEPING else RowMenuItem.KEEP)
    when {
      downloadable(clip) -> add(RowMenuItem.DOWNLOAD)
      removable(clip) -> add(RowMenuItem.REMOVE_FROM_PHONE)
    }
    if (videoRemovable(clip)) add(RowMenuItem.REMOVE_VIDEO)
  }
}
