package com.mapineda48.phonotrainer.feature.library

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipFilter
import com.mapineda48.phonotrainer.core.domain.model.ClipSort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.navigation.ClipTab

/** Test tags of :feature:library ("library.<screen>.<element>", work-plan 2.6). */
object LibraryTags {
  // --- The Library (P-01); the top bar's Back is found by its label, "Back" ---
  const val ADD = "library.list.add"
  const val LEDE = "library.list.lede"

  /** The one scrolling list of the screen (group, search, chips, count and rows). */
  const val LIST = "library.list.list"
  /** The search field's container; its editable node is `hasSetTextAction() and hasAnyAncestor(hasTestTag(SEARCH))`. */
  const val SEARCH = "library.list.search"

  /** The live count "8 of 10 clips" (a polite live region). */
  const val COUNT = "library.list.count"
  const val SORT = "library.list.sort"
  const val OFFLINE = "library.list.offline"
  const val LOADING = "library.list.loading"
  const val ERROR = "library.list.error"
  const val RETRY = "library.list.retry"
  const val EMPTY = "library.list.empty"
  const val NO_MATCH = "library.list.no-match"
  const val CLEAR_SEARCH = "library.list.clear-search"
  const val SHOW_ALL = "library.list.show-all"

  /** The "In progress" group's head. */
  const val IN_PROGRESS = "library.list.in-progress"

  /** "2 more started on your computer" (opens Requests). */
  const val STARTED_ON_COMPUTER = "library.list.started-on-computer"

  /** The filter chip of [filter] ("On this phone", "Not on this phone"). */
  fun filter(filter: ClipFilter): String = "library.list.filter.${filter.name}"

  /** The sort menu's item for [sort]. */
  fun sortOption(sort: ClipSort): String = "library.list.sort.${sort.name}"

  /** The row of clip [id]; its open target and "More actions" button carry `ClipCardTags`. */
  fun row(id: JobId): String = "library.list.row.${id.value}"

  /** The "In progress" row of request [id]. */
  fun request(id: RequestId): String = "library.list.request.${id.value}"

  /** An item of a row's "More actions" menu. */
  fun menu(item: RowMenuItem): String = "library.list.menu.${item.name}"

  // --- The removal confirmation (both screens) ---
  const val REMOVE_DIALOG = "library.remove.dialog"
  const val REMOVE_TITLE = "library.remove.title"
  const val REMOVE_BODY = "library.remove.body"
  const val REMOVE_CANCEL = "library.remove.cancel"
  const val REMOVE_CONFIRM = "library.remove.confirm"

  // --- The Clip page (P-02) ---
  const val CLIP_LIST = "library.clip.list"

  /** The Back button on the hero's overlay plate. */
  const val CLIP_BACK = "library.clip.back"
  const val CLIP_TITLE = "library.clip.title"
  const val CLIP_META = "library.clip.meta"
  const val CLIP_WATCH = "library.clip.watch"
  const val CLIP_FROM_START = "library.clip.from-start"

  /** The line under a disabled "Watch" ("Getting the audio… 40 %"). */
  const val CLIP_PLAY_NOTE = "library.clip.play-note"

  /** A header notice (not on this phone, needs downloading again, older rules, no audio, video). */
  fun clipNotice(notice: ClipNotice): String = "library.clip.notice.${notice.name}"
  const val CLIP_DOWNLOAD = "library.clip.download"
  const val CLIP_DELETED_BANNER = "library.clip.deleted-banner"
  const val CLIP_GONE = "library.clip.gone"
  const val CLIP_PROCESSING = "library.clip.processing"

  /** The tab [tab] (Shorts, Transcript, Summary). */
  fun tab(tab: ClipTab): String = "library.clip.tab.${tab.name}"
  const val SHORTS_LOADING = "library.clip.shorts.loading"
  const val SHORTS_NOT_HERE = "library.clip.shorts.not-here"

  /** The Shorts tab's row of [short]. */
  fun short(short: ShortId): String = "library.clip.short.${short.firstSegment}"

  /** The Summary's "Changes in this clip" row of [label]. */
  fun change(label: ChangeLabel): String = "library.clip.change.${label.value}"
  const val CHANGES_NONE = "library.clip.changes.none"
  const val ABOUT = "library.clip.about"
  const val SOURCE_LINK = "library.clip.about.source"

  /** The "On this phone" block (design 9.26, v1-core part). */
  const val ON_PHONE = "library.clip.on-phone"
  const val SIZE_PACK = "library.clip.on-phone.pack"
  const val SIZE_VIDEO = "library.clip.on-phone.video"
  const val KEEP = "library.clip.on-phone.keep"
  const val DOWNLOAD_VIDEO = "library.clip.on-phone.download-video"
  const val REMOVE_VIDEO = "library.clip.on-phone.remove-video"
  const val REMOVE_FROM_PHONE = "library.clip.on-phone.remove"

  // --- The M2 stub's tags, kept only so that e2e/smoke/PerTabStateTest (integrator-owned) still compiles. ---
  // The real Library has neither an instance marker nor numbered placeholder rows, so nothing carries these tags;
  // e2e/library/LibraryStateTest proves the same per-tab state on the real Library. Change request CR-WP16-1 retires
  // PerTabStateTest, after which both members go.

  /** Not drawn by the real Library (CR-WP16-1). */
  @Deprecated("Nothing carries this tag; e2e/library/LibraryStateTest replaces PerTabStateTest (CR-WP16-1).")
  const val INSTANCE = "library.list.instance"

  /** Not drawn by the real Library: rows are tagged by clip, [row] (CR-WP16-1). */
  @Deprecated("Nothing carries this tag; rows are tagged by clip id (CR-WP16-1).", ReplaceWith("row(id)"))
  fun row(number: Int): String = "library.list.stub-row.$number"
}
