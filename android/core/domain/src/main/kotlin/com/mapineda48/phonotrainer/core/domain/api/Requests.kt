package com.mapineda48.phonotrainer.core.domain.api

import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.LinkInput
import com.mapineda48.phonotrainer.core.domain.model.ParsedLink
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestCounts
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.coroutines.flow.Flow

// :core:requests (WP-09), architecture 3.4 and 8.

interface RequestRepository {
  fun requests(): Flow<List<Request>>

  fun request(id: RequestId): Flow<Request?>

  /** Own requests only (F-03). */
  fun counts(): Flow<RequestCounts>

  suspend fun findDuplicate(input: LinkInput): Duplicate?

  /** Stored before returning. */
  suspend fun addLink(input: LinkInput): AddResult

  suspend fun addFiles(files: List<FileSource>, options: FileOptions): AddFilesResult

  /** The share sheet: its read grant lives until close() (architecture 8.3). */
  fun holdSources(ids: Set<RequestId>): AutoCloseable

  /** Copy progress for the share sheet. */
  fun intake(id: RequestId): Flow<IntakeProgress>

  suspend fun stopIntake(id: RequestId)

  suspend fun perform(id: RequestId, action: RequestAction)
}

fun interface LinkParser {
  fun parse(sharedText: String, subject: String?): ParsedLink?
}
