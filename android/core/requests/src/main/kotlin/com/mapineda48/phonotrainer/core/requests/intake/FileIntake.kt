package com.mapineda48.phonotrainer.core.requests.intake

import com.mapineda48.phonotrainer.core.common.di.IoDispatcher
import com.mapineda48.phonotrainer.core.common.hash.HashedBytes
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.common.hash.Sha256Hasher
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import com.mapineda48.phonotrainer.core.database.library.entity.RequestEntity
import com.mapineda48.phonotrainer.core.datastore.pairing.PairingStore
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.requests.store.QueueCore
import com.mapineda48.phonotrainer.core.requests.store.QueueLive
import com.mapineda48.phonotrainer.core.requests.store.RequestWork
import com.mapineda48.phonotrainer.core.requests.rules.CopyCodes
import com.mapineda48.phonotrainer.core.requests.rules.MediaTypes
import com.mapineda48.phonotrainer.core.requests.rules.QueueEvent
import com.mapineda48.phonotrainer.core.requests.rules.stored
import com.mapineda48.phonotrainer.core.requests.rules.storedState
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How one intake ended, for its worker. */
internal enum class IntakeResult {
  /** The copy is complete (or the request moved on): nothing more to do. */
  DONE,

  /** The copy failed; the request says why. */
  FAILED,
}

/**
 * Files into the queue (architecture 8.3): the checks before a request exists, then the copy into
 * `requests/<id>/.copy.part` through a SHA-256 digest, counting the bytes that really arrive (providers may lie about
 * sizes), renamed to `source.<ext>` and marked complete in one transaction. With "Audio only" a seekable video's audio
 * track is remuxed without re-encoding; when that is impossible the whole file is kept and the request says so.
 */
