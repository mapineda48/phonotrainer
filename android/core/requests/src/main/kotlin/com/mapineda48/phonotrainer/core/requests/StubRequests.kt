package com.mapineda48.phonotrainer.core.requests

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
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteJob
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteLibrary
import com.mapineda48.phonotrainer.core.domain.spi.RequestReconciler
import com.mapineda48.phonotrainer.core.domain.spi.RequestSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

// Working stubs of the contract freeze (work-plan 2.3): the queue is empty and stores nothing. WP-09 replaces this
// file.

class StubRequestRepository @Inject constructor() : RequestRepository {
  override fun requests(): Flow<List<Request>> = flowOf(emptyList())

  override fun request(id: RequestId): Flow<Request?> = flowOf(null)

  override fun counts(): Flow<RequestCounts> =
    flowOf(RequestCounts(waiting = 0, onComputer = 0, needsAttention = 0, readyNew = 0))

  override suspend fun findDuplicate(input: LinkInput): Duplicate? = null

  override suspend fun addLink(input: LinkInput): AddResult = AddResult.NoLink

  override suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult =
    AddFilesResult(added = emptyList(), refused = emptyList())

  override fun holdSources(ids: Set<RequestId>): AutoCloseable = AutoCloseable { }

  override fun intake(id: RequestId): Flow<IntakeProgress> = emptyFlow()

  override suspend fun stopIntake(id: RequestId) = Unit

  override suspend fun perform(id: RequestId, action: RequestAction) = Unit
}

class StubLinkParser @Inject constructor() : LinkParser {
  override fun parse(sharedText: String, subject: String?): ParsedLink? = null
}

class StubRequestSender @Inject constructor() : RequestSender {
  private val nothing = SendReport(sent = 0, refused = 0, transient = 0, blocked = false)

  override suspend fun sendOperations(): SendReport = nothing

  override suspend fun sendLinks(): SendReport = nothing

  override fun scheduleUploads() = Unit
}

class StubRequestReconciler @Inject constructor() : RequestReconciler {
  override suspend fun reconcile(library: RemoteLibrary) = Unit

  override suspend fun onJob(job: RemoteJob) = Unit

  override suspend fun onJobDeleted(id: JobId) = Unit

  override suspend fun onClipOnPhone(id: JobId) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
interface RequestsModule {
  @Binds
  fun requests(impl: StubRequestRepository): RequestRepository

  @Binds
  fun linkParser(impl: StubLinkParser): LinkParser

  @Binds
  fun sender(impl: StubRequestSender): RequestSender

  @Binds
  fun reconciler(impl: StubRequestReconciler): RequestReconciler
}
