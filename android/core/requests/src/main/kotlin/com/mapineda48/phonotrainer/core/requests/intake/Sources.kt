package com.mapineda48.phonotrainer.core.requests.intake

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream

/** What a provider says about a source. Untrusted (security review M-4): names, types and sizes may lie. */
internal data class SourceFacts(val displayName: String?, val sizeBytes: Long?, val mimeType: String?)

/** Where an extractor can read a seekable source from: a descriptor range, or a local file. */
internal sealed interface ExtractorInput {
  data class Descriptor(val descriptor: FileDescriptor, val length: Long) : ExtractorInput

  data class Path(val file: File) : ExtractorInput
}

/**
 * An opened source (architecture 8.3): a shared item's descriptor, opened while its share sheet was visible and kept
 * in [IntakeHandles], a picked item reopened with its lasting permission, or the copy already kept on the phone.
 */
internal interface SourceStream : Closeable {
  /** A regular file can be read again from the start; a pipe cannot. */
  val seekable: Boolean

  /** Moves back to the first byte; false when the source cannot (then a stopped copy cannot restart). */
  fun rewind(): Boolean

  /** The bytes from the current position. Closing it does not close the source. */
  fun stream(): InputStream

  /** A seekable source for `MediaExtractor`, or null. */
  fun extractorInput(): ExtractorInput?
}

/** The app's access to shared and picked items (architecture 8.3, 20.2). */
internal interface SourceReader {
  /** Whether [uri] is not a `content://` URI of another app: `file://` and this app's own providers are refused. */
  fun refuses(uri: String): Boolean

  /** What the provider says; never throws (unreadable facts are null). */
  fun facts(uri: String): SourceFacts

  /** Opens [uri] for reading; throws when it cannot be read now (permission gone, file removed, cloud file offline). */
  fun open(uri: String): SourceStream

  /** Takes the lasting read permission of a picked item; false when the grant does not allow it. */
  fun keepReadable(uri: String): Boolean

  /** Releases a lasting read permission (ignored when there is none). */
  fun release(uri: String)
}

/** Free space as architecture 7.5 measures it: what can be allocated, and the floor nothing may cross. */
internal interface FreeSpace {
  fun allocatableBytes(): Long

  /** `min(1 GB, 10 % of total)`. */
  fun floorBytes(): Long
}

/** The audio track of a local video, remuxed without re-encoding (architecture 8.3). */
internal interface AudioExtraction {
  /**
   * Writes the audio of [input] to [target]; the extension of the result (`m4a`, `ogg`, `webm`), or null when the
   * track's codec has no container here, or anything fails (then the whole file is kept).
   */
  fun extract(input: ExtractorInput, target: File, onProgress: (Long) -> Unit): String?
}

/** A local file as a source: the copy kept on the phone ("Try audio only" after `too_large`, architecture 8.4). */
internal class FileSourceStream(private val file: File) : SourceStream {
  private var input: FileInputStream? = null

  override val seekable: Boolean = true

  override fun rewind(): Boolean {
    input?.close()
    input = null
    return true
  }

  override fun stream(): InputStream = FileInputStream(file).also {
    input?.close()
    input = it
  }

  override fun extractorInput(): ExtractorInput = ExtractorInput.Path(file)

  override fun close() {
    input?.close()
    input = null
  }
}
