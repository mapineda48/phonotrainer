package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.ui.R

/** What a failure's copy depends on besides the error (architecture 19). [available] is `Request.actions`. */
data class FailureContext(
  val kind: RequestKind,
  val site: Site?,
  val available: Set<RequestAction>,
  /** `PairedComputer.capabilities?.maxLinkDurationS`. */
  val maxLinkDurationS: Long?,
)

/** A failure in words: its title, its body and the actions, in the spec 8.6 order intersected with the available. */
data class FailureCopy(val title: String, val body: String, val actions: List<RequestAction>)

/**
 * The one mapping from a request failure to its copy (architecture 19, delivery review D-24; WP-22). Request rows,
 * the share sheet and `Notifier` all use it. The strings live in `strings_request.xml`, so both functions take the
 * [Resources] to read them from (an addition to the architecture's signature: a plain object cannot reach string
 * resources otherwise). M2 placeholder: one generic copy, the computer's cleaned message as the body.
 */
object RequestFailureCopy {
  fun of(error: RequestError, context: FailureContext, resources: Resources): FailureCopy = FailureCopy(
    title = resources.getString(R.string.request_failure_title),
    body = error.message ?: resources.getString(R.string.request_failure_body),
    actions = RequestAction.entries.filter { it in context.available },
  )

  /** F-23: the line of a "Needs attention" notification (placeholder: the generic title for every [error] and [site]). */
  fun notificationLine(error: RequestError, site: Site?, resources: Resources): String =
    resources.getString(R.string.request_failure_notification, resources.getString(R.string.request_failure_title))
}
