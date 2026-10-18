package com.mapineda48.phonotrainer.feature.requests

import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage

/** Test tags of :feature:requests ("requests.<screen>.<element>", work-plan 2.6). */
object RequestsTags {
  // Requests (P-03).
  const val LINK_ADD = "requests.requests.add"
  const val LIST = "requests.requests.list"
  const val LINE = "requests.requests.line"
  const val PAUSED = "requests.requests.paused"
  const val PAUSED_ACTION = "requests.requests.paused.action"
  const val LOADING = "requests.requests.loading"
  const val ERROR = "requests.requests.error"
  const val RETRY = "requests.requests.retry"
  const val EMPTY = "requests.requests.empty"
  const val EMPTY_ADD = "requests.requests.empty.add"
  const val BROWSER = "requests.requests.browser"
  const val BROWSER_TOGGLE = "requests.requests.browser.toggle"
  const val CANCEL_DIALOG = "requests.requests.cancel.dialog"
  const val REMOVE_DIALOG = "requests.requests.remove.dialog"

  /** The head of a group: "requests.requests.group.ATTENTION". */
  fun group(kind: String): String = "requests.requests.group.$kind"

  /** The card of a request. */
  fun request(id: RequestId): String = "requests.requests.request.${id.value}"

  /** A row of "Started on your computer". */
  fun browserJob(id: JobId): String = "requests.requests.browser.${id.value}"

  /** "Hide on this phone" of a browser job. */
  fun hide(id: JobId): String = "requests.requests.browser.hide.${id.value}"

  // Request detail (P-04).
  const val DETAIL_SOURCE = "requests.detail.source"
  const val DETAIL_LINK_TOGGLE = "requests.detail.link.toggle"
  const val DETAIL_TIMELINE = "requests.detail.timeline"
  const val DETAIL_CARD = "requests.detail.card"
  const val DETAIL_DOING = "requests.detail.doing"
  const val DETAIL_ESTIMATE = "requests.detail.estimate"
  const val DETAIL_FIRST_RUN = "requests.detail.first.run"
  const val DETAIL_DETAILS_TOGGLE = "requests.detail.details.toggle"
  const val DETAIL_DETAILS = "requests.detail.details"
  const val DETAIL_REMOVED = "requests.detail.removed"
  const val DETAIL_LOADING = "requests.detail.loading"

  /** A milestone row of the timeline: "requests.detail.milestone.SENT". */
  fun milestone(name: String): String = "requests.detail.milestone.$name"

  /** A stage row: "requests.detail.stage.PHONES". */
  fun stage(stage: Stage): String = "requests.detail.stage.${stage.name}"

  // Add a clip (P-05).
  const val ADD_LINK = "requests.add.link"
  const val ADD_PASTE = "requests.add.paste"
  const val ADD_SITE = "requests.add.site"
  const val ADD_CHOOSE = "requests.add.choose"
  const val ADD_CLEAR_FILES = "requests.add.files.clear"
  const val ADD_FILES = "requests.add.files"
  const val ADD_AUDIO_ONLY = "requests.add.audio.only"
  const val ADD_SEPARATE = "requests.add.separate"
  const val ADD_LARGE = "requests.add.large"
  const val ADD_LEGAL = "requests.add.legal"
  const val ADD_QUEUED_NOTE = "requests.add.queued"
  const val ADD_SUBMIT = "requests.add.submit"
  const val ADD_PROBLEM = "requests.add.problem"
  const val ADD_DUPLICATE = "requests.add.duplicate"
  const val ADD_OPEN_IT = "requests.add.duplicate.open"
  const val ADD_AGAIN = "requests.add.duplicate.again"
  const val ADD_DONE = "requests.add.done"
  const val ADD_SEE_REQUESTS = "requests.add.done.requests"
  const val ADD_ANOTHER = "requests.add.done.another"
  const val ADD_QUESTION = "requests.add.question"

  /** A chosen file's row, by its position. */
  fun file(index: Int): String = "requests.add.file.$index"

  /** An added file's row, by its request. */
  fun added(id: RequestId): String = "requests.add.added.${id.value}"
}
