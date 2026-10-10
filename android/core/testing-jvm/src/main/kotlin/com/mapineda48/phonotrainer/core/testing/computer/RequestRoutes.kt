package com.mapineda48.phonotrainer.core.testing.computer

import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_BAD_REQUEST
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CONFLICT
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_CREATED
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_FORBIDDEN
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_GONE
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_OK
import com.mapineda48.phonotrainer.core.testing.computer.ComputerCore.Companion.HTTP_UNAVAILABLE
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse

// Links, cancel, delete and re-run (server-contract 6.5, 6.7).

/** `POST /api/phone/jobs/link`: idempotent link creation behind the SSRF guard. */
internal fun ComputerCore.createLink(call: Call): MockResponse {
  val device = call.device!!
  val body = jsonBody(call)
  val key = requestKey(body.string("client_request_id")) ?: throw invalidRequest("client_request_id must be a UUID.")
  val rawUrl = body.string("url") ?: throw invalidRequest("url is required.")
  val audioOnly = body.nullableBoolean("audio_only") ?: false
  val separate = body.nullableBoolean("separate_dialogue") ?: true
  val url = LinkRules.accept(rawUrl)
  jobForKey(device.id, key)?.let { existing ->
    if (existing.kind == SourceKind.LINK && existing.rerunOf == null && existing.url == url) {
      return json(HTTP_OK, buildJsonObject {
        put("job", jobJson(existing, device.id))
        put("created", false)
      })
    }
    throw keyConflict()
  }
  if (sessions.containsKey(device.id to key)) throw keyConflict()
  if (url == null) throw Refusal(HTTP_BAD_REQUEST, "invalid_url", "This is not a link PhonoTrainer can open.")
  checkLink(url)
  val job = newJob(SourceKind.LINK, url, null, audioOnly, separate, device.id, key, rerunOf = null)
  job.source = FakeMediaBytes.video()
  publish(job)
  return json(HTTP_CREATED, buildJsonObject {
    put("job", jobJson(job, device.id))
    put("created", true)
  })
}

/** The SSRF guard of 4.5 at creation and re-run. */
internal fun ComputerCore.checkLink(url: String) {
  val host = LinkRules.host(url) ?: throw Refusal(HTTP_BAD_REQUEST, "invalid_url", "This is not a link.")
  when (LinkRules.classify(host, linkHosts)) {
    LinkHostAnswer.PUBLIC -> Unit
    LinkHostAnswer.REFUSED ->
      throw Refusal(HTTP_BAD_REQUEST, "refused_address", "This link points to a private or local address.")
    LinkHostAnswer.UNRESOLVABLE ->
      throw Refusal(HTTP_UNAVAILABLE, "cannot_check_link", "This computer could not look up the link's website.")
  }
}

internal fun keyConflict() =
  Refusal(HTTP_CONFLICT, "key_conflict", "This request id was already used for a different request.")

/** An own job, or `404 unknown_job` / `403 not_own_job` (6.7). */
private fun ComputerCore.ownJob(call: Call): Job {
  val job = knownJob(call.segments[3])
  if (!job.ownedBy(call.device!!.id)) {
    throw Refusal(HTTP_FORBIDDEN, "not_own_job", "This job was not sent from this phone.")
  }
  return job
}

/** `POST /api/phone/jobs/{id}/cancel`. */
internal fun ComputerCore.cancel(call: Call): MockResponse {
  val job = ownJob(call)
  when (job.status) {
    JobStatus.DONE -> throw Refusal(
      HTTP_CONFLICT, "already_finished", "This job already finished; it was not cancelled.",
      extra = mapOf("job" to jobJson(job, call.device!!.id)),
    )
    JobStatus.ERROR, JobStatus.CANCELLED -> Unit
    JobStatus.QUEUED -> cancelNow(job, "Cancelled before starting.")
    JobStatus.RUNNING -> if (deferRunningCancel) {
      job.cancelRequested = true
      publishChange(job)
    } else {
      cancelNow(job, "Cancelled during the analysis.")
    }
  }
  return json(HTTP_OK, buildJsonObject { put("job", jobJson(job, call.device!!.id)) })
}

/** Moves a job to `cancelled` (the store's two messages, 8.2 row 1). */
internal fun ComputerCore.cancelNow(job: Job, message: String) {
  job.status = JobStatus.CANCELLED
  job.cancelRequested = false
  job.stage = null
  job.errorCode = "cancelled"
  job.errorMessage = message
  job.finishedAtMillis = serverNowMillis()
  publishChange(job)
}

/** `DELETE /api/phone/jobs/{id}`: only failed or cancelled own jobs (6.7, spec D-07). */
internal fun ComputerCore.deleteJobRoute(call: Call): MockResponse {
  val job = ownJob(call)
  if (job.status != JobStatus.ERROR && job.status != JobStatus.CANCELLED) {
    throw Refusal(HTTP_CONFLICT, "not_deletable", "Delete it on your computer.")
  }
  removeJob(job.id)
  return noContent()
}

/** `POST /api/phone/jobs/{id}/rerun`: "Try again", "Try audio only", the automatic re-run after `interrupted`. */
internal fun ComputerCore.rerun(call: Call): MockResponse {
  val device = call.device!!
  val body = jsonBody(call)
  val key = requestKey(body.string("client_request_id")) ?: throw invalidRequest("client_request_id must be a UUID.")
  val audioOnly = body.nullableBoolean("audio_only")
  val oldId = call.segments[3]
  jobForKey(device.id, key)?.let { created ->
    if (created.rerunOf == oldId) return replaced(HTTP_OK, created, oldId, device.id)
    throw keyConflict()
  }
  if (sessions.containsKey(device.id to key)) throw keyConflict()
  val old = ownJob(call)
  if (old.status != JobStatus.ERROR && old.status != JobStatus.CANCELLED) {
    throw Refusal(HTTP_CONFLICT, "not_rerunnable", "Only a failed or cancelled job can run again.")
  }
  if (audioOnly != null && old.kind != SourceKind.LINK) {
    throw Refusal(HTTP_BAD_REQUEST, "invalid_option", "Audio only applies to links.")
  }
  val created = when (old.kind) {
    SourceKind.LINK -> {
      val url = old.url ?: throw Refusal(HTTP_GONE, "source_gone", "This computer no longer has this link.")
      checkLink(url)
      newJob(
        SourceKind.LINK, url, null, audioOnly ?: old.audioOnly, old.separateDialogue, device.id, key, rerunOf = old.id,
      ).also { it.source = FakeMediaBytes.video() }
    }
    else -> {
      val source = old.source?.takeIf { !old.sourceGone }
        ?: throw Refusal(HTTP_GONE, "source_gone", "Your computer no longer has this file.")
      newJob(old.kind, null, old.fileName, false, old.separateDialogue, device.id, key, rerunOf = old.id).also {
        it.source = source // moved, not copied
        old.source = null
      }
    }
  }
  removeJob(old.id)
  publish(created)
  return replaced(HTTP_CREATED, created, old.id, device.id)
}

private fun ComputerCore.replaced(status: Int, job: Job, oldId: String, viewer: String): MockResponse =
  json(status, buildJsonObject {
    put("job", jobJson(job, viewer))
    put("replaced", oldId)
  })
