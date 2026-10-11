package com.mapineda48.phonotrainer.core.testing.fake.requests

import com.mapineda48.phonotrainer.core.domain.api.LinkParser
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.SendReport
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

// Settable fakes of the :core:requests interfaces (work-plan 4.6; WP-09 owns this folder after B1).

/**
 * A settable [LinkParser] for the share sheet's and "Add a clip"'s tests (the real rules, `LinksV1`, live in
 * `:core:analysis`, which features cannot use). By default: the first http(s) URL of the text (or of the subject),
 * sent as shared, its own duplicate key, the site from the host (`youtu.be`/`youtube.com`, `/shorts/`, `x.com`/
 * `twitter.com`), otherwise [Site.OTHER]. [parsed] records every call.
 */
class FakeLinkParser(var rule: (sharedText: String, subject: String?) -> ParsedLink? = ::firstLink) : LinkParser {
  val parsed = mutableListOf<Pair<String, String?>>()

  override fun parse(sharedText: String, subject: String?): ParsedLink? {
    parsed += sharedText to subject
    return rule(sharedText, subject)
  }

  companion object {
    private val URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun firstLink(sharedText: String, subject: String?): ParsedLink? {
      val url = (URL.find(sharedText) ?: subject?.let(URL::find))?.value?.trimEnd('.', ',', ';', ':', '!', '?') ?: return null
      val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
      val site = when {
        host.endsWith("youtube.com") && url.contains("/shorts/") -> Site.YOUTUBE_SHORTS
        host == "youtu.be" || host.endsWith("youtube.com") -> Site.YOUTUBE
        host == "x.com" || host.endsWith("twitter.com") || host == "t.co" -> Site.X
        else -> Site.OTHER
      }
      return ParsedLink(shared = url, sent = url, key = url, site = site)
    }
  }
}

class FakeRequestRepository : RequestRepository {
  val all = MutableStateFlow(emptyList<Request>())
  val countsState = MutableStateFlow(RequestCounts(0, 0, 0, 0))
  var duplicate: Duplicate? = null
  var addLinkResult: (LinkInput) -> AddResult = { AddResult.Added(RequestId("added-${it.sharedText.hashCode()}")) }
  var addFilesResult: (List<FileSource>) -> AddFilesResult = { AddFilesResult(emptyList(), emptyList()) }
  val intakes = mutableMapOf<RequestId, Flow<IntakeProgress>>()
  val performed = mutableListOf<Pair<RequestId, RequestAction>>()
  val links = mutableListOf<LinkInput>()
  var heldSources = 0
    private set

  override fun requests(): Flow<List<Request>> = all

  override fun request(id: RequestId): Flow<Request?> = all.map { list -> list.firstOrNull { it.id == id } }

  override fun counts(): Flow<RequestCounts> = countsState

  override suspend fun findDuplicate(input: LinkInput): Duplicate? = duplicate

  override suspend fun addLink(input: LinkInput): AddResult {
    links += input
    return addLinkResult(input)
  }

  override suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult = addFilesResult(files)

  override fun holdSources(ids: Set<RequestId>): AutoCloseable {
    heldSources++
    return AutoCloseable { heldSources-- }
  }

  override fun intake(id: RequestId): Flow<IntakeProgress> = intakes[id] ?: emptyFlow()

  override suspend fun stopIntake(id: RequestId) {
    performed += id to RequestAction.CANCEL_PREPARING
  }

  override suspend fun perform(id: RequestId, action: RequestAction) {
    performed += id to action
  }
}

class FakeRequestSender : RequestSender {
  var report = SendReport(sent = 0, refused = 0, transient = 0, blocked = false)
  var operationsSent = 0
    private set
  var linksSent = 0
    private set
  var uploadsScheduled = 0
    private set

  override suspend fun sendOperations(): SendReport {
    operationsSent++
    return report
  }

  override suspend fun sendLinks(): SendReport {
    linksSent++
    return report
  }

  override fun scheduleUploads() {
    uploadsScheduled++
  }
}

class FakeRequestReconciler : RequestReconciler {
  val libraries = mutableListOf<RemoteLibrary>()
  val jobs = mutableListOf<RemoteJob>()
  val deleted = mutableListOf<JobId>()
  val onPhone = mutableListOf<JobId>()

  override suspend fun reconcile(library: RemoteLibrary) {
    libraries += library
  }

  override suspend fun onJob(job: RemoteJob) {
    jobs += job
  }

  override suspend fun onJobDeleted(id: JobId) {
    deleted += id
  }

  override suspend fun onClipOnPhone(id: JobId) {
    onPhone += id
  }
}