@Singleton
internal class FileIntake @Inject constructor(
  private val core: QueueCore,
  private val live: QueueLive,
  private val handles: IntakeHandles,
  private val reader: SourceReader,
  private val space: FreeSpace,
  private val extraction: AudioExtraction,
  private val work: RequestWork,
  private val pairing: PairingStore,
  @param:IoDispatcher private val io: CoroutineDispatcher,
  private val log: PhonoLog,
) {
  /**
   * Checks and stores each file as its own request (`PREPARING`), then hands its copy to the intake work: the
   * expedited shared-file work with the descriptor opened now, while the share sheet is visible, or the picked-file
   * work, which opens the file again with its lasting permission (F-19-AC4).
   */
  suspend fun add(sources: List<FileSource>, options: FileOptions, serverId: String?): AddFilesResult =
    withContext(io) {
      val added = mutableListOf<RequestId>()
      val refused = mutableListOf<FileRefusal>()
      val maxUpload = maxUploadBytes()
      var reserved = 0L
      sources.forEachIndexed { index, source ->
        if (index >= MAX_ITEMS) {
          refused += FileRefusal(MediaTypes.safeDisplayName(source.displayName), FileRefusalReason.TOO_MANY, null)
          return@forEachIndexed
        }
        val checked = check(source, options, maxUpload, reserved)
        when (checked) {
          is Checked.Refused -> refused += checked.refusal
          is Checked.Accepted -> {
            val id = store(checked, options, serverId)
            if (id == null) {
              checked.stream?.close()
              refused += FileRefusal(checked.name, FileRefusalReason.NO_SPACE, null)
            } else {
              added += id
              reserved += checked.reserve
              if (checked.stream != null) {
                handles.put(id, checked.stream)
                work.startShareIntake(id)
              } else {
                work.startFileIntake(id)
              }
            }
          }
        }
      }
      AddFilesResult(added, refused)
    }

  /**
   * Copies the source of request [id] (both intake works run this). A stopped worker ([CancellationException]) keeps
   * the request `PREPARING` when its source can be read again from the start, so the work's retry copies it again;
   * a shared source that cannot (a pipe, or a descriptor lost with the process) ends `COPY_FAILED` `copy_interrupted`
   * (QT-13).
   */
  suspend fun copy(id: RequestId): IntakeResult = withContext(io) {
    val row = core.requests.get(id.value)
    if (row == null || storedState(row.state) != RequestState.PREPARING) {
      handles.drop(id)
      return@withContext IntakeResult.DONE
    }
    val limit = maxUploadBytes()
    val kept = core.files.resolve(id, row.copyFile)?.takeIf { it.isFile && row.audioOnly && !row.copyComplete }
    val source: SourceStream = when {
      kept != null -> FileSourceStream(kept)
      row.sourcePersisted -> runCatching { row.sourceUri?.let(reader::open) }.getOrNull()
        ?: return@withContext fail(id, CopyCodes.UNREADABLE, null)
      else -> handles.get(id)?.takeIf { it.rewind() }
        ?: return@withContext fail(id, CopyCodes.INTERRUPTED, null)
    }
    try {
      copyFrom(id, row, source, kept, limit)
    } catch (stopped: CancellationException) {
      withContext(NonCancellable) { stoppedWhileCopying(id, row, source) }
      throw stopped
    } finally {
      live.copyingDone(id)
      if (kept != null || row.sourcePersisted) source.close()
    }
  }

  private suspend fun copyFrom(id: RequestId, row: RequestEntity, source: SourceStream, kept: File?, limit: Long): IntakeResult {
    val files = core.files
    files.dir(id).mkdirs()
    val part = if (kept != null) files.audioPart(id) else files.part(id)
    part.delete()
    val video = core.isVideo(row)
    val outcome = try {
      when {
        row.audioOnly && video && source.seekable -> extractAudio(id, source, part, limit)
          ?: if (kept != null) keptAsItIs(row, kept) else wholeCopy(id, row, source, part, limit, fallback = true)
        else -> wholeCopy(id, row, source, part, limit, fallback = false)
      }
    } catch (failure: CopyFailure) {
      part.delete()
      return fail(id, failure.code, failure.neededBytes)
    }
    val extension = outcome.extension ?: RequestExtension.of(row) ?: return fail(id, CopyCodes.UNREADABLE, null)
    val target = files.copy(id, extension)
    val produced = outcome.file ?: part
    if (produced != target) {
      if (kept != null && kept != target) kept.delete()
      if (!produced.renameTo(target)) {
        produced.delete()
        return fail(id, CopyCodes.NO_SPACE, null)
      }
    }
    val applied = core.apply(
      id,
      QueueEvent.CopyComplete(
        copyFile = files.relative(target),
        sha256 = outcome.hashed.sha256,
        bytes = outcome.hashed.bytes,
        audioExtracted = outcome.extension != null,
        wholeFileFallback = outcome.fallback,
      ),
    )
    handles.drop(id)
    if (applied == null) {
      // Removed or stopped while the copy ran: nothing of it stays.
      files.delete(id)
    }
    return IntakeResult.DONE
  }

  /** The copy already on the phone stays as it is when its audio cannot be separated ("Sent the whole file"). */
  private fun keptAsItIs(row: RequestEntity, kept: File): Copied {
    val hashed = row.fileSha256?.takeIf { Sha256.isHex(it) }?.let { HashedBytes(it, kept.length()) } ?: Sha256.of(kept)
    return Copied(hashed, extension = null, fallback = true, file = kept)
  }

  /** The audio track alone (architecture 8.3); null when it cannot be separated here. */
  private fun extractAudio(id: RequestId, source: SourceStream, part: File, limit: Long): Copied? {
    val input = source.extractorInput() ?: return null
    val extension = try {
      extraction.extract(input, part) { bytes -> live.copying(id, bytes, null) }
    } catch (expected: Exception) {
      log.w("requests.audio_extraction_failed", expected)
      null
    }
    if (extension == null || !part.isFile || part.length() == 0L) {
      part.delete()
      if (space.allocatableBytes() < space.floorBytes()) throw CopyFailure(CopyCodes.NO_SPACE, space.floorBytes())
      source.rewind()
      return null
    }
    if (part.length() > limit) throw CopyFailure(CopyCodes.NO_SPACE, null)
    return Copied(Sha256.of(part), extension, fallback = false)
  }

  /**
   * The whole file, hashed while it is written; aborts as `copy_no_space` when the count exceeds the computer's limit,
   * a declared size by more than [DECLARED_SLACK_BYTES], or would cross the free-space floor (architecture 8.3).
   */
  private suspend fun wholeCopy(
    id: RequestId,
    row: RequestEntity,
    source: SourceStream,
    part: File,
    limit: Long,
    fallback: Boolean,
  ): Copied {
    val declared = row.fileBytes?.takeIf { it > 0 }
    val hasher = Sha256Hasher()
    val input = try {
      source.stream()
    } catch (unreadable: Exception) {
      throw CopyFailure(CopyCodes.UNREADABLE, null, unreadable)
    }
    val output = try {
      part.outputStream()
    } catch (full: IOException) {
      throw CopyFailure(CopyCodes.NO_SPACE, declared, full)
    }
    output.use { out ->
      val buffer = ByteArray(Sha256.BUFFER_BYTES)
      var sinceCheck = SPACE_CHECK_BYTES
      while (true) {
        currentCoroutineContext().ensureActive()
        val read = read(input, buffer)
        if (read < 0) break
        val total = hasher.bytes + read
        if (total > limit) throw CopyFailure(CopyCodes.NO_SPACE, null)
        if (declared != null && total > declared + DECLARED_SLACK_BYTES) throw CopyFailure(CopyCodes.NO_SPACE, null)
        sinceCheck += read
        if (sinceCheck >= SPACE_CHECK_BYTES) {
          sinceCheck = 0
          val free = space.allocatableBytes()
          val floor = space.floorBytes()
          if (free - read < floor) {
            val expected = maxOf(declared ?: total, total)
            throw CopyFailure(CopyCodes.NO_SPACE, neededBytes(expected, hasher.bytes, free, floor))
          }
        }
        write(out, buffer, read, declared)
        hasher.update(buffer, 0, read)
        live.copying(id, hasher.bytes, declared)
      }
    }
    return Copied(hasher.result(), extension = null, fallback = fallback)
  }

  private fun read(input: InputStream, buffer: ByteArray): Int = try {
    input.read(buffer)
  } catch (unreadable: IOException) {
    throw CopyFailure(CopyCodes.UNREADABLE, null, unreadable)
  } catch (unreadable: SecurityException) {
    throw CopyFailure(CopyCodes.UNREADABLE, null, unreadable)
  }

  private fun write(out: OutputStream, buffer: ByteArray, length: Int, declared: Long?) = try {
    out.write(buffer, 0, length)
  } catch (full: IOException) {
    throw CopyFailure(CopyCodes.NO_SPACE, declared, full)
  }

  /** What must be freed so the whole copy fits above the floor, once the partial copy is deleted. */
  private fun neededBytes(expected: Long, copied: Long, free: Long, floor: Long): Long =
    maxOf(expected + floor - (free + copied), MIN_NEEDED_BYTES)

  private suspend fun stoppedWhileCopying(id: RequestId, row: RequestEntity, source: SourceStream) {
    core.files.part(id).delete()
    val now = core.requests.get(id.value) ?: return
    if (storedState(now.state) != RequestState.PREPARING) return
    if (!row.sourcePersisted && !source.seekable) {
      log.w("requests.shared_copy_stopped")
      fail(id, CopyCodes.INTERRUPTED, null)
    }
  }

  private suspend fun fail(id: RequestId, code: String, neededBytes: Long?): IntakeResult {
    core.apply(id, QueueEvent.CopyFailed(code, neededBytes))
    handles.drop(id)
    live.copyingDone(id)
    return IntakeResult.FAILED
  }

  // -------------------------------------------------------------------------------------------------------------
  // Before a request exists (architecture 8.3, spec 8.3)

  private sealed interface Checked {
    data class Refused(val refusal: FileRefusal) : Checked

    data class Accepted(
      val source: FileSource,
      val name: String?,
      val mime: String?,
      val declared: Long?,
      val video: Boolean,
      val persisted: Boolean,
      /** The shared item's descriptor, opened now; null for a picked item. */
      val stream: SourceStream?,
      /** Space this copy is expected to take, for the next items' checks. */
      val reserve: Long,
    ) : Checked
  }

  private fun check(source: FileSource, options: FileOptions, maxUpload: Long, reserved: Long): Checked {
    val facts = runCatching { reader.facts(source.uri) }.getOrDefault(SourceFacts(null, null, null))
    val name = MediaTypes.safeDisplayName(source.displayName ?: facts.displayName)
    val mime = (source.mimeType ?: facts.mimeType)?.take(MAX_MIME_CHARS)
    fun refuse(reason: FileRefusalReason, needed: Long? = null) = Checked.Refused(FileRefusal(name, reason, needed))
    // Only another app's content can be read: `file://` is refused (Android review L8), and so is this app's own
    // provider (security review M-4).
    if (!source.uri.startsWith(CONTENT_SCHEME)) return refuse(FileRefusalReason.UNREADABLE)
    if (reader.refuses(source.uri)) return refuse(FileRefusalReason.OWN_PROVIDER)
    val extension = MediaTypes.extensionOf(name, mime) ?: return refuse(FileRefusalReason.UNSUPPORTED_TYPE)
    val video = MediaTypes.isVideo(extension, mime)
    val audioOnly = options.audioOnly && video
    val declared = (source.declaredBytes ?: facts.sizeBytes)?.takeIf { it >= 0 }
    // A video sent as audio only shrinks on the phone: only the copy's own byte count can refuse it then.
    if (declared != null && !audioOnly && declared > maxUpload) return refuse(FileRefusalReason.TOO_LARGE)
    if (declared != null && !audioOnly) {
      val free = space.allocatableBytes() - reserved
      val floor = space.floorBytes()
      if (free - declared < floor) return refuse(FileRefusalReason.NO_SPACE, declared + floor - free)
    }
    val persisted = source.persisted && reader.keepReadable(source.uri)
    val stream = try {
      reader.open(source.uri)
    } catch (expected: Exception) {
      log.w("requests.source_unreadable", expected)
      if (persisted) reader.release(source.uri)
      return refuse(FileRefusalReason.UNREADABLE)
    }
    val shared = if (persisted) {
      stream.close()
      null
    } else {
      stream
    }
    return Checked.Accepted(source, name, mime, declared, video, persisted, shared, if (audioOnly) 0 else declared ?: 0)
  }

  private suspend fun store(checked: Checked.Accepted, options: FileOptions, serverId: String?): RequestId? {
    val now = core.now()
    val row = RequestEntity(
      id = core.ids.next(),
      seq = 0,
      kind = RequestKind.FILE.stored(),
      state = RequestState.PREPARING.stored(),
      createdAt = now,
      updatedAt = now,
      fileName = checked.name,
      fileMime = checked.mime,
      fileBytes = checked.declared,
      sourceUri = checked.source.uri,
      sourcePersisted = checked.persisted,
      audioOnly = options.audioOnly && checked.video,
      separateDialogue = options.separateDialogue,
      targetServerId = serverId,
    )
    return try {
      RequestId(core.insert(row).id)
    } catch (expected: Exception) {
      // The record itself could not be written (the phone is out of space): nothing is pretended (8.1).
      log.w("requests.insert_failed", expected)
      if (checked.persisted) reader.release(checked.source.uri)
      null
    }
  }

  private suspend fun maxUploadBytes(): Long =
    runCatching { pairing.current()?.capabilities?.maxUploadBytes }.getOrNull()?.takeIf { it > 0 }
      ?: DEFAULT_MAX_UPLOAD_BYTES

  /** A finished copy: its digest, the extension of an extracted track, and the file when it is not the part. */
  private data class Copied(val hashed: HashedBytes, val extension: String?, val fallback: Boolean, val file: File? = null)

  private class CopyFailure(val code: String, val neededBytes: Long?, cause: Throwable? = null) :
    Exception(code, cause)

  companion object {
    /** Multi-select: at most 10 items, one request each (spec 8.1). */
    const val MAX_ITEMS: Int = 10

    /** The limit when the phone has never been connected (spec 8.3). */
    const val DEFAULT_MAX_UPLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** A copy may exceed the provider's declared size by this much before it is refused (architecture 8.3). */
    const val DECLARED_SLACK_BYTES: Long = 1024L * 1024

    private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024
    private const val MIN_NEEDED_BYTES = 1024L * 1024
    private const val MAX_MIME_CHARS = 100
    private const val CONTENT_SCHEME = "content://"
  }
}

/** The extension of a request's copy, from its name and type. */
internal object RequestExtension {
  fun of(row: RequestEntity): String? = MediaTypes.extensionOf(row.fileName, row.fileMime)
}
